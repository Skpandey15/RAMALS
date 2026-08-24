package io.ramals.learningplatform.orchestration;

import io.ramals.learningplatform.diagnosticassessment.DiagnosticAssessmentService;
import io.ramals.learningplatform.execution.AiExecutionRecoveryPort;
import io.ramals.learningplatform.execution.AiExecutionRecoveryPort.RecordedExecution;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort.RecordedDecision;
import io.ramals.learningplatform.grounding.ProposalType;
import io.ramals.learningplatform.learner.LearnerRepository;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Runs the M2-T09 diagnostic path as one composition step.
 *
 * <p>The learner's subject is resolved here and passed down, because the diagnostic service accepts
 * only an authenticated subject and derives the learner itself. Handing it a learner id would open
 * exactly the cross-learner path that design closes -- so the workflow resolves the subject from its
 * own authoritative run row and lets the service re-derive everything else.
 */
@Component
public class DiagnosticAgentStep implements WorkflowAgentStep.Diagnostic {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticAgentStep.class);

  static final String ABANDONED = "AI_EXECUTION_ABANDONED";
  static final String UNRECOVERABLE = "DIAGNOSIS_RESULT_UNRECOVERABLE";

  private final DiagnosticAssessmentService diagnostics;
  private final LearnerRepository learners;
  private final ProposalGateDecisionPort decisions;
  private final AiExecutionRecoveryPort executions;

  public DiagnosticAgentStep(
      DiagnosticAssessmentService diagnostics,
      LearnerRepository learners,
      ProposalGateDecisionPort decisions,
      AiExecutionRecoveryPort executions) {
    this.diagnostics = diagnostics;
    this.learners = learners;
    this.decisions = decisions;
    this.executions = executions;
  }

  /**
   * Diagnoses, or recovers whatever a previous attempt already achieved.
   *
   * <p>Five durable states exist under this run's stable request identity, and every one of them has
   * a deterministic answer. None of them redispatch a request that was already commissioned: dispatch
   * is at-most-once by construction and recovery must not be the loophole that makes it twice.
   */
  @Override
  public Result diagnose(Run run) {
    String previousInteractionId = MDC.get("interactionId");
    String previousTraceId = MDC.get("traceId");
    MDC.put("interactionId", run.interactionId());
    if (run.traceId() == null || run.traceId().isBlank()) {
      MDC.remove("traceId");
    } else {
      MDC.put("traceId", run.traceId());
    }
    try {
      return diagnoseWithCorrelation(run);
    } finally {
      restore("interactionId", previousInteractionId);
      restore("traceId", previousTraceId);
    }
  }

  private Result diagnoseWithCorrelation(Run run) {
    String requestId = requestId(run);

    // State 5: the gate already ruled. The cheapest and most complete recovery there is.
    Optional<RecordedDecision> recorded = decisions.findDecision(requestId, ProposalType.DIAGNOSTIC);
    if (recorded.isPresent()) {
      RecordedDecision decision = recorded.orElseThrow();
      log(run, requestId, "adopted", decision.accepted() ? "ACCEPTED" : "REJECTED");
      return decision.accepted()
          ? Result.accepted("DIAGNOSIS_ACCEPTED", requestId)
          : Result.rejected("DIAGNOSIS_PROPOSAL_REJECTED", requestId);
    }

    // No decision. What the execution ledger says now decides whether dispatch is even permitted.
    RecordedExecution execution = executions.findExecutionState(requestId);
    return switch (execution.state()) {
      case ABSENT -> dispatch(run, requestId);
      case COMMISSIONED -> closeIndeterminate(run, requestId);
      case FAILED -> adoptFailure(run, requestId, execution.errorCode());
      case SUCCEEDED -> unrecoverableSuccess(run, requestId);
    };
  }

  private static void restore(String key, String previous) {
    if (previous == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, previous);
    }
  }

  /** State 1: nothing was ever commissioned, so this is an ordinary first attempt. */
  private Result dispatch(Run run, String requestId) {
    String subject = learners.findActiveSubjectById(run.learnerId()).orElse(null);
    if (subject == null) {
      // Not retryable in any useful sense, but reported as a failure so the bounded attempt policy
      // -- rather than this adapter -- decides when to give up.
      return Result.failed("DIAGNOSIS_LEARNER_INACTIVE", null);
    }
    DiagnosticAssessmentService.Outcome outcome =
        diagnostics.assess(subject, run.curriculumVersionId(), requestId);
    return outcome.accepted()
        ? Result.accepted("DIAGNOSIS_ACCEPTED", requestId)
        : Result.rejected("DIAGNOSIS_PROPOSAL_REJECTED", requestId);
  }

  /**
   * State 2: commissioned, with no terminal record. The genuinely indeterminate case.
   *
   * <p>The provider may have been called and may even have answered; the worker died before anything
   * was written. Nothing can establish which, so this must not guess and must not dispatch again.
   *
   * <p>Closing the commission is a <em>conditional</em> write: it inserts only when no terminal
   * record exists. Losing that race is not an error, it is news -- the original worker committed a
   * real outcome in the meantime. Its result is therefore honoured rather than discarded, because
   * reporting abandonment over a real verdict would be the worst possible answer here.
   */
  private Result closeIndeterminate(Run run, String requestId) {
    if (executions.closeAbandonedExecution(requestId, ABANDONED)) {
      log(run, requestId, "abandoned", ABANDONED);
      return Result.terminal("DIAGNOSIS_EXECUTION_ABANDONED", requestId);
    }
    return resolveAfterLostRace(run, requestId);
  }

  /**
   * Re-reads authoritative state after another writer won the close.
   *
   * <p>Decision first, then execution state: the gate decision is the more complete fact, and a run
   * that can adopt a verdict should never settle for the execution row that produced it.
   *
   * <p>An outcome that is still not terminal is left to the workflow's own bounded attempt policy
   * rather than to a spin loop here. The next advance re-reads a moment later, the attempt ceiling
   * bounds it, and no thread sits waiting on a database it has just been told is in flux.
   */
  private Result resolveAfterLostRace(Run run, String requestId) {
    Optional<RecordedDecision> decision = decisions.findDecision(requestId, ProposalType.DIAGNOSTIC);
    if (decision.isPresent()) {
      log(run, requestId, "adopted-after-race", decision.orElseThrow().accepted() ? "ACCEPTED" : "REJECTED");
      return decision.orElseThrow().accepted()
          ? Result.accepted("DIAGNOSIS_ACCEPTED", requestId)
          : Result.rejected("DIAGNOSIS_PROPOSAL_REJECTED", requestId);
    }

    RecordedExecution execution = executions.findExecutionState(requestId);
    return switch (execution.state()) {
      case FAILED -> adoptFailureAfterRace(run, requestId, execution.errorCode());
      case SUCCEEDED -> unrecoverableSuccess(run, requestId);
      // Still not terminal. Bounded by MAX_STEP_ATTEMPTS through the ordinary retry path, which is
      // where a wait belongs; ABSENT cannot follow COMMISSIONED, so it is treated the same way
      // rather than being given a meaning it does not have.
      case COMMISSIONED, ABSENT -> {
        log(run, requestId, "unresolved", execution.state().name());
        yield Result.failed("DIAGNOSIS_RECOVERY_UNRESOLVED", requestId);
      }
    };
  }

  /**
   * A terminal failure recorded by whoever won the race.
   *
   * <p>Distinguishes a real provider failure from another recovery worker's abandonment. They are
   * both FAILED rows and they mean different things, and flattening them would tell an operator the
   * provider failed when in fact nobody ever heard back from it.
   */
  private Result adoptFailureAfterRace(Run run, String requestId, String errorCode) {
    if (ABANDONED.equals(errorCode)) {
      log(run, requestId, "abandoned-by-peer", ABANDONED);
      return Result.terminal("DIAGNOSIS_EXECUTION_ABANDONED", requestId);
    }
    return adoptFailure(run, requestId, errorCode);
  }

  /**
   * State 3: the call was made and failed, and that is durable.
   *
   * <p>Retrying is not merely useless but actively misleading: commissioning would refuse, and the
   * run would spend its remaining attempts rediscovering a failure already on record. Adopt it.
   */
  private Result adoptFailure(Run run, String requestId, String errorCode) {
    log(run, requestId, "adopted-failure", errorCode);
    return Result.terminal("DIAGNOSIS_EXECUTION_FAILED", requestId);
  }

  /**
   * State 4: the provider succeeded, and the verdict was never recorded.
   *
   * <p>This one cannot be recovered with today's persistence. {@code core.ai_execution} keeps a
   * {@code proposal_digest} and not the proposal, deliberately -- the ledger holds no model content
   * -- so there is nothing to re-gate. Gating requires the proposal, and the proposal is gone.
   *
   * <p>Fails terminally with its own reason code so the state is countable rather than hidden inside
   * a generic failure. The evidence document records the fix that removes the state entirely:
   * committing the execution success and its gate decision atomically, so a success without a
   * decision cannot exist.
   */
  private Result unrecoverableSuccess(Run run, String requestId) {
    log(run, requestId, "unrecoverable", UNRECOVERABLE);
    return Result.terminal(UNRECOVERABLE, requestId);
  }

  private static void log(Run run, String requestId, String outcome, String detail) {
    LOGGER
        .atInfo()
        .addKeyValue("operation", "workflow.diagnose.recovery")
        .addKeyValue("runId", run.id())
        .addKeyValue("requestId", requestId)
        .addKeyValue("recovery", outcome)
        .addKeyValue("detail", detail)
        .log("diagnostic step resolved from durable state");
  }

  /**
   * Derived from the run, never generated fresh.
   *
   * <p>A retry of this step must present the same request identity, or the AI execution ledger
   * records a second execution for what is logically one attempt and the idempotency the dispatcher
   * and gate both rely on evaporates. It is also the key the recovery lookup above searches by, so a
   * fresh identity would make an already-durable verdict unfindable.
   */
  static String requestId(Run run) {
    return "wf-diag-" + run.id();
  }
}
