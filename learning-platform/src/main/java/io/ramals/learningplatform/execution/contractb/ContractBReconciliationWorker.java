package io.ramals.learningplatform.execution.contractb;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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
    recoverOrphans();

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

    // One allowance for the whole pass, not one per execution (M2-ADR-020 §3.2). Enumeration costs
    // a provider call per candidate, so a pass leasing twenty orphans at fifty inspections each
    // would authorise a thousand calls -- the unbounded behaviour a single search's bound was meant
    // to prevent, reintroduced by the loop around it.
    InspectionBudget budget =
        InspectionBudget.of(properties.getReconciliation().getInspectionBudgetPerPass());

    for (String requestId : due) {
      try {
        DurableExecutionState state = lifecycle.reconcile(requestId, budget);
        if (!state.terminal()) {
          backOff(requestId);
        }
      } catch (DurableExecutionRateLimitedException limited) {
        // The limit is organization-wide, so the next execution in this pass would ask the same
        // exhausted quota the same question. Ending the pass is not giving up: it is the only
        // response that does not make recovery slower for every other execution too (§7).
        long delay = deferralFor(limited);
        LOGGER.warn("contract B reconciliation stopped: the provider is rate limiting "
            + "[requestId={}, retryInMs={}, remainingInPass={}]. The rest of this pass is deferred.",
            requestId, delay, due.size() - due.indexOf(requestId) - 1);
        executions.deferReconciliation(requestId, delay);
        return;
      } catch (RuntimeException failure) {
        // One execution's failure must not end the pass. The others in this batch are unrelated,
        // and a worker that stopped at the first problem would strand them.
        LOGGER.warn("contract B reconciliation failed for one execution [requestId={}, error={}]",
            requestId, failure.getClass().getSimpleName());
        backOff(requestId);
      }
    }
  }

  /**
   * Pushes the next attempt out exponentially, with jitter and a cap (M2-ADR-020 §7).
   *
   * <p>Exponential because a fixed interval spends the same provider quota on the hundredth failed
   * attempt as on the first, and jittered because a fleet that backs off in lockstep returns to the
   * provider in lockstep — which is a slower way to be rate limited, not a way to avoid it.
   */
  private void backOff(String requestId) {
    ContractBProperties.Reconciliation settings = properties.getReconciliation();
    long jitter = settings.getBackoffJitterMs() <= 0
        ? 0
        : ThreadLocalRandom.current().nextLong(settings.getBackoffJitterMs());
    executions.recordReconciliationAttempt(
        requestId, settings.getBackoffMs(), settings.getMaxBackoffMs(), jitter);
  }

  /**
   * How long to wait after a rate limit: what the provider asked for, clamped to the backoff bounds.
   *
   * <p>The provider's own figure is preferred because it knows when it will serve again. Clamped
   * because an unbounded provider-supplied delay should not be able to push a recovery most of the
   * way to its horizon in a single step, and a zero-second one should not turn into an immediate
   * retry of the request that was just refused.
   */
  private long deferralFor(DurableExecutionRateLimitedException limited) {
    ContractBProperties.Reconciliation settings = properties.getReconciliation();
    Long asked = limited.retryAfterMillis();
    long delay = asked == null ? settings.getBackoffMs() : asked;
    return Math.clamp(delay, settings.getBackoffMs(), settings.getMaxBackoffMs());
  }

  /**
   * Enqueues executions that are durably recoverable but have no work item.
   *
   * <p>Without this the queue would decide what is recoverable, and it does not know: a process that
   * dies between recording a provider identity and enqueueing its work item leaves an execution that
   * every other mechanism can drive and nothing ever will. Kill points 2, 3 and 4 all produce rows in
   * that position, and the crash qualification found them stranded.
   *
   * <p>Two populations, and they are separated because the age threshold applies to only one of
   * them:
   *
   * <ul>
   *   <li><strong>Acknowledged but unqueued</strong> — a provider identity exists, so this is
   *       ordinary work that simply lost its index entry. Enqueued immediately; there is nothing to
   *       wait for.
   *   <li><strong>Sent but never acknowledged</strong> — no identity, nothing to poll, and the
   *       expected outcome is {@code UNKNOWN_TERMINAL}. Enqueued only once it is too old to still be
   *       in flight, because a submission happening right now is indistinguishable from one whose
   *       acknowledgement was lost, and resolving it early would terminate an execution about to
   *       succeed.
   * </ul>
   *
   * <p>This never submits. It only makes an execution visible to reconciliation, which decides the
   * outcome. For the second population that decision used to be indeterminate by necessity; since
   * M2-ADR-020 it is whatever enumeration by {@code custom_id} establishes — a recovered identity,
   * a conclusive absence, a duplicate, or an honest "not yet".
   */
  private void recoverOrphans() {
    int batch = properties.getReconciliation().getBatchSize();
    try {
      for (ProviderExecution execution : executions.reconcilableWithoutWork(batch)) {
        LOGGER.info("contract B execution had no work item; re-queuing [requestId={}, state={}]",
            execution.requestId(), execution.state());
        executions.enqueueReconciliation(execution.requestId());
      }
      for (String requestId : executions.sentWithoutAcknowledgement(
          properties.getReconciliation().getUnacknowledgedGraceMs(), batch)) {
        LOGGER.warn("contract B execution was sent and never acknowledged [requestId={}]. "
            + "Queuing it for enumeration by custom_id: the provider may hold an execution RAMALS "
            + "cannot name, and only a search can establish whether it does.", requestId);
        executions.enqueueReconciliation(requestId);
      }
    } catch (RuntimeException unavailable) {
      // Best effort. The ordinary lease loop below is the primary path, and a failure to sweep for
      // orphans must not stop it.
      LOGGER.warn("contract B orphan recovery scan failed [error={}]",
          unavailable.getClass().getSimpleName());
    }
  }
}
