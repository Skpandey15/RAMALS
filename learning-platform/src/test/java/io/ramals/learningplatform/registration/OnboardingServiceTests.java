package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.ramals.learningplatform.learner.Learner;
import io.ramals.learningplatform.learner.LearnerRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Onboarding state resolution and the completed-onboarding gate.
 *
 * <p>The theme is M1-ADR-012: an operationally {@code ACTIVE} learner is not an onboarded one, and
 * nothing a browser sends can make them one.
 */
class OnboardingServiceTests {

  private static final String SUBJECT = "subject-1";
  private static final UUID LEARNER_ID = UUID.fromString("01900000-0000-7000-8000-0000000000c3");

  private RegistrationRepository registrations;
  private LearnerRepository learners;
  private IdentityProviderPort identities;
  private OnboardingService service;

  @BeforeEach
  void setUp() {
    registrations = mock(RegistrationRepository.class);
    learners = mock(LearnerRepository.class);
    identities = mock(IdentityProviderPort.class);
    // ACTIVE, deliberately: every test below runs against a learner the operational model considers
    // fully usable, which is exactly the population an ACTIVE check would wrongly admit.
    when(learners.provisionForSubject(anyString()))
        .thenReturn(new Learner(LEARNER_ID, SUBJECT, "ACTIVE", Instant.now(), Instant.now()));
    service = new OnboardingService(learners, registrations, identities, new SimpleMeterRegistry());
  }

  @Test
  @DisplayName("a JIT-provisioned ACTIVE learner reads as not registered")
  void jitProvisionedActiveLearnerIsNotRegistered() {
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.empty());

    OnboardingService.OnboardingResponse response = service.current(SUBJECT, true);

    assertThat(response.onboardingState()).isEqualTo("NOT_REGISTERED");
    assertThat(response.nextStep()).isEqualTo("REGISTRATION");
    assertThat(response.emailVerified()).isFalse();
    // Even a token asserting a verified email does not manufacture a registration that never happened.
    verify(registrations, never()).markEmailVerified(any());
  }

  @Test
  @DisplayName("an ACTIVE learner who never onboarded is refused by the gate")
  void gateRefusesAnActiveButNotOnboardedLearner() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.requireOnboarded(SUBJECT))
        .isInstanceOf(RegistrationException.class)
        .extracting(failure -> ((RegistrationException) failure).code())
        .isEqualTo("ONBOARDING_INCOMPLETE");
  }

  @Test
  @DisplayName("the gate refuses every state short of ONBOARDED")
  void gateRefusesEveryIntermediateState() {
    for (String state : new String[] {"IDENTITY_CREATED", "EMAIL_PENDING", "EMAIL_VERIFIED",
        "MOBILE_PENDING", "MOBILE_VERIFIED", "PROFILE_PENDING", "JOURNEY_PENDING"}) {
      when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of(state));
      assertThatThrownBy(() -> service.requireOnboarded(SUBJECT))
          .as("state %s must not satisfy the gate", state)
          .isInstanceOf(RegistrationException.class);
    }
  }

  @Test
  @DisplayName("the gate admits a fully onboarded learner")
  void gateAdmitsAnOnboardedLearner() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("ONBOARDED"));
    service.requireOnboarded(SUBJECT);
  }

  @Test
  @DisplayName("a mobile-verified learner without a profile resumes at the profile step")
  void mobileVerifiedLearnerResumesAtProfile() {
    fullyVerifiedContact();
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("PROFILE_PENDING"));

    OnboardingService.OnboardingResponse response = service.current(SUBJECT, false);

    assertThat(response.nextStep()).isEqualTo("PROFESSIONAL_PROFILE");
    assertThat(response.onboardingState()).isEqualTo("PROFILE_PENDING");
  }

  @Test
  @DisplayName("a learner with a profile but no journey resumes at the journey step")
  void journeyPendingLearnerResumesAtJourney() {
    fullyVerifiedContact();
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("JOURNEY_PENDING"));

    OnboardingService.OnboardingResponse response = service.current(SUBJECT, false);

    // Before this existed, JOURNEY_PENDING fell into the profile branch and the learner was sent
    // back to a step they had already completed -- with no way to reach the one they had not.
    assertThat(response.nextStep()).isEqualTo("LEARNING_JOURNEY");
  }

  @Test
  @DisplayName("only ONBOARDED reports COMPLETE")
  void onlyOnboardedIsComplete() {
    fullyVerifiedContact();
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("ONBOARDED"));

    assertThat(service.current(SUBJECT, false).nextStep()).isEqualTo("COMPLETE");
  }

  @Test
  @DisplayName("an unrecognised state falls back to the profile step, never to COMPLETE")
  void unknownStateFailsTowardsAnEarlierStep() {
    fullyVerifiedContact();
    when(registrations.findOnboardingState(LEARNER_ID))
        .thenReturn(Optional.of("SOME_FUTURE_STATE"));

    // The direction of the fallback is the point. Sending a learner to a step they have already
    // completed is recoverable; reporting COMPLETE for a state this build cannot evaluate would
    // open the dashboard to a learner whose gates were never checked.
    assertThat(service.current(SUBJECT, false).nextStep()).isEqualTo("PROFESSIONAL_PROFILE");
  }

  private void fullyVerifiedContact() {
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.of(
        new RegistrationRepository.Contact(
            "a@example.com", "+919876543210", Instant.now(), Instant.now())));
  }

  @Test
  @DisplayName("a stored verification flag avoids any provider call")
  void storedFlagShortCircuitsTheProviderCall() {
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.of(
        new RegistrationRepository.Contact("a@example.com", "+919876543210", Instant.now(), null)));
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("MOBILE_PENDING"));

    OnboardingService.OnboardingResponse response = service.current(SUBJECT, false);

    assertThat(response.emailVerified()).isTrue();
    assertThat(response.nextStep()).isEqualTo("MOBILE_VERIFICATION");
    // Without the short-circuit, every poll by an unverified learner becomes a synchronous admin call.
    verify(identities, never()).emailVerified(anyString());
  }

  @Test
  @DisplayName("a signed token claim reconciles verification without an admin call")
  void trustedTokenClaimReconcilesVerification() {
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.of(
        new RegistrationRepository.Contact("a@example.com", "+919876543210", null, null)));
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("EMAIL_PENDING"));

    OnboardingService.OnboardingResponse response = service.current(SUBJECT, true);

    // The claim is Keycloak's assertion inside a JWT the resource server already validated, not a
    // browser-supplied boolean: §11 forbids the latter and this is the former.
    assertThat(response.emailVerified()).isTrue();
    verify(registrations).markEmailVerified(LEARNER_ID);
    verify(registrations).audit(any(), eq(LEARNER_ID), any(), eq("EMAIL_VERIFIED"), eq("SUCCESS"),
        any());
    verify(identities, never()).emailVerified(anyString());
  }

  @Test
  @DisplayName("a stale token falls back to server-to-server provider state")
  void fallsBackToProviderStateWhenTheTokenIsStale() {
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.of(
        new RegistrationRepository.Contact("a@example.com", "+919876543210", null, null)));
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("EMAIL_PENDING"));
    when(identities.emailVerified(SUBJECT)).thenReturn(true);

    OnboardingService.OnboardingResponse response = service.current(SUBJECT, false);

    assertThat(response.emailVerified()).isTrue();
    verify(registrations).markEmailVerified(LEARNER_ID);
  }

  @Test
  @DisplayName("an unverified learner stays at email verification")
  void unverifiedLearnerStaysAtEmailVerification() {
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.of(
        new RegistrationRepository.Contact("a@example.com", "+919876543210", null, null)));
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("EMAIL_PENDING"));
    when(identities.emailVerified(SUBJECT)).thenReturn(false);

    OnboardingService.OnboardingResponse response = service.current(SUBJECT, false);

    assertThat(response.emailVerified()).isFalse();
    assertThat(response.nextStep()).isEqualTo("EMAIL_VERIFICATION");
    verify(registrations, never()).markEmailVerified(any());
  }

  @Test
  @DisplayName("a verified mobile advances the next step to the professional profile")
  void verifiedMobileAdvancesToTheProfile() {
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.of(
        new RegistrationRepository.Contact("a@example.com", "+919876543210", Instant.now(),
            Instant.now())));
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("PROFILE_PENDING"));

    OnboardingService.OnboardingResponse response = service.current(SUBJECT, true);

    assertThat(response.mobileVerified()).isTrue();
    // PR-A stops here: it must not fabricate profile or journey completion.
    assertThat(response.nextStep()).isEqualTo("PROFESSIONAL_PROFILE");
    assertThat(response.onboardingState()).isNotEqualTo("ONBOARDED");
  }

  @Test
  @DisplayName("no method accepts a caller-supplied onboarding state")
  void noMethodAcceptsACallerSuppliedState() {
    // The browser cannot assert EMAIL_VERIFIED, MOBILE_VERIFIED or ONBOARDED because there is no
    // parameter through which to assert one. current() takes a subject and a validated JWT claim.
    assertThat(java.util.Arrays.stream(OnboardingService.class.getDeclaredMethods())
        .filter(method -> method.getName().equals("current") || method.getName().equals("requireOnboarded"))
        .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
        .map(Class::getSimpleName))
        .containsOnly("String", "boolean");
  }
}
