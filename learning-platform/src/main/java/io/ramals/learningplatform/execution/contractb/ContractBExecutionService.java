package io.ramals.learningplatform.execution.contractb;

import io.ramals.learningplatform.execution.crypto.ResultEncryptionKeyUnavailableException;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.time.Instant;
import java.util.Set;
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
  private final long skewMillis;
  private final long horizonMillis;
  private final int maxInspectionsPerSearch;
  private final int defaultInspectionBudget;

  public ContractBExecutionService(
      ProviderExecutionRepository executions,
      ContractBTransitionLedger ledger,
      DurableExecutionPort provider,
      ContractBResultStore results,
      ContractBAdoption adoption,
      ContractBProperties properties) {
    this.executions = executions;
    this.ledger = ledger;
    this.provider = provider;
    this.results = results;
    this.adoption = adoption;
    this.skewMillis = properties.getRecovery().getSearchSkewMs();
    this.horizonMillis = properties.getRecovery().getSearchHorizonMs();
    this.maxInspectionsPerSearch = properties.getRecovery().getMaxInspectionsPerSearch();
    this.defaultInspectionBudget = properties.getReconciliation().getInspectionBudgetPerPass();
  }

  /**
   * Admits a durable execution. Safe to call twice for the same idempotency key.
   *
   * <p>The durable row exists before any provider is contacted, which is what makes the rest of the
   * lifecycle recoverable: a process that dies immediately after this leaves a row a later worker
   * can find and reason about, rather than nothing at all.
   */
  public boolean admit(String requestId, String idempotencyKey,
      String provider, String model, String modelRoute) {
    // Captured now, while a request is still on this thread. Reconciliation runs later on a
    // scheduler with nothing behind it, and this is the only moment the originating correlation
    // exists to be recorded (V040).
    boolean admitted = executions.admit(requestId, idempotencyKey, provider, model, modelRoute,
        CorrelationContext.currentInteractionId(), CorrelationContext.currentTraceId());
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
    return reconcile(requestId, InspectionBudget.of(defaultInspectionBudget));
  }

  /**
   * Reconciles one execution, spending from a budget shared with the rest of the pass.
   *
   * <p>The budget matters only to the enumeration path. Polling an execution that already has an
   * identity is a single call by name and costs nothing that needs rationing; searching for one that
   * does not is a call per candidate, and it is the operation that has to be paid for out of a
   * shared allowance (M2-ADR-020 §3.2).
   */
  public DurableExecutionState reconcile(String requestId, InspectionBudget budget) {
    ProviderExecution execution = require(requestId);
    if (execution.state().terminal()) {
      return execution.state();
    }
    if (!execution.hasProviderIdentity()) {
      return recoverLostAcknowledgement(execution, budget);
    }
    return pollAndAdvance(execution);
  }

  /**
   * Searches for the provider execution whose acknowledgement was lost, and acts on what it finds.
   *
   * <p>M2-ADR-020. Before this existed, "sent, unacknowledged" was terminal by necessity: RAMALS held
   * no name for what it had sent and no way to ask. Enumeration by {@code custom_id} gives it one.
   *
   * <p>Four outcomes, and only one of them adopts anything:
   *
   * <ul>
   *   <li><strong>ONE</strong> — adopt the identity, fenced, and resume ordinary reconciliation. The
   *       execution stops being an orphan.
   *   <li><strong>ZERO</strong> — conclusive, so nothing was ever created and nothing more can be
   *       found. Terminal.
   *   <li><strong>MULTIPLE</strong> — the duplicate the Definition of Done exists to surface. Every
   *       one is recorded, none is adopted, and an operator is required. Choosing would be the
   *       tempting error: adopting the first attributes a diagnosis to an arbitrary execution, and
   *       adopting the newest silently prefers a duplicate over the original.
   *   <li><strong>INCONCLUSIVE</strong> — the search did not finish. Not an answer: retry, until the
   *       horizon makes waiting pointless.
   * </ul>
   *
   * <p>Every discovered execution is recorded whatever the outcome, because cost evidence has to
   * account for executions RAMALS decided not to adopt just as much as for the one it did.
   */
  private DurableExecutionState recoverLostAcknowledgement(
      ProviderExecution execution, InspectionBudget budget) {
    String requestId = execution.requestId();
    Instant submittedAt = executions.submittedAt(requestId);
    if (submittedAt == null) {
      // No anchor, so no defensible window. Nothing to search against.
      return indeterminate(requestId, execution.submitFence(), "NO_PROVIDER_IDENTITY",
          "the execution carries no submission time to search against");
    }

    boolean pastHorizon = Instant.now().isAfter(submittedAt.plusMillis(horizonMillis));

    if (budget.exhausted()) {
      // This pass has spent its inspections on other orphans. Deliberately NOT terminal, even past
      // the horizon: the horizon ends a search that looked and could not see, and this one never
      // looked. Terminating on evidence we declined to gather would be the fail-open M2-ADR-020 §2
      // exists to prevent. The next pass gets a fresh budget, and the memo means it resumes rather
      // than restarts.
      LOGGER.info("contract B enumeration deferred; the pass has no inspection budget left "
          + "[requestId={}]", requestId);
      return DurableExecutionState.RECONCILING;
    }

    // Batches an earlier search already proved are not this request's. Coverage established once is
    // coverage: an ended batch's results are immutable, so re-reading them is spending the
    // provider's rate limit to learn something already known (M2-ADR-020 §3.1).
    Set<String> alreadyRuledOut = executions.excludedFromSearch(requestId);
    int allowance = Math.min(budget.remaining(), maxInspectionsPerSearch);

    DurableExecutionSearch search;
    try {
      search = provider.search(
          execution.customId(),
          submittedAt.minusMillis(skewMillis).toString(),
          submittedAt.plusMillis(skewMillis).toString(),
          allowance,
          alreadyRuledOut);
    } catch (DurableExecutionRateLimitedException limited) {
      // Being told to slow down says nothing about whether an orphan exists, so past the horizon it
      // is treated exactly like any other search that could not complete. Before the horizon it is
      // rethrown, because the pass must stop rather than ask the same exhausted quota again, and
      // only the worker can end a pass (M2-ADR-020 §7).
      if (pastHorizon) {
        return indeterminate(requestId, execution.submitFence(), "SEARCH_HORIZON_EXHAUSTED",
            "enumeration was rate limited and never completed before the search horizon");
      }
      throw limited;
    } catch (RuntimeException unreachable) {
      // The provider being unreachable says nothing about whether an orphan exists. Not terminal,
      // unless we have waited long enough that waiting more cannot help.
      LOGGER.warn("contract B enumeration failed [requestId={}, error={}]",
          requestId, unreachable.getClass().getSimpleName());
      return pastHorizon
          ? indeterminate(requestId, execution.submitFence(), "SEARCH_HORIZON_EXHAUSTED",
              "enumeration could not complete before the search horizon")
          : DurableExecutionState.RECONCILING;
    }

    budget.spend(search.batchesInspected());

    // Remember what this search ruled out, so the next one does not pay for it again. The port
    // reports only ended, fully-streamed, non-matching batches here; anything uninspectable is
    // excluded at the source, because memoising it would hand a later search coverage nobody
    // established and let it report ZERO -- which is terminal -- over a batch no one read.
    int remembered = executions.recordSearchExclusions(
        requestId, execution.customId(), search.newlyExcluded());
    if (remembered > 0) {
      LOGGER.debug("contract B enumeration ruled out {} further batches [requestId={}]",
          remembered, requestId);
    }

    for (DiscoveredExecution discovered : search.matches()) {
      if (executions.recordObservation(requestId, discovered, "ENUMERATION")) {
        ledger.record(requestId, null, null, "RECONCILER", execution.submitFence(),
            "OBSERVED_" + discovered.providerExecutionId());
      }
    }

    return switch (search.outcome()) {
      case ONE -> adoptRecovered(execution, search.matches().get(0));
      case MULTIPLE -> duplicate(execution, search);
      case ZERO -> indeterminate(requestId, execution.submitFence(), "SEARCH_FOUND_NOTHING",
          "enumeration inspected every candidate and none carried this request");
      case INCONCLUSIVE -> {
        LOGGER.warn("contract B enumeration inconclusive [requestId={}, uninspectable={}, "
            + "limitReached={}, inspected={}, alreadyRuledOut={}]. Not treated as absence.",
            requestId, search.uninspectable(), search.limitReached(),
            search.batchesInspected(), search.excluded());
        yield pastHorizon
            ? indeterminate(requestId, execution.submitFence(), "SEARCH_HORIZON_EXHAUSTED",
                "the search never became conclusive before the horizon")
            : DurableExecutionState.RECONCILING;
      }
    };
  }

  /** Adopts a recovered identity under the fence, then continues as an ordinary reconciliation. */
  private DurableExecutionState adoptRecovered(
      ProviderExecution execution, DiscoveredExecution recovered) {
    String requestId = execution.requestId();
    if (!executions.adoptRecoveredIdentity(
        requestId, execution.submitFence(), recovered.providerExecutionId())) {
      // The fence moved, or another worker adopted first. Either way this caller must not write its
      // identity over theirs, and must not treat its own search as authoritative.
      LOGGER.info("contract B recovered identity not adopted; another worker owns this execution "
          + "[requestId={}]", requestId);
      return executions.find(requestId).map(ProviderExecution::state)
          .orElse(DurableExecutionState.RECONCILING);
    }
    ledger.record(requestId, DurableExecutionState.SUBMITTED, DurableExecutionState.SUBMITTED,
        "RECONCILER", execution.submitFence(), "IDENTITY_RECOVERED");
    executions.enqueueReconciliation(requestId);
    LOGGER.warn("contract B recovered a lost acknowledgement [requestId={}, "
        + "providerExecutionId={}]. The execution is no longer an orphan.",
        requestId, recovered.providerExecutionId());

    // Resume the ordinary path immediately, with the identity now durable.
    return executions.find(requestId).map(this::pollAndAdvance)
        .orElse(DurableExecutionState.RECONCILING);
  }

  /**
   * Records a duplicate provider condition and refuses to resolve it.
   *
   * <p>Terminal and operator-visible. There is no rule that makes choosing among several executions
   * carrying one correlation key correct, because the information needed to choose does not exist on
   * this side — so the honest outcome is to record all of them and stop.
   */
  private DurableExecutionState duplicate(
      ProviderExecution execution, DurableExecutionSearch search) {
    String requestId = execution.requestId();
    ledger.record(requestId, null, null, "RECONCILER", execution.submitFence(),
        "DUPLICATE_PROVIDER_EXECUTION");
    LOGGER.error("contract B found {} provider executions carrying one correlation key "
        + "[requestId={}, customId={}]. None is adopted: there is no rule that makes choosing "
        + "correct. This requires an operator.",
        search.matches().size(), requestId, execution.customId());
    return indeterminate(requestId, execution.submitFence(), "DUPLICATE_PROVIDER_EXECUTION",
        "several provider executions carry this request's correlation key");
  }

  /** The ordinary poll-and-advance path, for an execution that has an identity. */
  private DurableExecutionState pollAndAdvance(ProviderExecution execution) {
    String requestId = execution.requestId();
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
      // RESULT_AVAILABLE is what the adapter actually emits for a batch whose processing_status is
      // "ended" -- the others are defensive aliases. Getting this wrong meant every real Contract B
      // execution polled a finished batch forever, and no test caught it because the fake asserted
      // the vocabulary I assumed rather than the one the adapter produces. Found by the W2
      // real-provider run; guarded now by ContractBProviderStateVocabularyTests.
      case "RESULT_AVAILABLE", "SUCCEEDED", "ENDED", "COMPLETED" ->
          retrieveAndFinish(execution, status);
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
    // No ledger write here, deliberately. core.adopt_ai_execution_result records the adoption in
    // the same statement as the delete, inside the transaction. A second entry from out here would
    // be both redundant and non-atomic: a process dying between the commit and this line would
    // leave adoption evidence that disagrees with itself about how many adoptions happened. Found
    // by the K10 crash qualification.
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
