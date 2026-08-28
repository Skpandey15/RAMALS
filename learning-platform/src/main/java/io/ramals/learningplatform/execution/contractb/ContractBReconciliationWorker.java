package io.ramals.learningplatform.execution.contractb;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives non-terminal Contract B executions forward, including after the process that started them
 * has died.
 *
 * <p>Recovery is not a special mode here. The worker reads the durable rows, asks the provider about
 * each, and records what it learns — which is the same thing the original process would have done,
 * because nothing was ever held in memory that mattered. A restart is invisible to this loop except
 * that the leases of the dead process expire.
 *
 * <p><strong>Off unless explicitly enabled.</strong> A scheduler that started polling on its own the
 * moment this class shipped would begin calling a paid provider in every environment that happened
 * to have a credential. It stays disabled until Contract B passes crash/recovery qualification, and
 * the flag is the record of that.
 *
 * <p><strong>It never submits.</strong> The worker leases work items that already carry a provider
 * execution identity and asks about them. Deciding to submit is the one judgement a recovery worker
 * must not make: it cannot distinguish an execution that was never sent from one whose
 * acknowledgement was lost, and choosing wrong duplicates live work at the provider.
 */
@Component
@ConditionalOnProperty(prefix = "ramals.contract-b", name = "reconciliation.enabled",
    havingValue = "true")
public class ContractBReconciliationWorker {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ContractBReconciliationWorker.class);

  /** This process, for the lease. A fresh id per start, so a restart cannot inherit its own leases. */
  private final UUID owner = UUID.randomUUID();

  private final ProviderExecutionRepository executions;
  private final ContractBExecutionService lifecycle;
  private final ContractBProperties properties;

  public ContractBReconciliationWorker(ProviderExecutionRepository executions,
      ContractBExecutionService lifecycle, ContractBProperties properties) {
    this.executions = executions;
    this.lifecycle = lifecycle;
    this.properties = properties;
  }

  /**
   * One reconciliation pass.
   *
   * <p>Leases before working, so two instances do not drive the same execution at once, and records
   * an attempt afterwards so a repeatedly unreachable execution backs off rather than spinning. A
   * lease that expires because this process died is simply picked up by another — which is the
   * recovery path, and it needs no separate code.
   */
  @Scheduled(fixedDelayString = "${ramals.contract-b.reconciliation.interval-ms:30000}")
  public void poll() {
    List<String> due;
    try {
      due = executions.leaseDue(owner, properties.getReconciliation().getLeaseMs(),
          properties.getReconciliation().getBatchSize());
    } catch (RuntimeException unavailable) {
      // The scheduler must survive a database blip. Logged and retried on the next tick rather than
      // killing the only thing that can finish a recoverable execution.
      LOGGER.warn("contract B reconciliation could not lease work [error={}]",
          unavailable.getClass().getSimpleName());
      return;
    }

    for (String requestId : due) {
      try {
        DurableExecutionState state = lifecycle.reconcile(requestId);
        if (!state.terminal()) {
          executions.recordReconciliationAttempt(
              requestId, properties.getReconciliation().getBackoffMs());
        }
      } catch (RuntimeException failure) {
        // One execution's failure must not end the pass. The others in this batch are unrelated,
        // and a worker that stopped at the first problem would strand them.
        LOGGER.warn("contract B reconciliation failed for one execution [requestId={}, error={}]",
            requestId, failure.getClass().getSimpleName());
        executions.recordReconciliationAttempt(
            requestId, properties.getReconciliation().getBackoffMs());
      }
    }
  }
}
