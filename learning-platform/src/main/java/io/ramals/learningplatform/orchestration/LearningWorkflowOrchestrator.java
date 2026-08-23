package io.ramals.learningplatform.orchestration;

import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate;
import io.ramals.learningplatform.evidence.Evidence;
import io.ramals.learningplatform.evidence.EvidenceService;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasteryService;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Status;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Step;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepClaim;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepRun;
import io.ramals.learningplatform.orchestration.LearningWorkflow.StepStatus;
import io.ramals.learningplatform.orchestration.LearningWorkflowPolicy.Eligibility;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic composition of evaluation, mastery, diagnosis and adaptation (M2-T14).
 *
 * <p>Deliberately not {@code @Transactional} at this level. Each transition below is its own short,
 * atomic write, and the agent steps in between are remote calls that can take seconds. Wrapping the
 * whole advance in one transaction would hold a database connection across a model call, and -- far
 * worse -- would roll back the attempt counter along with the failure that incremented it, turning
 * the bounded retry into an unbounded one. The dispatcher makes the same choice for the same
 * reason: the bookkeeping commits before the remote work begins.
 *
 * <p>Concurrency is handled by claiming, not by hoping. The advancer runs on every instance, so two
 * workers routinely see the same RUNNING run in the same poll. A step is executed only by the worker
 * whose {@link StepClaim} won the atomic claim, and its result is accepted only while that claim is
 * still the current one -- which is how a cancellation or a timeout beats a worker that is still
 * waiting on a model.
 *
 * <p>Each call to {@link #advance} performs at most one step. A workflow therefore makes progress
 * only as fast as it is driven, and can never recurse into itself.
 */
public class LearningWorkflowOrchestrator {

  private static final Logger LOGGER = LoggerFactory.getLogger(LearningWorkflowOrchestrator.class);

  static final String REASON_COMPLETED = "WORKFLOW_COMPLETED";
  static final String REASON_ATTEMPTS_EXHAUSTED = "STEP_ATTEMPTS_EXHAUSTED";
  static final String REASON_DEADLINE = "RUN_DEADLINE_EXCEEDED";
  static final String REASON_STEP_FAILED = "STEP_EXECUTION_FAILED";
  static final String REASON_CANCELLED = "CANCELLED_BY_OPERATOR";
  static final String REASON_SNAPSHOT_LINEAGE = "MASTERY_SNAPSHOT_LINEAGE_BROKEN";

  private final LearningWorkflowRepository runs;
  private final EvidenceService evidence;
  private final MasteryService mastery;
  private final WorkflowAgentStep.Diagnostic diagnostic;
  private final WorkflowAgentStep.Adaptation adaptation;
  private final Clock clock;

  public LearningWorkflowOrchestrator(
      LearningWorkflowRepository runs,
      EvidenceService evidence,
      MasteryService mastery,
      WorkflowAgentStep.Diagnostic diagnostic,
      WorkflowAgentStep.Adaptation adaptation,
      Clock clock) {
    this.runs = runs;
    this.evidence = evidence;
    this.mastery = mastery;
    this.diagnostic = diagnostic;
    this.adaptation = adaptation;
    this.clock = clock;
  }

  /** The Spring-owned facts that start a composition. None of them come from a proposal. */
  public record EvaluationTrigger(
      String evaluationRequestId,
      EvaluationProposalGate.Outcome outcome,
      BigDecimal normalizedScore,
      UUID learnerId,
      UUID skillId,
      UUID curriculumVersionId,
      UUID attemptId,
      UUID assessmentVersionId,
      String interactionId,
      String traceId) {}

  /**
   * Starts, or re-returns, the one workflow for a gated evaluation.
   *
   * <p>Idempotent by construction (G05): the trigger key is the evaluation's request identity, and
   * the run table refuses a second row for it. A redelivered trigger therefore returns the existing
   * run rather than starting a parallel chain of agent calls.
   *
   * <p>An ineligible evaluation still gets a run row, immediately STOPPED with its reason. Recording
   * the refusal costs one row and answers the question an operator actually asks -- "why did nothing
   * happen for this learner?" -- which a silent no-op cannot.
   */
  public Run trigger(EvaluationTrigger trigger) {
    requireTrigger(trigger);
    Eligibility eligibility =
        LearningWorkflowPolicy.evaluationEligible(trigger.outcome(), trigger.normalizedScore());
    BigDecimal score = eligibility.eligible() ? trigger.normalizedScore() : BigDecimal.ZERO;

    Run run =
        runs.startOrGet(
            triggerKey(trigger.evaluationRequestId()),
            trigger.learnerId(),
            trigger.skillId(),
            trigger.curriculumVersionId(),
            trigger.attemptId(),
            trigger.assessmentVersionId(),
            score,
            trigger.evaluationRequestId(),
            trigger.interactionId(),
            trigger.traceId(),
            clock.instant().plus(LearningWorkflowPolicy.RUN_DEADLINE));

    if (run.status().terminal()) {
      return run;
    }
    if (!eligibility.eligible()) {
      stop(run, eligibility.reasonCode());
      return reload(run.id());
    }
    log("workflow.triggered", run, null, null);
    return run;
  }

  /**
   * Executes at most one step of a running workflow and returns its state afterwards.
   *
   * <p>Safe to call concurrently from several instances: the step is claimed atomically, and a
   * caller that loses the claim does nothing at all rather than duplicating the work.
   */
  public Run advance(UUID runId) {
    Run run = runs.findById(runId).orElseThrow(() -> unknownRun(runId));
    if (run.status().terminal()) {
      return run;
    }
    if (!clock.instant().isBefore(run.deadlineAt())) {
      // The absolute deadline outranks every per-step allowance. A run that is still going after
      // its budget cannot produce a useful adaptation for the answer that started it (G07).
      expire(run, Status.TIMED_OUT, REASON_DEADLINE);
      return reload(runId);
    }

    Step step = run.currentStep();
    if (step == null) {
      stop(run, "WORKFLOW_STEP_ABSENT");
      return reload(runId);
    }

    Optional<StepClaim> claimed =
        runs.claimStep(runId, step, LearningWorkflowPolicy.MAX_STEP_ATTEMPTS);
    if (claimed.isEmpty()) {
      // Either another worker owns this step, or the run moved on, or the attempts are spent.
      // Only the last of those is this caller's business to conclude.
      if (attemptsExhausted(runId, step)) {
        failExhausted(run, step);
      }
      return reload(runId);
    }

    StepClaim claim = claimed.orElseThrow();
    try {
      apply(run, claim, execute(run, step));
    } catch (RuntimeException failure) {
      LOGGER
          .atWarn()
          .addKeyValue("operation", "workflow.step")
          .addKeyValue("outcome", "FAILED")
          .addKeyValue("runId", runId)
          .addKeyValue("step", step.name())
          .addKeyValue("attempt", claim.attemptCount())
          .addKeyValue("errorType", failure.getClass().getSimpleName())
          .log("workflow step failed", failure);
      releaseAfterFailure(run, claim);
    }
    return reload(runId);
  }

  /** Operator cancellation (G07). A cancelled run keeps whatever authoritative state it wrote. */
  public boolean cancel(UUID runId, String reasonCode) {
    Run run = runs.findById(runId).orElseThrow(() -> unknownRun(runId));
    if (run.status().terminal()) {
      return false;
    }
    return expire(run, Status.CANCELLED, reasonCode == null ? REASON_CANCELLED : reasonCode);
  }

  /** Moves every overrun run to TIMED_OUT. Driven by the scheduled advancer. */
  public int sweepTimeouts(int limit) {
    int expired = 0;
    for (Run run : runs.running(limit)) {
      if (!clock.instant().isBefore(run.deadlineAt())
          && expire(run, Status.TIMED_OUT, REASON_DEADLINE)) {
        expired++;
      }
    }
    return expired;
  }

  /** What one step decided; the caller turns it into persisted transitions. */
  private record StepOutcome(
      StepStatus status,
      String reasonCode,
      String requestId,
      UUID resultRef,
      Eligibility continuation) {

    static StepOutcome completed(UUID resultRef) {
      return new StepOutcome(StepStatus.COMPLETED, null, null, resultRef, Eligibility.ELIGIBLE);
    }

    static StepOutcome completed(UUID resultRef, Eligibility continuation) {
      return new StepOutcome(StepStatus.COMPLETED, null, null, resultRef, continuation);
    }

    static StepOutcome agent(WorkflowAgentStep.Result result, Eligibility continuation) {
      return new StepOutcome(
          StepStatus.COMPLETED, result.reasonCode(), result.requestId(), null, continuation);
    }
  }

  private StepOutcome execute(Run run, Step step) {
    return switch (step) {
      case RECORD_EVALUATION_EVIDENCE -> recordEvidence(run);
      case RECOMPUTE_MASTERY -> recomputeMastery(run);
      case DIAGNOSE -> runDiagnosis(run);
      case ADAPT -> runAdaptation(run);
    };
  }

  private StepOutcome recordEvidence(Run run) {
    Evidence recorded =
        evidence.recordEvaluationEvidence(
            run.learnerId(),
            run.skillId(),
            run.attemptId(),
            run.assessmentVersionId(),
            run.evaluationRequestId(),
            EvaluationProposalGate.POLICY_VERSION,
            run.normalizedScore(),
            run.interactionId());
    return StepOutcome.completed(recorded.id());
  }

  private StepOutcome recomputeMastery(Run run) {
    MasterySnapshot snapshot =
        mastery.recompute(
            run.learnerId(), run.skillId(), run.curriculumVersionId(), run.interactionId());
    // Diagnosis eligibility is decided from the recomputed snapshot, not from the proposal that
    // preceded it. A learner who is now MASTERED has nothing to diagnose (G02).
    return StepOutcome.completed(
        snapshot.id(), LearningWorkflowPolicy.diagnosisEligible(snapshot.status()));
  }

  private StepOutcome runDiagnosis(Run run) {
    WorkflowAgentStep.Result result = diagnostic.diagnose(run);
    if (!result.succeeded()) {
      // No verdict was obtained, so this is a transport-shaped failure and worth another attempt.
      throw new WorkflowStepFailedException(result.reasonCode());
    }
    return StepOutcome.agent(result, LearningWorkflowPolicy.adaptationEligible(result.accepted()));
  }

  private StepOutcome runAdaptation(Run run) {
    // Adaptation consumes the snapshot this workflow produced, identified by the recompute step's
    // recorded result. Reading "the latest snapshot" instead would let an unrelated learner event
    // slip a different snapshot in between the two steps, so the audit trail would name one
    // snapshot while the recommendation was computed from another.
    UUID snapshotId =
        runs.step(run.id(), Step.RECOMPUTE_MASTERY)
            .filter(recompute -> recompute.status() == StepStatus.COMPLETED)
            .map(StepRun::resultRef)
            .orElse(null);
    if (snapshotId == null) {
      throw new WorkflowStepFailedException(REASON_SNAPSHOT_LINEAGE);
    }
    WorkflowAgentStep.Result result = adaptation.adapt(run, snapshotId);
    if (!result.succeeded()) {
      throw new WorkflowStepFailedException(result.reasonCode());
    }
    return StepOutcome.agent(result, Eligibility.ELIGIBLE);
  }

  /**
   * Persists a step outcome, but only while this worker still holds the claim.
   *
   * <p>A refused completion means the run reached a terminal state while the remote call was in
   * flight. That state stands: nothing here may advance, complete, or reopen a workflow that has
   * already been cancelled or timed out.
   */
  private void apply(Run run, StepClaim claim, StepOutcome outcome) {
    boolean owned =
        runs.finishClaimedStep(
            claim, outcome.status(), outcome.reasonCode(), outcome.requestId(), outcome.resultRef());
    if (!owned) {
      log("workflow.step.superseded", run, claim.step(), null);
      return;
    }

    if (!outcome.continuation().eligible()) {
      // A deterministic stop, not a failure: the remaining steps are marked SKIPPED so the audit
      // shows they were considered and declined rather than silently never attempted (G03).
      skipRemaining(run, claim.step(), outcome.continuation().reasonCode());
      runs.finishRun(run.id(), Status.STOPPED, outcome.continuation().reasonCode());
      log("workflow.stopped", run, claim.step(), outcome.continuation().reasonCode());
      return;
    }

    Optional<Step> next = LearningWorkflowPolicy.next(claim.step());
    if (next.isEmpty()) {
      runs.finishRun(run.id(), Status.COMPLETED, REASON_COMPLETED);
      log("workflow.completed", run, claim.step(), REASON_COMPLETED);
      return;
    }
    runs.advanceTo(run.id(), next.get());
    log("workflow.advanced", run, next.get(), null);
  }

  /**
   * Releases a failed attempt, or ends the run when its attempts are spent.
   *
   * <p>Guarded on the claim for the same reason completion is: a worker whose run was cancelled
   * mid-call must not reopen the step for another attempt.
   */
  private void releaseAfterFailure(Run run, StepClaim claim) {
    if (LearningWorkflowPolicy.mayRetry(claim.attemptCount())) {
      if (!runs.retryClaimedStep(claim, REASON_STEP_FAILED)) {
        log("workflow.step.superseded", run, claim.step(), null);
      }
      return;
    }
    if (runs.finishClaimedStep(
        claim, StepStatus.FAILED, REASON_ATTEMPTS_EXHAUSTED, null, null)) {
      runs.finishRun(run.id(), Status.FAILED, REASON_ATTEMPTS_EXHAUSTED);
      log("workflow.failed", run, claim.step(), REASON_ATTEMPTS_EXHAUSTED);
    }
  }

  /** Whether a step is PENDING with no attempts left, which the claim predicate cannot express. */
  private boolean attemptsExhausted(UUID runId, Step step) {
    return runs.step(runId, step)
        .filter(current -> current.status() == StepStatus.PENDING)
        .filter(current -> !LearningWorkflowPolicy.mayRetry(current.attemptCount()))
        .isPresent();
  }

  private void failExhausted(Run run, Step step) {
    runs.markCurrentStepTerminal(run.id(), step, StepStatus.FAILED, REASON_ATTEMPTS_EXHAUSTED);
    runs.finishRun(run.id(), Status.FAILED, REASON_ATTEMPTS_EXHAUSTED);
    log("workflow.failed", run, step, REASON_ATTEMPTS_EXHAUSTED);
  }

  private void skipRemaining(Run run, Step from, String reasonCode) {
    Optional<Step> next = LearningWorkflowPolicy.next(from);
    while (next.isPresent()) {
      Step step = next.get();
      // Marked, never claimed: a step that policy declined has no attempt to its name.
      runs.markSkipped(run.id(), step, reasonCode);
      next = LearningWorkflowPolicy.next(step);
    }
  }

  private void stop(Run run, String reasonCode) {
    runs.finishRun(run.id(), Status.STOPPED, reasonCode);
    log("workflow.stopped", run, null, reasonCode);
  }

  /**
   * Closes a run that ran out of time or was cancelled, marking whichever step it was on.
   *
   * <p>The run transition goes first and is guarded on RUNNING, so exactly one caller wins. Clearing
   * the step's claim token is what makes the decision stick: a worker still waiting on a model finds
   * its completion refused when it returns, rather than overwriting this terminal state.
   */
  private boolean expire(Run run, Status status, String reasonCode) {
    if (!runs.finishRun(run.id(), status, reasonCode)) {
      return false;
    }
    Step step = run.currentStep();
    if (step != null) {
      StepStatus stepStatus =
          status == Status.CANCELLED ? StepStatus.CANCELLED : StepStatus.TIMED_OUT;
      runs.markCurrentStepTerminal(run.id(), step, stepStatus, reasonCode);
      skipRemaining(run, step, reasonCode);
    }
    log("workflow." + status.name().toLowerCase(Locale.ROOT), run, step, reasonCode);
    return true;
  }

  private Run reload(UUID runId) {
    return runs.findById(runId).orElseThrow(() -> unknownRun(runId));
  }

  private void log(String operation, Run run, Step step, String reasonCode) {
    LOGGER
        .atInfo()
        .addKeyValue("operation", operation)
        .addKeyValue("runId", run.id())
        .addKeyValue("workflowType", run.workflowType())
        .addKeyValue("policyVersion", run.policyVersion())
        .addKeyValue("step", step == null ? null : step.name())
        .addKeyValue("reasonCode", reasonCode)
        .addKeyValue("learnerId", run.learnerId())
        .addKeyValue("skillId", run.skillId())
        .addKeyValue("evaluationRequestId", run.evaluationRequestId())
        .addKeyValue("interactionId", run.interactionId())
        .addKeyValue("traceId", run.traceId())
        .log("controlled workflow transition");
  }

  private static String triggerKey(String evaluationRequestId) {
    return LearningWorkflow.TYPE_EVALUATION_TO_ADAPTATION + ":" + evaluationRequestId;
  }

  private static void requireTrigger(EvaluationTrigger trigger) {
    if (trigger == null
        || trigger.evaluationRequestId() == null
        || trigger.evaluationRequestId().isBlank()
        || trigger.learnerId() == null
        || trigger.skillId() == null
        || trigger.curriculumVersionId() == null
        || trigger.attemptId() == null
        || trigger.assessmentVersionId() == null
        || trigger.interactionId() == null
        || trigger.interactionId().isBlank()) {
      throw new IllegalArgumentException(
          "a controlled workflow requires complete runtime-owned identities");
    }
  }

  private static IllegalArgumentException unknownRun(UUID runId) {
    return new IllegalArgumentException("unknown workflow run: " + runId);
  }

  /** A step that produced no verdict. Distinct from a verdict of "rejected", which is a result. */
  public static final class WorkflowStepFailedException extends RuntimeException {
    public WorkflowStepFailedException(String reasonCode) {
      super(reasonCode == null ? REASON_STEP_FAILED : reasonCode);
    }
  }
}
