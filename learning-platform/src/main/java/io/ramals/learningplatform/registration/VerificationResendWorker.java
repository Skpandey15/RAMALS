package io.ramals.learningplatform.registration;

import io.ramals.learningplatform.observability.BusinessEventLogger;
import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.UuidV7;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers the verification mail that {@code RegistrationService#resendVerification} accepted.
 *
 * <p>The work is off the request path for a security reason rather than a performance one. Sending
 * inline meant only a request for a genuinely unverified address paid for the provider's
 * send-verify-email call, so response time distinguished the cases the response body is careful not
 * to — an enumeration oracle rebuilt by the clock. Here the send happens after the caller has
 * already been answered, so its duration is unobservable to them.
 *
 * <p>A DB table plus a scheduled worker rather than an executor or a detached thread: that is the
 * pattern the platform already uses for durable side effects ({@code AgentWorkDispatcher},
 * {@code ContractBReconciliationWorker}), and it is the only one that survives the process dying
 * between accepting a resend and delivering it. A learner who never receives the mail has no way to
 * tell whether to wait or retry, so losing the work silently is the failure that matters most.
 *
 * <p>Rows whose subject is null are accepted requests with nothing to send — an unknown or already
 * verified address. They are deleted without contacting anyone; they exist so the write on the
 * request path does not vary with the lookup's outcome.
 */
@Component
@ConditionalOnProperty(prefix = "ramals.registration", name = "resend.enabled",
    havingValue = "true", matchIfMissing = true)
class VerificationResendWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(VerificationResendWorker.class);

  private final RegistrationRepository registrations;
  private final IdentityProviderPort identities;
  private final RegistrationProperties properties;
  private final TransactionTemplate transactions;

  VerificationResendWorker(RegistrationRepository registrations, IdentityProviderPort identities,
      RegistrationProperties properties, PlatformTransactionManager transactionManager) {
    this.registrations = registrations;
    this.identities = identities;
    this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelayString = "${ramals.registration.resend.interval-ms:5000}")
  public void deliver() {
    // A scheduler thread carries no MDC, so each pass is correlated to keep its lines joinable.
    try (var ignored = CorrelationContext.withCorrelation(UuidV7.generate().toString(), null)) {
      try {
        transactions.executeWithoutResult(status -> deliverBatch());
      } catch (RuntimeException failure) {
        // Swallowed deliberately: the scheduler stops re-invoking a method that throws, and a
        // worker that stops is a queue that silently never drains.
        BusinessEventLogger.error(LOGGER, "registration.verification.resend.pass-failed",
            "Verification resend pass failed", failure, Map.of("outcome", "FAILURE"));
      }
    }
  }

  /**
   * Claims and delivers one batch.
   *
   * <p>The whole batch runs in one transaction, which is what holds the {@code FOR UPDATE SKIP
   * LOCKED} claim: a peer replica skips these rows for the duration rather than sending them again.
   * A provider failure is caught per row so that one unreachable subject cannot roll back the
   * bookkeeping for the rest of the batch.
   */
  private void deliverBatch() {
    RegistrationProperties.Resend resend = properties.getResend();
    Instant now = Instant.now();
    List<RegistrationRepository.VerificationResend> due =
        registrations.claimDueVerificationResends(now, resend.getBatchSize());

    for (RegistrationRepository.VerificationResend work : due) {
      if (work.subject() == null) {
        // Accepted, nothing to send. Not logged per row: at the ceiling these are the majority and
        // logging each one would turn a probe into a log-volume amplifier.
        registrations.deleteVerificationResend(work.id());
        continue;
      }
      try {
        identities.sendVerificationEmail(work.subject());
        registrations.deleteVerificationResend(work.id());
        BusinessEventLogger.info(LOGGER, "registration.verification.resent",
            "Verification mail re-sent for an unverified identity", Map.of("outcome", "SUCCESS"));
      } catch (RuntimeException failure) {
        recordFailure(work, resend, now, failure);
      }
    }
  }

  private void recordFailure(RegistrationRepository.VerificationResend work,
      RegistrationProperties.Resend resend, Instant now, RuntimeException failure) {
    String code = failure instanceof RegistrationException rejected ? rejected.code() : "UNEXPECTED_ERROR";
    int attempts = work.attemptCount() + 1;
    if (attempts >= resend.getMaxAttempts()) {
      registrations.abandonVerificationResend(work.id(), code);
      BusinessEventLogger.error(LOGGER, "registration.verification.resend.abandoned",
          "Verification resend abandoned after exhausting its attempts", failure,
          Map.of("attemptCount", attempts, "outcome", "FAILURE"));
      return;
    }
    // Exponential, capped. A provider outage must not turn a queue of resends into a retry storm
    // against the thing that is already failing.
    Duration backoff = Duration.ofMillis(Math.min(
        resend.getMaxBackoffMillis(),
        resend.getInitialBackoffMillis() * (1L << Math.min(attempts - 1, 16))));
    registrations.rescheduleVerificationResend(work.id(), now.plus(backoff), code);
    BusinessEventLogger.warn(LOGGER, "registration.verification.resend.retry",
        "Verification resend failed and will be retried",
        Map.of("attemptCount", attempts, "backoffMillis", backoff.toMillis(), "outcome", "FAILURE"));
  }
}
