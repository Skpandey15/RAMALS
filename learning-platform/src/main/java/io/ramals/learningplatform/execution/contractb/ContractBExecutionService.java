package io.ramals.learningplatform.execution.contractb;

import io.ramals.learningplatform.execution.crypto.ResultEncryptionKeyUnavailableException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Contract B lifecycle, and the only component allowed to move an execution through it.
 *
 * <pre>
 *   ADMITTED ─▶ SUBMITTED ─▶ RUNNING ⇄ RECONCILING ─▶ SUCCEEDED | FAILED | UNKNOWN_TERMINAL
 * </pre>
 *
 * <p>Every transition is a compare-and-set against the durable row, every transition is recorded in
 * the append-only ledger, and every step is safe to repeat. Repeatability is not politeness: after a
 * process death the replacement re-enters at whatever step the ledger last recorded, and a step that
 * could only be taken once would leave the execution stranded.
 *
 * <p><strong>What this class refuses to do.</strong> It never submits twice for one owned execution,
 * never resubmits to recover from its own uncertainty, and never converts an unknown outcome into a
 * known one. Those three refusals are the contract; the rest is bookkeeping around them.
 *
 * <p><strong>No provider call happens inside a transaction.</strong> The AI plane is called between
 * transactions, never during one, following the Contract A clients — a provider call holding a
 * database connection across its deadline is the failure M1-ADR-001 exists to prevent.
 */
@Service
public class ContractBExecutionService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ContractBExecutionService.class);

  private final ProviderExecutionRepository executions;
  private final ContractBTransitionLedger ledger;
  private final DurableExecutionPort provider;
  private final ContractBResultStore results;
  private final ContractBAdoption adoption;

  public ContractBExecutionService(
      ProviderExecutionRepository executions,
      ContractBTransitionLedger ledger,
      DurableExecutionPort provider,
      ContractBResultStore results,
      ContractBAdoption adoption) {
    this.executions = executions;
    this.ledger = ledger;
    this.provider = provider;
    this.results = results;
    this.adoption = adoption;
  }

  /**
   * Admits a durable execution. Safe to call twice for the same idempotency key.
   *
   * <p>The durable row exists before any provider is contacted, which is what makes the rest of the
   * lifecycle recoverable: a process that dies immediately after this leaves a row a later worker
   * can find and reason about, rather than nothing at all.
   */
  public boolean admit(String requestId, String idempotencyKey, String customId,
      String provider, String model, String modelRoute) {
    boolean admitted =
        executions.admit(requestId, idempotencyKey, customId, provider, model, modelRoute);
    if (admitted) {
      ledger.record(requestId, null, DurableExecutionState.ADMITTED, "ADMITTER", 0L, "ADMITTED");
    }
    return admitted;
  }

  /**
   * Submits exactly once for an owned execution.
   *
   * <p>The ordering is the whole design, and it is chosen against one specific window: the moment
   * between the provider accepting a request and RAMALS durably knowing its identity. Nothing can
   * remove that window, so the code is arranged to make it as short as possible and to fail closed
   * on either side of it.
   *
   * <ol>
   *   <li>Claim, advancing the fence and recording the attempt durably as {@code SUBMITTED} with no
   *       identity yet. A process that dies after this leaves "sent, unacknowledged" rather than
   *       something that looks freshly submittable.
   *   <li>Call the provider, exactly once, with every controllable retry disabled.
   *   <li>Record the identity <em>immediately</em>, fenced on the claim.
   * </ol>
   *
   * <p><strong>Only an explicitly classified refusal may become {@code FAILED}.</strong> The three
   * outcomes are not symmetric and the asymmetry is deliberate:
   *
   * <ul>
   *   <li>{@link DurableExecutionRefusedException} — the far side chose a status, so it decided
   *       against the request and created nothing. {@code FAILED} is true.
   *   <li>{@link DurableSubmissionAmbiguousException} — a diagnosed unknown. {@code UNKNOWN_TERMINAL}.
   *   <li>anything else — an undiagnosed failure, which can be thrown after the request reached the
   *       provider. It cannot prove nothing was created, so it is also {@code UNKNOWN_TERMINAL}.
   * </ul>
   *
   * <p>The last row is the one that is easy to get wrong, and getting it wrong fails open: a bug in
   * a mapping layer or an interceptor becomes a tidy {@code FAILED} that hides a live provider
   * execution. The rule is that a definite outcome requires definite evidence, and only a classified
   * exception carries any.
   *
   * <p>Neither unknown is a placeholder for a better answer. The provider may hold an execution
   * RAMALS cannot name, and neither resubmitting nor declaring failure would be true. M2-ADR-016
   * forbids inferring replay-safe admission, so there is no key to submit under that would make a
   * retry safe.
   *
   * @return the state the execution now holds
   */
  public DurableExecutionState submit(String requestId, DurableSubmissionCommand command) {
    Optional<Long> claimed = executions.claimForSubmission(requestId);
    if (claimed.isEmpty()) {
      // Not admitted, or another worker owns the submission. Either way this caller must not call
      // the provider: that is what "exactly one submission per owned execution" means in practice.
      ProviderExecution current = require(requestId);
      LOGGER.info("contract B submission not claimed [requestId={}, state={}]",
          requestId, current.state());
      return current.state();
    }
    long fence = claimed.get();

    DurableSubmissionAck ack;
    try {
      ack = provider.submit(command);
    } catch (DurableExecutionRefusedException refused) {
      // The ONLY failure that may become FAILED. The far side answered with a status it chose, so
      // it processed the request and decided against it: nothing was accepted, nothing is running,
      // and FAILED is a true statement rather than a guess.
      ledger.record(requestId, DurableExecutionState.SUBMITTED, DurableExecutionState.FAILED,
          "SUBMITTER", fence, "SUBMIT_REFUSED");
      executions.finish(requestId, DurableExecutionState.FAILED, null, null);
      LOGGER.warn("contract B submission refused by the provider [requestId={}, status={}]",
          requestId, refused.status().value());
      return DurableExecutionState.FAILED;
    } catch (DurableSubmissionAmbiguousException ambiguous) {
      // Fail closed, loudly, and terminally. The provider may or may not be running this work.
      return indeterminate(requestId, fence, "SUBMIT_AMBIGUOUS",
          "the submission outcome could not be established");
    } catch (RuntimeException unexpected) {
      // Anything else, and the point is that we do not know what it was.
      //
      // Catching this as a refusal was the original defect here, and it failed open in the one
      // place it must not: an exception nobody anticipated -- a bug in the client's own mapping, a
      // serializer, an interceptor -- can be thrown *after* the request reached the provider, which
      // means a provider execution may exist. Recording FAILED would say RAMALS knows none does.
      //
      // Only a classified exception carries a diagnosis. An unclassified one is exactly the case
      // where the code cannot prove nothing was created, so it fails closed and the operator sees
      // an execution that needs looking at rather than a tidy failure that hides one.
      LOGGER.error("contract B submission failed unclassified [requestId={}, error={}]. "
          + "Treated as INDETERMINATE: this exception cannot prove the provider created nothing.",
          requestId, unexpected.getClass().getName());
      return indeterminate(requestId, fence, "SUBMIT_UNCLASSIFIED",
          "an unclassified failure after the submission began");
    }

    if (!ack.usable()) {
      // A 2xx carrying no execution id leaves RAMALS unable to poll -- the same position as never
      // having heard back, and treated the same way rather than optimistically.
      return indeterminate(requestId, fence, "SUBMIT_ACK_UNUSABLE",
          "the provider acknowledged without an execution identity");
    }

    if (!executions.recordSubmission(requestId, fence, ack.providerExecutionId())) {
      // The fence moved under us: another worker owns this execution now. Do not write the identity
      // over theirs, and do not treat our own call as authoritative.
      return indeterminate(requestId, fence, "SUBMIT_FENCE_LOST",
          "a concurrent worker owns this execution");
    }

    ledger.record(requestId, DurableExecutionState.SUBMITTED, DurableExecutionState.SUBMITTED,
        "SUBMITTER", fence, "PROVIDER_ACCEPTED");
    executions.enqueueReconciliation(requestId);
    LOGGER.info("contract B execution submitted [requestId={}, providerExecutionId={}]",
        requestId, ack.providerExecutionId());
    return DurableExecutionState.SUBMITTED;
  }

  /**
   * Drives one non-terminal execution as far as the provider currently allows.
   *
   * <p>The entry point for recovery, and it makes no distinction between a first poll and a poll
   * after a process death — because there is none. Status is read from the provider by identity, so
   * a replacement worker asks exactly the question the dead one would have asked. Nothing is carried
   * forward in memory, which is why nothing is lost when the memory goes.
   *
   * @return the state the execution holds after this attempt
   */
  public DurableExecutionState reconcile(String requestId) {
    ProviderExecution execution = require(requestId);
    if (execution.state().terminal()) {
      return execution.state();
    }
    if (!execution.hasProviderIdentity()) {
      // "Sent, unacknowledged" -- the state the write-ahead claim exists to make visible. The
      // provider may hold a live execution RAMALS cannot name.
      //
      // A worker must never resolve this by submitting. Recovering it would need enumeration of the
      // provider's executions matched on custom_id, and no such lookup is implemented here, so the
      // honest outcome is INDETERMINATE rather than a retry that could duplicate live work.
      return indeterminate(requestId, execution.submitFence(), "NO_PROVIDER_IDENTITY",
          "the execution was sent but never acknowledged, so there is nothing to reconcile against");
    }

    if (execution.state() == DurableExecutionState.SUBMITTED
        || execution.state() == DurableExecutionState.RUNNING) {
      // Marks the execution as being worked on, so the ledger shows a reconciliation was attempted
      // even if this process dies before it finishes.
      if (executions.transition(requestId, execution.state(), DurableExecutionState.RECONCILING)) {
        ledger.record(requestId, execution.state(), DurableExecutionState.RECONCILING,
            "RECONCILER", execution.submitFence(), "RECONCILE_STARTED");
      }
    }

    DurableStatusSnapshot status;
    try {
      status = provider.status(execution.providerExecutionId());
    } catch (RuntimeException unreachable) {
      // Not terminal. The provider being unreachable says nothing about the execution, and the work
      // item stays queued for a later attempt.
      LOGGER.warn("contract B status unavailable [requestId={}, error={}]",
          requestId, unreachable.getClass().getSimpleName());
      return DurableExecutionState.RECONCILING;
    }

    return switch (normalized(status.state())) {
      case "SUCCEEDED", "ENDED", "COMPLETED" -> retrieveAndFinish(execution, status);
      case "FAILED", "ERRORED" -> finish(execution, DurableExecutionState.FAILED,
          "PROVIDER_FAILED", null, null);
      case "CANCELLED", "CANCELED" -> finish(execution, DurableExecutionState.CANCELLED,
          "PROVIDER_CANCELLED", null, null);
      case "EXPIRED" -> finish(execution, DurableExecutionState.UNKNOWN_TERMINAL,
          "PROVIDER_EXPIRED", null, null);
      default -> {
        // Still working. Stay non-terminal and come back; this is the ordinary case, not an error.
        executions.transition(requestId, DurableExecutionState.RECONCILING,
            DurableExecutionState.RUNNING);
        yield DurableExecutionState.RUNNING;
      }
    };
  }

  /**
   * Retrieves the terminal result, validates it, seals it, and stores it.
   *
   * <p>Order matters and is enforced by {@link ContractBResultStore}: the document is validated
   * against the committed schema <em>before</em> it is encrypted, so nothing unchecked is ever
   * sealed, and the raw provider text never reaches a column in any form.
   *
   * <p>A key that will not resolve leaves the execution non-terminal on purpose. M2-ADR-018 §10 is
   * explicit — refuse to store, and the execution stays recoverable while the provider still holds
   * the result. Marking it failed would discard a result that is still there.
   */
  private DurableExecutionState retrieveAndFinish(
      ProviderExecution execution, DurableStatusSnapshot status) {
    DurableResultRecord record;
    try {
      record = provider.result(execution.providerExecutionId(), execution.customId());
    } catch (RuntimeException unavailable) {
      LOGGER.warn("contract B result retrieval failed [requestId={}, error={}]",
          execution.requestId(), unavailable.getClass().getSimpleName());
      return DurableExecutionState.RECONCILING;
    }

    if (!record.succeeded() || record.text() == null || record.text().isBlank()) {
      return finish(execution, DurableExecutionState.FAILED, "PROVIDER_RECORD_" + record.outcome(),
          record.inputTokens(), record.outputTokens());
    }

    try {
      results.store(execution.requestId(), execution.providerExecutionId(), record.text());
    } catch (ResultEncryptionKeyUnavailableException unavailable) {
      // Deliberately not terminal. The result exists at the provider and stays retrievable; a key
      // problem is an outage to fix, not an outcome to record.
      LOGGER.error("contract B result refused: no usable encryption key [requestId={}]. "
          + "The execution stays recoverable while the provider retains the result.",
          execution.requestId());
      return DurableExecutionState.RECONCILING;
    } catch (ContractBResultRejectedException rejected) {
      // The provider returned something that is not a valid proposal. That is a definite failure
      // and terminal: retrieving it again would return the same bytes.
      LOGGER.warn("contract B result failed validation [requestId={}, reason={}]",
          execution.requestId(), rejected.reasonCode());
      return finish(execution, DurableExecutionState.FAILED, "RESULT_SCHEMA_INVALID",
          record.inputTokens(), record.outputTokens());
    }

    ledger.record(execution.requestId(), DurableExecutionState.RECONCILING, null,
        "RECONCILER", execution.submitFence(), "RESULT_STORED");
    return finish(execution, DurableExecutionState.SUCCEEDED,
        "PROVIDER_" + normalized(status.nativeStatus()), record.inputTokens(),
        record.outputTokens());
  }

  /**
   * Adopts a stored result: commits the caller's decision and destroys the result, atomically.
   *
   * <p>Delegates to {@link ContractBAdoption} rather than reimplementing the boundary, so there is
   * exactly one place in the platform where the adoption transaction is drawn.
   *
   * @return the adopted result document, or empty when there is nothing to adopt
   */
  public Optional<String> adopt(String requestId, Runnable commitDecision) {
    Optional<String> result = results.read(requestId);
    if (result.isEmpty()) {
      // Absent, not undecryptable -- the store keeps those apart, and only the first is safe to
      // treat as "nothing to do here".
      return Optional.empty();
    }
    adoption.adopt(requestId, () -> {
      commitDecision.run();
      return null;
    });
    ledger.record(requestId, null, null, "ADOPTER", 0L, "ADOPTED");
    return result;
  }

  private DurableExecutionState finish(ProviderExecution execution, DurableExecutionState terminal,
      String reason, Integer inputTokens, Integer outputTokens) {
    if (executions.finish(execution.requestId(), terminal, inputTokens, outputTokens)) {
      ledger.record(execution.requestId(), execution.state(), terminal, "RECONCILER",
          execution.submitFence(), reason);
    }
    executions.clearReconciliation(execution.requestId());
    return terminal;
  }

  /** Ends an execution at Contract B's INDETERMINATE, recording why. */
  private DurableExecutionState indeterminate(String requestId, long fence, String reason,
      String explanation) {
    if (executions.finish(requestId, DurableExecutionState.UNKNOWN_TERMINAL, null, null)) {
      ledger.record(requestId, null, DurableExecutionState.UNKNOWN_TERMINAL, "SUBMITTER", fence,
          reason);
    }
    executions.clearReconciliation(requestId);
    LOGGER.error("contract B execution is INDETERMINATE [requestId={}, reason={}]: {}. "
        + "No resubmission is safe: RAMALS cannot establish whether the provider is running this "
        + "work, and this provider offers no replay-safe admission.", requestId, reason,
        explanation);
    return DurableExecutionState.UNKNOWN_TERMINAL;
  }

  private ProviderExecution require(String requestId) {
    return executions.find(requestId).orElseThrow(() ->
        new IllegalStateException("no contract B execution [requestId=" + requestId + "]"));
  }

  private static String normalized(String state) {
    return state == null ? "" : state.trim().toUpperCase(java.util.Locale.ROOT);
  }
}
