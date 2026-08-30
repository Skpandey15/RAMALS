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
 * <p><strong>The server decides; the browser is told.</strong> Every state here is derived from
 * trusted sources — the database, and Keycloak either through a signed token claim or a
 * server-to-server call. Nothing a client sends can move a learner to {@code EMAIL_VERIFIED},
 * {@code MOBILE_VERIFIED} or {@code ONBOARDED}, and there is no endpoint that accepts a state as
 * input. The UI reads this and renders it; it does not participate in deciding it.
 *
 * <p><strong>Operational status and onboarding state are different questions</strong> (M1-ADR-012).
 * {@code core.learner.status = ACTIVE} means the account is usable, and just-in-time provisioning
 * sets it on first sign-in for anybody Keycloak authenticates — including every learner who predates
 * this capability. It says nothing about whether professional onboarding was completed, which is why
 * that lives in {@code identity.professional_onboarding} and why {@link #requireOnboarded} consults
 * the latter and never the former.
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
   * <p><strong>The three sources are consulted in cost order, and all three are trusted.</strong>
   * The stored flag first, because a learner who has already been reconciled needs no external call
   * at all. Then the {@code email_verified} claim on the presented access token: it is signed by
   * Keycloak and validated by the resource server before this code runs, so it is provider state, not
   * client state — the thing §11 forbids is a browser-supplied boolean, and a claim inside a verified
   * JWT is not one. Only if neither says verified do we ask Keycloak directly, which covers the
   * learner who verified in another tab and holds a token minted before they did.
   *
   * <p>The ordering matters for load as much as correctness: without the short-circuit, every poll of
   * this endpoint by an unverified learner would become a synchronous admin call.
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
   * <p><strong>This is the seam PR-B builds on, and the control §2 asks for.</strong> Any endpoint
   * that requires a fully onboarded professional learner calls this instead of testing
   * {@code core.learner.status}. The distinction is the entire point of M1-ADR-012: a just-in-time
   * learner is {@code ACTIVE} from their first sign-in, so an {@code ACTIVE} check would admit every
   * learner who never registered, and would do so silently.
   *
   * <p>It is applied narrowly and never blanket-applied. Existing deterministic learning endpoints
   * keep their present authorization, because retrofitting an onboarding requirement onto them would
   * lock out the legacy population this capability was explicitly required not to break (§19). PR-A
   * introduces no endpoint that needs completed onboarding — its own mobile endpoints require
   * registration and email verification, which they check directly — so this has no production caller
   * yet. It ships with PR-A because the invariant it protects is PR-A's, and a control introduced
   * alongside its first consumer is a control nobody reviews.
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
