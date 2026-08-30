package io.ramals.learningplatform.registration;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumes an abuse ceiling in its own transaction.
 *
 * <p>Consumption has to survive the rejection it causes. {@code MobileVerificationService#verify}
 * charges the per-subject verify budget and then rejects an expired, consumed, superseded, exhausted
 * or unknown challenge with an ordinary {@link RegistrationException} - which rolls its transaction
 * back, taking the increment with it. Only a wrong code was exempt, so an attacker probing challenge
 * ids paid nothing and the ceiling never advanced.
 *
 * <p>Widening {@code noRollbackFor} to the whole exception type would have fixed the counter and
 * committed whatever else the transaction had touched. A separate transaction is the narrower
 * instrument: the counter commits, and the verification transaction still rolls back cleanly.
 *
 * <p>{@code REQUIRES_NEW} is set on a {@link TransactionTemplate} rather than by annotation so the
 * boundary does not depend on a Spring proxy - the integration suites construct these beans directly,
 * and an annotation would be silently inert there, which is the one place this most needs proving.
 */
@Component
class AbuseCeiling {

  private final RegistrationRepository registrations;
  private final TransactionTemplate independentTransaction;

  AbuseCeiling(RegistrationRepository registrations, PlatformTransactionManager transactionManager) {
    this.registrations = registrations;
    this.independentTransaction = new TransactionTemplate(transactionManager);
    this.independentTransaction.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * Increments the dimension's counter and reports whether the caller is still inside the ceiling.
   *
   * <p>Committed independently of any surrounding transaction, so a caller that goes on to reject
   * the request has still paid for the attempt.
   */
  boolean consume(String dimension, int limit, int windowSeconds) {
    return Boolean.TRUE.equals(independentTransaction.execute(
        status -> registrations.withinCeiling(dimension, limit, windowSeconds)));
  }
}
