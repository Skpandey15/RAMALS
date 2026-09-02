package io.ramals.learningplatform.registration;

import io.micrometer.core.instrument.MeterRegistry;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.learner.UnknownLearningDomainException;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns learning journey orchestration, its compatibility projection into {@code core.learner_goal},
 * and the JOURNEY_PENDING to ONBOARDED transition (M1-PROF-01 LLD section 8.6, Doc 03 section 8).
 *
 * <p>The projection is not a side effect that happens to run afterwards. Doc 03 section 8.4 requires
 * that a failure to create or maintain it prevents ONBOARDED, so the journey write, the goal
 * projection and the transition share one transaction: if the projection throws, the rollback takes
 * the transition with it and the learner stays at JOURNEY_PENDING with no half-built goal. A learner
 * reported as onboarded whose deterministic-core goal was never written is precisely the
 * inconsistency that requirement exists to prevent.
 *
 * <p>The projection goes through {@link LearnerRepository#upsertGoal}, the same method the legacy
 * {@code PUT /me/goal} uses, rather than a parallel INSERT. Two writers of one row that do not share
 * an implementation eventually disagree about it.
 *
 * <p>Nothing here consults an AI service. Onboarding eligibility is a deterministic-core decision.
 */
@Service
class LearningJourneyService {

  private static final Logger LOGGER = LoggerFactory.getLogger(LearningJourneyService.class);

  /**
   * The states in which a journey may be written.
   *
   * <p>JOURNEY_PENDING is the step. ONBOARDED is included so a learner can revise their journey
   * afterwards without being sent back through onboarding; because the advance is separately guarded
   * to JOURNEY_PENDING, such an edit re-projects the goal and moves nobody. Anything earlier is
   * refused: Doc 03 section 10 forbids reaching ONBOARDED from an unfinished gate, and the cheapest
   * place to honour that is before anything is written.
   */
  private static final Set<String> WRITABLE_STATES =
      Set.of(OnboardingService.JOURNEY_PENDING, OnboardingService.ONBOARDED);

  private final LearnerRepository learners;
  private final RegistrationRepository registrations;
  private final LearningJourneyRepository journeys;
  private final MeterRegistry meterRegistry;

  LearningJourneyService(LearnerRepository learners, RegistrationRepository registrations,
      LearningJourneyRepository journeys, MeterRegistry meterRegistry) {
    this.learners = learners;
    this.registrations = registrations;
    this.journeys = journeys;
    this.meterRegistry = meterRegistry;
  }

  /**
   * The active domains the journey step may offer.
   *
   * <p>Exposed through this service rather than letting the controller reach the repository: HTTP
   * code goes through an application service or a port, which ArchitectureGuardrailTests enforces.
   * The catalog is a journey-step concern, so it belongs to the service that owns the step.
   */
  @Transactional(readOnly = true)
  List<LearningDomainResponse> availableDomains() {
    return learners.findActiveDomains().stream()
        .map(domain -> new LearningDomainResponse(domain.code(), domain.name()))
        .toList();
  }

  /** The caller's journey, or empty when they have not completed the step. */
  @Transactional(readOnly = true)
  Optional<LearningJourneyResponse> current(String subject) {
    Learner learner = learners.provisionForSubject(subject);
    return journeys.find(learner.id()).map(LearningJourneyResponse::from);
  }

  /**
   * Stores the journey, projects the primary goal, and completes onboarding when this clears the
   * last gate.
   *
   * <p>Repeated submission is idempotent by construction: the journey upserts one row per learner,
   * the goal projection upserts one row per learner, and the guarded transition matches nothing the
   * second time -- so a learner who double-submits ends onboarded once, with one journey and one
   * goal, and one ONBOARDING_COMPLETED in the audit.
   */
  @Transactional
  LearningJourneyResponse save(String subject, LearningJourneyRequest request) {
    Learner learner = learners.provisionForSubject(subject);

    if (registrations.findContact(learner.id()).isEmpty()) {
      throw RegistrationException.registrationRequired();
    }

    String state = registrations.findOnboardingState(learner.id())
        .orElse(OnboardingService.NOT_REGISTERED);
    if (!WRITABLE_STATES.contains(state)) {
      BusinessEventLogger.warn(LOGGER, "onboarding.journey.refused",
          "Learning journey submitted before the profile gate was cleared",
          Map.of("learnerId", learner.id(), "onboardingState", state, "outcome", "REJECTED"));
      throw RegistrationException.professionalProfileRequired(state);
    }

    // The learner names a domain; the server resolves it. An unknown or retired code is a rejection,
    // never a foreign key the client chose. Doc 03 section 8 also requires that no domain is ever
    // auto-selected -- there is no default here, so the domain is only ever the one they named.
    UUID primaryDomainId = learners.findActiveDomainId(request.primaryDomainCode())
        .orElseThrow(() -> new UnknownLearningDomainException(request.primaryDomainCode()));

    journeys.save(learner.id(), primaryDomainId, request);

    // The compatibility projection (Doc 03 section 8.1). Same transaction, same method the legacy
    // goal endpoint uses: if this throws, the transition below never commits.
    learners.upsertGoal(learner.id(), primaryDomainId, request.targetProficiency(),
        request.targetDate());

    boolean advanced = journeys.advanceToOnboarded(learner.id()) > 0;
    if (advanced) {
      registrations.audit(null, learner.id(), null, "ONBOARDING_COMPLETED", "SUCCESS", null);
      BusinessEventLogger.info(LOGGER, "onboarding.completed",
          "Learning journey recorded and projected; professional onboarding complete",
          Map.of("learnerId", learner.id(), "outcome", "SUCCESS"));
      meterRegistry.counter("ramals.registration.onboarding.transitions",
          "from", "JOURNEY_PENDING", "to", "ONBOARDED").increment();
    }

    return journeys.find(learner.id()).map(LearningJourneyResponse::from)
        .orElseThrow(() -> new IllegalStateException(
            "Learning journey was written but could not be read back."));
  }

  /**
   * A catalog entry as the onboarding UI needs it.
   *
   * <p>Re-declared here rather than returning the repository's own record, so no HTTP class depends
   * on a type nested inside a Repository -- which is the dependency ArchitectureGuardrailTests
   * forbids, and which returning the repository type would reintroduce through the signature alone.
   */
  record LearningDomainResponse(String code, String name) {
  }

  /** The screen-required projection of a stored journey (Doc 03 section 15). */
  record LearningJourneyResponse(UUID id, String goalType, String targetRole,
      String learningIntensity, int weeklyHours, String status, String primaryDomainCode,
      java.math.BigDecimal targetProficiency, java.time.LocalDate targetDate) {

    static LearningJourneyResponse from(LearningJourneyRepository.Journey journey) {
      return new LearningJourneyResponse(journey.id(), journey.goalType(), journey.targetRole(),
          journey.learningIntensity(), journey.weeklyHours(), journey.status(),
          journey.primaryDomainCode(), journey.targetProficiency(), journey.targetDate());
    }
  }
}
