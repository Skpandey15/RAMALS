package io.ramals.learningplatform.execution.contractb;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Commits a Contract B adoption decision and destroys the result it was made from, atomically.
 *
 * <p>M2-ADR-018 §9: the result row is deleted <em>in the same transaction</em> that commits the gate
 * decision — <em>"not scheduled, not marked — deleted."</em> Once the decision exists the result is
 * redundant with an artifact the platform already owns, and the shortest exposure available is the
 * transaction boundary. This class is the only place that boundary is drawn, so it cannot be drawn
 * differently by two callers.
 *
 * <p>The transaction is opened through a {@link TransactionTemplate} rather than declared with
 * {@code @Transactional}, following {@code TransactionalDiagnosticOutcomeWriter}. A declared
 * annotation takes effect only through a Spring proxy, so the guarantee would quietly disappear for
 * any caller holding a directly constructed instance — including its own tests, which would then
 * pass while proving nothing. Opening it here makes atomicity a property of the class rather than of
 * how the class was obtained.
 *
 * <p><strong>Order within the transaction is deliberate.</strong> The decision is committed first
 * and the delete follows. Either order is atomic, but this one means a failure in the decision
 * leaves the result intact and the execution still adoptable — the direction that loses nothing.
 * The reverse would be equally correct on rollback and much easier to break later, because a delete
 * that has already "happened" invites someone to move the decision out of the transaction.
 *
 * <p>Deletion goes through {@code core.adopt_ai_execution_result}, which removes the row and writes
 * its transition-ledger entry as one statement (M2-ADR-019 §2). Calling {@code DELETE} here instead
 * would let the evidence and the deletion drift apart.
 */
@Component
public class ContractBAdoption {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContractBAdoption.class);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public ContractBAdoption(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Runs a deterministic adoption decision and deletes the result that produced it.
   *
   * <p>If {@code commitDecision} throws, nothing is committed and the result survives: the
   * execution remains adoptable by a later worker, which is the whole point of storing it.
   *
   * @param requestId the durable request identity
   * @param commitDecision the deterministic gate/adoption write. Runs inside the transaction and
   *     must not call a provider — this transaction is short and local by design
   * @return the caller's decision value, together with whether a result row was actually removed
   */
  public <T> Adopted<T> adopt(String requestId, Supplier<T> commitDecision) {
    if (requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException("a request identity is required to adopt");
    }
    Adopted<T> adopted = transactions.execute(status -> {
      T decision = commitDecision.get();
      Integer removed = jdbc.queryForObject(
          "SELECT core.adopt_ai_execution_result(?)", Integer.class, requestId);
      return new Adopted<>(decision, removed == null ? 0 : removed);
    });

    // Zero is not an error. A repeated adoption, or one whose result was already swept, removes
    // nothing and reports so honestly (M2-ADR-019 §2). It is still worth a line, because an
    // adoption that found no result is not the ordinary case.
    if (adopted != null && adopted.resultsRemoved() == 0) {
      LOGGER.info("contract B adoption committed with no result row to remove [requestId={}]",
          requestId);
    } else {
      LOGGER.info("contract B adoption committed and result purged [requestId={}]", requestId);
    }
    return adopted;
  }

  /**
   * The decision, and what the adoption destroyed.
   *
   * @param decision whatever the caller's gate write returned
   * @param resultsRemoved 1 when a result row was deleted, 0 when there was none
   */
  public record Adopted<T>(T decision, int resultsRemoved) {}
}
