package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * The professional profile step and the one transition it authorises.
 *
 * <p>The theme is that the gate is ordered and server-owned: the payload carries no state, the
 * advance is guarded by the state it may leave, and a learner who has not proved mobile ownership
 * cannot buy their way past that by submitting the next step's form.
 */
class ProfessionalProfileServiceTests {

  private static final String SUBJECT = "subject-profile";
  private static final UUID LEARNER_ID = UUID.fromString("01900000-0000-7000-8000-0000000000d1");

  private static final ProfessionalProfileRequest VALID = new ProfessionalProfileRequest(
      "Staff Engineer", "FIVE_TO_TEN_YEARS", "Distributed systems", "ADVANCED");

  private LearnerRepository learners;
  private RegistrationRepository registrations;
  private ProfessionalProfileRepository profiles;
  private ProfessionalProfileService service;

  @BeforeEach
  void setUp() {
    learners = mock(LearnerRepository.class);
    registrations = mock(RegistrationRepository.class);
    profiles = mock(ProfessionalProfileRepository.class);
    when(learners.provisionForSubject(anyString()))
        .thenReturn(new Learner(LEARNER_ID, SUBJECT, "ACTIVE", Instant.now(), Instant.now()));
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.of(
        new RegistrationRepository.Contact(
            "a@example.com", "+919876543210", Instant.now(), Instant.now())));
    service = new ProfessionalProfileService(
        learners, registrations, profiles, new SimpleMeterRegistry());
  }

  private void storedProfileIs(ProfessionalProfileRequest request) {
    when(profiles.find(LEARNER_ID)).thenReturn(Optional.of(new ProfessionalProfileRepository.Profile(
        request.currentRole(), request.experienceBand(), request.primaryExpertise(),
        request.declaredSkillLevel(), 1L)));
  }

  @Test
  @DisplayName("a valid profile advances PROFILE_PENDING to JOURNEY_PENDING")
  void validProfileAdvancesToJourneyPending() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("PROFILE_PENDING"));
    when(profiles.advanceToJourneyPending(LEARNER_ID)).thenReturn(1);
    storedProfileIs(VALID);

    ProfessionalProfileService.ProfessionalProfileResponse response = service.save(SUBJECT, VALID);

    assertThat(response.currentRole()).isEqualTo("Staff Engineer");
    verify(profiles).save(LEARNER_ID, VALID);
    verify(profiles).advanceToJourneyPending(LEARNER_ID);
    verify(registrations).audit(null, LEARNER_ID, null, "PROFILE_COMPLETED", "SUCCESS", null);
  }

  @Test
  @DisplayName("a learner who has not verified their mobile cannot submit a profile")
  void mobilePendingLearnerCannotSubmitProfile() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("MOBILE_PENDING"));

    assertThatThrownBy(() -> service.save(SUBJECT, VALID))
        .isInstanceOf(RegistrationException.class)
        .hasMessageContaining("MOBILE_PENDING");

    // The refusal must be total: no profile row, and above all no transition.
    verify(profiles, never()).save(any(), any());
    verify(profiles, never()).advanceToJourneyPending(any());
  }

  @Test
  @DisplayName("an email-pending learner cannot skip two gates by submitting a profile")
  void emailPendingLearnerCannotSkipToJourney() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("EMAIL_PENDING"));

    assertThatThrownBy(() -> service.save(SUBJECT, VALID))
        .isInstanceOf(RegistrationException.class);

    verify(profiles, never()).advanceToJourneyPending(any());
  }

  @Test
  @DisplayName("resubmitting a profile is idempotent and advances nobody twice")
  void resubmissionIsIdempotent() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("JOURNEY_PENDING"));
    // Already past the gate, so the guarded UPDATE matches no rows -- the database, not a flag in
    // the service, is what makes the second call a no-op.
    when(profiles.advanceToJourneyPending(LEARNER_ID)).thenReturn(0);
    storedProfileIs(VALID);

    service.save(SUBJECT, VALID);
    service.save(SUBJECT, VALID);

    verify(profiles, times(2)).save(LEARNER_ID, VALID);
    // No second PROFILE_COMPLETED: the learner completed the step once.
    verify(registrations, never()).audit(any(), any(), any(), eq("PROFILE_COMPLETED"), any(), any());
  }

  @Test
  @DisplayName("an onboarded learner may correct their profile without regressing state")
  void onboardedLearnerMayEditProfile() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("ONBOARDED"));
    when(profiles.advanceToJourneyPending(LEARNER_ID)).thenReturn(0);
    storedProfileIs(VALID);

    service.save(SUBJECT, VALID);

    verify(profiles).save(LEARNER_ID, VALID);
    // The guard is what protects this: an ONBOARDED learner is not walked back to JOURNEY_PENDING.
    verify(registrations, never()).audit(any(), any(), any(), eq("PROFILE_COMPLETED"), any(), any());
  }

  @Test
  @DisplayName("a learner who never registered cannot create a profile")
  void unregisteredLearnerIsRefused() {
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.save(SUBJECT, VALID))
        .isInstanceOf(RegistrationException.class);

    verify(profiles, never()).save(any(), any());
  }

  @Test
  @DisplayName("the profile read is scoped to the authenticated learner")
  void readIsScopedToAuthenticatedLearner() {
    when(profiles.find(LEARNER_ID)).thenReturn(Optional.empty());

    assertThat(service.current(SUBJECT)).isEmpty();

    // The id is resolved from the subject, so there is no parameter through which one learner could
    // name another. This asserts the only id ever reaching the repository is the caller's own.
    verify(profiles).find(LEARNER_ID);
  }
}
