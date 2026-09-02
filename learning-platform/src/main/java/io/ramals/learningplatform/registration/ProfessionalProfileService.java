package io.ramals.learningplatform.registration;

import io.micrometer.core.instrument.MeterRegistry;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns professional profile validation and persistence, and the PROFILE_PENDING to JOURNEY_PENDING
 * transition (M1-PROF-01 LLD section 8.3).
 *
 * <p>The transition is decided here, from stored state, and never from the request. Doc 03 section
 * 11 makes the backend authoritative for onboarding progress, so a client can submit a profile but
 * cannot declare itself past the gate -- there is no state field to send, and the UPDATE that moves
 * the learner is guarded by the state it is allowed to leave.
 *
 * <p>Nothing here consults an AI service. Onboarding eligibility is a deterministic-core decision;
 * the recommendation plane may later read a profile, but it does not get a vote on whether one is
 * complete.
 */
@Service
class ProfessionalProfileService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProfessionalProfileService.class);

  /**
   * The states in which a profile may be written.
   *
   * <p>PROFILE_PENDING is the step itself. JOURNEY_PENDING and ONBOARDED are included so a learner
   * can correct a typo later without being told to go back and verify their mobile again; because
   * the advance is separately guarded to PROFILE_PENDING, such an edit updates the profile and
   * moves nobody. Anything earlier is refused: the gate is ordered.
   */
  private static final Set<String> WRITABLE_STATES =
      Set.of("PROFILE_PENDING", "JOURNEY_PENDING", OnboardingService.ONBOARDED);

  private final LearnerRepository learners;
  private final RegistrationRepository registrations;
  private final ProfessionalProfileRepository profiles;
  private final MeterRegistry meterRegistry;

  ProfessionalProfileService(LearnerRepository learners, RegistrationRepository registrations,
      ProfessionalProfileRepository profiles, MeterRegistry meterRegistry) {
    this.learners = learners;
    this.registrations = registrations;
    this.profiles = profiles;
    this.meterRegistry = meterRegistry;
  }

  /** The caller's stored profile, or empty when they have not completed the step. */
  @Transactional(readOnly = true)
  Optional<ProfessionalProfileResponse> current(String subject) {
    Learner learner = learners.provisionForSubject(subject);
    return profiles.find(learner.id()).map(ProfessionalProfileResponse::from);
  }

  /**
   * Stores the profile and advances the learner when this completes the step.
   *
   * <p>Both writes share one transaction, so a learner cannot end with a saved profile and an
   * unmoved state, or a moved state and no profile. Repeated submission is idempotent by
   * construction rather than by a guard the caller must remember: the write upserts one row per
   * learner, and the transition matches no rows the second time, so the second call returns the same
   * response the first did and emits no second advance event.
   */
  @Transactional
  ProfessionalProfileResponse save(String subject, ProfessionalProfileRequest request) {
    Learner learner = learners.provisionForSubject(subject);

    // A learner with no contact record never registered -- they exist only through just-in-time
    // provisioning (ADR 0001). They have no onboarding to advance.
    if (registrations.findContact(learner.id()).isEmpty()) {
      throw RegistrationException.registrationRequired();
    }

    String state = registrations.findOnboardingState(learner.id())
        .orElse(OnboardingService.NOT_REGISTERED);
    if (!WRITABLE_STATES.contains(state)) {
      // Ordered gate: refuse rather than write a profile the learner's state does not admit.
      BusinessEventLogger.warn(LOGGER, "onboarding.profile.refused",
          "Professional profile submitted before the mobile gate was cleared",
          Map.of("learnerId", learner.id(), "onboardingState", state, "outcome", "REJECTED"));
      throw RegistrationException.mobileVerificationRequired(state);
    }

    profiles.save(learner.id(), request);
    boolean advanced = profiles.advanceToJourneyPending(learner.id()) > 0;

    if (advanced) {
      registrations.audit(null, learner.id(), null, "PROFILE_COMPLETED", "SUCCESS", null);
      BusinessEventLogger.info(LOGGER, "onboarding.profile.completed",
          "Professional profile completed; learner advanced to the journey step",
          Map.of("learnerId", learner.id(), "outcome", "SUCCESS"));
      meterRegistry.counter("ramals.registration.onboarding.transitions",
          "from", "PROFILE_PENDING", "to", "JOURNEY_PENDING").increment();
    }

    // Read back rather than echo the request: the response then reports what is stored, including
    // the trimming and null-normalisation the repository applied.
    return profiles.find(learner.id()).map(ProfessionalProfileResponse::from)
        .orElseThrow(() -> new IllegalStateException(
            "Professional profile was written but could not be read back."));
  }

  /** The screen-required projection of a stored profile (Doc 03 section 15). */
  record ProfessionalProfileResponse(String currentRole, String experienceBand,
      String primaryExpertise, String declaredSkillLevel) {

    static ProfessionalProfileResponse from(ProfessionalProfileRepository.Profile profile) {
      return new ProfessionalProfileResponse(profile.currentRole(), profile.experienceBand(),
          profile.primaryExpertise(), profile.declaredSkillLevel());
    }
  }
}
