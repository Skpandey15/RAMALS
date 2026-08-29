package io.ramals.learningplatform.execution.contractb;

import io.ramals.learningplatform.observability.CorrelationContext;
import io.ramals.learningplatform.observability.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the retention ceiling sweep on a schedule (M2-ADR-018 §9, M2-ADR-019).
 *
 * <p>The ceiling had a function, a constraint, a grant and an alert, and nothing that ran it. That
 * is the gap this closes: M2-ADR-018 §10 makes results outliving the ceiling a <em>governance
 * failure rather than a backlog</em>, and a rule enforced only by someone remembering to run
 * {@code psql} fails it as surely as one that errors — just more quietly, and later.
 *
 * <p><strong>Off by default, and separately flagged.</strong> Not folded into
 * {@code reconciliation.enabled}, because the two authorise different things: reconciliation drives
 * executions forward, this deletes result material permanently. An operator enabling recovery should
 * not silently acquire a scheduled delete along with it.
 *
 * <p>Off is also correct while no results exist. A scheduler sweeping an empty table forever is
 * noise, not safety — which is why this is a switch rather than an unconditional bean. It must be on
 * wherever Contract B is producing results.
 *
 * <p><strong>What it never removes.</strong> The sweep deletes rows from
 * {@code core.ai_execution_result} — the encrypted result payload, and only that. The execution row,
 * the transition ledger and the observation evidence all survive: they hold no model output, they
 * are what reconstructs an execution after its result is gone, and M2-ADR-019 keeps them precisely so
 * that a purged result is still explicable. Deleting the payload is the point; deleting the account
 * of it would destroy the audit trail the ceiling exists to protect.
 */
@Component
@ConditionalOnProperty(prefix = "ramals.contract-b", name = "purge.enabled", havingValue = "true")
public class ContractBPurgeWorker {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContractBPurgeWorker.class);

  private final ContractBResultPurge purge;
  private final ContractBProperties properties;

  public ContractBPurgeWorker(ContractBResultPurge purge, ContractBProperties properties) {
    this.purge = purge;
    this.properties = properties;
  }

  /**
   * One bounded sweep.
   *
   * <p>Safe across restarts because it holds nothing: the window is computed from {@code stored_at}
   * in the database on every run, so a sweep that never happened is indistinguishable from one that
   * did nothing, and a process that dies mid-sweep leaves a committed partial delete that the next
   * run simply continues. There is no cursor to lose and no state to reconcile.
   *
   * <p>Harmless when nothing qualifies: the function returns zero, which is logged at INFO as the
   * ordinary steady state rather than treated as a problem.
   *
   * <p>A failure is logged and swallowed <em>here</em> so the scheduler survives to try again — but
   * {@link ContractBResultPurge} has already logged it at ERROR with the governance wording, which is
   * the alerting signal. Letting it escape would kill the only thing that can enforce the ceiling,
   * which is the opposite of what a retention control should do when it fails.
   */
  @Scheduled(fixedDelayString = "${ramals.contract-b.purge.interval-ms:21600000}")
  public void sweep() {
    ContractBProperties.Purge settings = properties.getPurge();
    // A scheduler thread carries no MDC, and the sweep's own log lines are the retention evidence.
    // Correlated per run for the same reason reconciliation is: an uncorrelated governance event is
    // one nobody can join to anything else.
    try (CorrelationContext.Scope ignored =
        CorrelationContext.withCorrelation(UuidV7.generate().toString(), null)) {
      purge.sweep(settings.getRetentionDays(), settings.getBatchSize());
    } catch (RuntimeException failure) {
      LOGGER.warn("contract B ceiling sweep did not complete; it will be retried on the next "
          + "interval [error={}]", failure.getClass().getSimpleName());
    }
  }
}
