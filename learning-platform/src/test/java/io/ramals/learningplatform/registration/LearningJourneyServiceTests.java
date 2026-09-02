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
import io.ramals.learningplatform.learner.UnknownLearningDomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The learning journey step, its compatibility projection, and the transition to ONBOARDED.
 *
 * <p>The theme is Doc 03 section 8.4: a learner is not onboarded until the deterministic-core goal
 * projection exists. The projection is not a follow-up task -- if it cannot be written, the learner
 * does not advance.
 */
class LearningJourneyServiceTests {

  private static final String SUBJECT = "subject-journey";
  private static final UUID LEARNER_ID = UUID.fromString("01900000-0000-7000-8000-0000000000e1");
  private static final UUID DOMAIN_ID = UUID.fromString("01900000-0000-7000-8000-000000000001");

  private static final LearningJourneyRequest VALID = new LearningJourneyRequest(
      "ROLE_TRANSITION", "Principal Engineer", "STEADY", 8, "KAFKA",
      new BigDecimal("0.800"), LocalDate.of(2027, 1, 1));

  private LearnerRepository learners;
  private RegistrationRepository registrations;
  private LearningJourneyRepository journeys;
  private LearningJourneyService service;

  @BeforeEach
  void setUp() {
    learners = mock(LearnerRepository.class);
    registrations = mock(RegistrationRepository.class);
    journeys = mock(LearningJourneyRepository.class);
    when(learners.provisionForSubject(anyString()))
        .thenReturn(new Learner(LEARNER_ID, SUBJECT, "ACTIVE", Instant.now(), Instant.now()));
    when(learners.findActiveDomainId("KAFKA")).thenReturn(Optional.of(DOMAIN_ID));
    when(registrations.findContact(LEARNER_ID)).thenReturn(Optional.of(
        new RegistrationRepository.Contact(
            "a@example.com", "+919876543210", Instant.now(), Instant.now())));
    service = new LearningJourneyService(
        learners, registrations, journeys, new SimpleMeterRegistry());
  }

  private void storedJourney() {
    when(journeys.find(LEARNER_ID)).thenReturn(Optional.of(new LearningJourneyRepository.Journey(
        UUID.randomUUID(), "ROLE_TRANSITION", "Principal Engineer", "STEADY", 8, "ACTIVE",
        "KAFKA", DOMAIN_ID, new BigDecimal("0.800"), LocalDate.of(2027, 1, 1))));
  }

  @Test
  @DisplayName("a valid journey projects the goal and completes onboarding")
  void validJourneyProjectsGoalAndOnboards() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("JOURNEY_PENDING"));
    when(journeys.advanceToOnboarded(LEARNER_ID)).thenReturn(1);
    storedJourney();

    LearningJourneyService.LearningJourneyResponse response = service.save(SUBJECT, VALID);

    assertThat(response.primaryDomainCode()).isEqualTo("KAFKA");
    verify(journeys).save(LEARNER_ID, DOMAIN_ID, VALID);
    // Projected through the same method the legacy PUT /me/goal uses, not a parallel INSERT.
    verify(learners).upsertGoal(
        LEARNER_ID, DOMAIN_ID, new BigDecimal("0.800"), LocalDate.of(2027, 1, 1));
    verify(journeys).advanceToOnboarded(LEARNER_ID);
    verify(registrations).audit(null, LEARNER_ID, null, "ONBOARDING_COMPLETED", "SUCCESS", null);
  }

  @Test
  @DisplayName("a failed goal projection prevents the transition to ONBOARDED")
  void failedProjectionPreventsOnboarding() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("JOURNEY_PENDING"));
    when(learners.upsertGoal(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("Goal upsert did not persist a row."));

    assertThatThrownBy(() -> service.save(SUBJECT, VALID))
        .isInstanceOf(IllegalStateException.class);

    // Doc 03 section 8.4. The advance is never even attempted, and because all three writes share
    // one transaction the journey row rolls back with it -- a learner reported as onboarded whose
    // deterministic-core goal was never written is the exact inconsistency this forbids.
    verify(journeys, never()).advanceToOnboarded(any());
    verify(registrations, never()).audit(any(), any(), any(), eq("ONBOARDING_COMPLETED"), any(),
        any());
  }

  @Test
  @DisplayName("a profile-pending learner cannot jump to ONBOARDED with a journey")
  void profilePendingLearnerCannotJumpToOnboarded() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("PROFILE_PENDING"));

    assertThatThrownBy(() -> service.save(SUBJECT, VALID))
        .isInstanceOf(RegistrationException.class)
        .hasMessageContaining("PROFILE_PENDING");

    verify(journeys, never()).save(any(), any(), any());
    verify(learners, never()).upsertGoal(any(), any(), any(), any());
    verify(journeys, never()).advanceToOnboarded(any());
  }

  @Test
  @DisplayName("a mobile-pending learner cannot skip three gates with a journey")
  void mobilePendingLearnerCannotSkipEveryGate() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("MOBILE_PENDING"));

    assertThatThrownBy(() -> service.save(SUBJECT, VALID))
        .isInstanceOf(RegistrationException.class);

    verify(journeys, never()).advanceToOnboarded(any());
  }

  @Test
  @DisplayName("resubmitting a journey is idempotent and completes onboarding once")
  void resubmissionIsIdempotent() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("ONBOARDED"));
    // Already past the gate: the guarded UPDATE matches nothing the second time.
    when(journeys.advanceToOnboarded(LEARNER_ID)).thenReturn(0);
    storedJourney();

    service.save(SUBJECT, VALID);
    service.save(SUBJECT, VALID);

    // Both writes are upserts, so two submissions leave one journey and one goal.
    verify(journeys, times(2)).save(LEARNER_ID, DOMAIN_ID, VALID);
    verify(learners, times(2)).upsertGoal(any(), any(), any(), any());
    verify(registrations, never()).audit(any(), any(), any(), eq("ONBOARDING_COMPLETED"), any(),
        any());
  }

  @Test
  @DisplayName("an unknown domain code is rejected rather than stored")
  void unknownDomainIsRejected() {
    when(registrations.findOnboardingState(LEARNER_ID)).thenReturn(Optional.of("JOURNEY_PENDING"));
    when(learners.findActiveDomainId("NOT_A_DOMAIN")).thenReturn(Optional.empty());
    LearningJourneyRequest unknown = new LearningJourneyRequest(
        "EXPLORATION", "Engineer", "CASUAL", 2, "NOT_A_DOMAIN", new BigDecimal("0.500"), null);

    assertThatThrownBy(() -> service.save(SUBJECT, unknown))
        .isInstanceOf(UnknownLearningDomainException.class);

    // The learner names a code; the server resolves it. A client never supplies the foreign key.
    verify(journeys, never()).save(any(), any(), any());
    verify(journeys, never()).advanceToOnboarded(any());
  }

  @Test
  @DisplayName("the journey read is scoped to the authenticated learner")
  void readIsScopedToAuthenticatedLearner() {
    when(journeys.find(LEARNER_ID)).thenReturn(Optional.empty());

    assertThat(service.current(SUBJECT)).isEmpty();

    verify(journeys).find(LEARNER_ID);
  }
}
