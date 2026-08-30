package io.ramals.learningplatform.registration;

import io.micrometer.core.instrument.MeterRegistry;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The authoritative answer to "where is this learner in professional onboarding".
 *
 * <p>Every state is derived from the database or from Keycloak, through a signed token claim or a
 * server-to-server call. Nothing a client sends can move a learner to EMAIL_VERIFIED,
 * MOBILE_VERIFIED or ONBOARDED, and no endpoint accepts a state as input.
 *
 * <p>Operational status and onboarding state are different questions (M1-ADR-012):
 * {@code core.learner.status = ACTIVE} is set by just-in-time provisioning for anybody Keycloak
 * authenticates, so it says nothing about whether onboarding was completed.
 */
@Service
class OnboardingService {

  private static final Logger LOGGER = LoggerFactory.getLogger(OnboardingService.class);

  static final String NOT_REGISTERED = "NOT_REGISTERED";
  static final String ONBOARDED = "ONBOARDED";

  private final LearnerRepository learners;
  private final RegistrationRepository registrations;
  private final IdentityProviderPort identities;
  private final MeterRegistry meterRegistry;

  OnboardingService(LearnerRepository learners, RegistrationRepository registrations,
      IdentityProviderPort identities, MeterRegistry meterRegistry) {
    this.learners = learners;
    this.registrations = registrations;
    this.identities = identities;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Resolves the caller's onboarding state, reconciling email verification if it has changed.
   *
   * <p>Three trusted sources in cost order: the stored flag, then the {@code email_verified} claim on
   * the presented token - signed by Keycloak and already validated by the resource server, so it is
   * provider state rather than the browser boolean §11 forbids - then a direct call, which covers a
   * learner holding a token minted before they verified. Without the short-circuit every poll by an
   * unverified learner would become a synchronous admin call.
   */
  @Transactional
  OnboardingResponse current(String subject, boolean trustedTokenEmailVerified) {
    Learner learner = learners.provisionForSubject(subject);
    Optional<RegistrationRepository.Contact> contact = registrations.findContact(learner.id());
    if (contact.isEmpty()) {
      // A learner who exists only through just-in-time provisioning. Not an error: ADR 0001 makes
      // that a legitimate way to exist, and this capability must describe such a learner rather than
      // fail for them.
      return new OnboardingResponse(NOT_REGISTERED, "REGISTRATION", false, false);
    }

    RegistrationRepository.Contact details = contact.get();
    boolean emailVerified = details.emailVerifiedAt() != null
        || trustedTokenEmailVerified
        || identities.emailVerified(subject);

    if (emailVerified && details.emailVerifiedAt() == null) {
      registrations.markEmailVerified(learner.id());
      registrations.audit(null, learner.id(), null, "EMAIL_VERIFIED", "SUCCESS", null);
      BusinessEventLogger.info(LOGGER, "onboarding.email.reconciled",
          "Email verification reconciled from trusted provider state",
          Map.of("learnerId", learner.id(), "outcome", "SUCCESS"));
      meterRegistry.counter("ramals.registration.email.reconciliations", "outcome", "verified")
          .increment();
    }

    String state = registrations.findOnboardingState(learner.id()).orElse(NOT_REGISTERED);
    boolean mobileVerified = details.mobileVerifiedAt() != null;
    String nextStep = nextStep(emailVerified, mobileVerified, state);
    meterRegistry.counter("ramals.registration.onboarding.state", "state", state).increment();
    return new OnboardingResponse(state, nextStep, emailVerified, mobileVerified);
  }

  private static String nextStep(boolean emailVerified, boolean mobileVerified, String state) {
    if (!emailVerified) {
      return "EMAIL_VERIFICATION";
    }
    if (!mobileVerified) {
      return "MOBILE_VERIFICATION";
    }
    return ONBOARDED.equals(state) ? "COMPLETE" : "PROFESSIONAL_PROFILE";
  }

  /**
   * Refuses a caller whose professional onboarding is not complete.
   *
   * <p>The seam PR-B builds on, and the control §2 asks for: any endpoint needing a fully onboarded
   * learner calls this instead of testing {@code core.learner.status}, which would admit every
   * learner who never registered. Applied narrowly and never blanket-applied - existing learning
   * endpoints keep their present authorization, or the legacy population would be locked out (§19).
   * PR-A introduces no endpoint that needs it, so it ships without a production caller; a control
   * introduced alongside its first consumer is one nobody reviews.
   */
  void requireOnboarded(String subject) {
    Learner learner = learners.provisionForSubject(subject);
    String state = registrations.findOnboardingState(learner.id()).orElse(NOT_REGISTERED);
    if (!ONBOARDED.equals(state)) {
      BusinessEventLogger.warn(LOGGER, "onboarding.gate.refused",
          "Refused an operation that requires completed professional onboarding",
          Map.of("learnerId", learner.id(), "onboardingState", state, "outcome", "REJECTED"));
      throw RegistrationException.onboardingIncomplete(state);
    }
  }

  record OnboardingResponse(String onboardingState, String nextStep, boolean emailVerified,
      boolean mobileVerified) {
  }
}
