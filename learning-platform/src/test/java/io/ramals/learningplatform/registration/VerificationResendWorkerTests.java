package io.ramals.learningplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Delivery of the verification mail the resend route accepts.
 *
 * <p>The cases that matter are the ones about what the worker must survive rather than what it does
 * when everything works: a row with nothing to send, a provider that is failing, and a pass that
 * throws. The last is the one that would be silent — the Spring scheduler stops re-invoking a method
 * that propagates an exception, so a worker that lets one escape becomes a queue that never drains
 * again and nothing says so.
 */
class VerificationResendWorkerTests {

  private static final UUID WORK_ID = UUID.fromString("01900000-0000-7000-8000-0000000000a1");

  private RegistrationRepository registrations;
  private IdentityProviderPort identities;
  private RegistrationProperties properties;
  private VerificationResendWorker worker;

  @BeforeEach
  void setUp() {
    registrations = mock(RegistrationRepository.class);
    identities = mock(IdentityProviderPort.class);
    properties = new RegistrationProperties();

    // Executes the callback inline so the test exercises the worker rather than Spring's proxying.
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(DefaultTransactionStatus.class));

    worker = new VerificationResendWorker(registrations, identities, properties, transactionManager);
  }

  private void due(RegistrationRepository.VerificationResend... work) {
    when(registrations.claimDueVerificationResends(any(), anyInt())).thenReturn(List.of(work));
  }

  @Test
  @DisplayName("a claimed subject is sent to and the row removed")
  void sendsAndClearsClaimedWork() {
    due(new RegistrationRepository.VerificationResend(WORK_ID, "subject-9", 0));

    worker.deliver();

    verify(identities).sendVerificationEmail("subject-9");
    verify(registrations).deleteVerificationResend(WORK_ID);
  }

  @Test
  @DisplayName("a row with nothing to send contacts nobody")
  void noOpRowIsDroppedWithoutContactingTheProvider() {
    // Written for an unknown or already-verified address purely so the request path's work does not
    // vary with the lookup's outcome. It must cost the provider nothing here.
    due(new RegistrationRepository.VerificationResend(WORK_ID, null, 0));

    worker.deliver();

    verify(identities, never()).sendVerificationEmail(anyString());
    verify(registrations).deleteVerificationResend(WORK_ID);
  }

  @Test
  @DisplayName("a failing provider reschedules with backoff instead of losing the work")
  void reschedulesOnProviderFailure() {
    due(new RegistrationRepository.VerificationResend(WORK_ID, "subject-9", 0));
    doThrow(new IllegalStateException("keycloak down"))
        .when(identities).sendVerificationEmail("subject-9");

    worker.deliver();

    // Not deleted: a learner waiting on this mail has no way to know whether to keep waiting, so
    // dropping it silently is the failure that matters most.
    verify(registrations, never()).deleteVerificationResend(WORK_ID);
    ArgumentCaptor<Instant> nextAttempt = ArgumentCaptor.forClass(Instant.class);
    verify(registrations).rescheduleVerificationResend(eq(WORK_ID), nextAttempt.capture(), anyString());
    assertThat(nextAttempt.getValue()).isAfter(Instant.now());
  }

  @Test
  @DisplayName("work is abandoned once it exhausts its attempts")
  void abandonsAfterMaxAttempts() {
    int last = properties.getResend().getMaxAttempts() - 1;
    due(new RegistrationRepository.VerificationResend(WORK_ID, "subject-9", last));
    doThrow(new IllegalStateException("still down"))
        .when(identities).sendVerificationEmail("subject-9");

    worker.deliver();

    // Terminal rather than deleted, so an operator can still see that a resend never landed.
    verify(registrations).abandonVerificationResend(eq(WORK_ID), anyString());
    verify(registrations, never()).rescheduleVerificationResend(any(), any(), anyString());
  }

  @Test
  @DisplayName("one unreachable subject does not stop the rest of the batch")
  void oneFailureDoesNotAbortTheBatch() {
    UUID second = UUID.fromString("01900000-0000-7000-8000-0000000000a2");
    due(new RegistrationRepository.VerificationResend(WORK_ID, "broken", 0),
        new RegistrationRepository.VerificationResend(second, "healthy", 0));
    doThrow(new IllegalStateException("nope")).when(identities).sendVerificationEmail("broken");

    worker.deliver();

    verify(identities).sendVerificationEmail("healthy");
    verify(registrations).deleteVerificationResend(second);
  }

  @Test
  @DisplayName("a failing pass is swallowed so the scheduler keeps running")
  void aFailingPassDoesNotKillTheWorker() {
    when(registrations.claimDueVerificationResends(any(), anyInt()))
        .thenThrow(new IllegalStateException("database unavailable"));

    // Must not propagate: Spring's scheduler stops re-invoking a method that throws, so an escaping
    // exception turns a transient database blip into a queue that silently never drains again.
    worker.deliver();
  }

  @Test
  @DisplayName("the batch size is the configured one")
  void claimsTheConfiguredBatchSize() {
    properties.getResend().setBatchSize(7);
    due();

    worker.deliver();

    verify(registrations).claimDueVerificationResends(any(), eq(7));
  }
}
