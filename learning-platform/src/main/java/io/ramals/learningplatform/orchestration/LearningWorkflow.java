package io.ramals.learningplatform.orchestration;

import java.time.Instant;
import java.util.UUID;

/**
 * Vocabulary for one controlled M2-T14 composition.
 *
 * <p>The composition is an explicit, ordered list of steps rather than a set of agents that may
 * call one another. That ordering is the whole point: an agent cannot advance the workflow, only a
 * deterministic transition recorded here can, so "agents recommend, deterministic services decide"
 * survives being spread across four steps and two service boundaries.
 */
public final class LearningWorkflow {

  public static final String TYPE_EVALUATION_TO_ADAPTATION = "EVALUATION_TO_ADAPTATION";

  private LearningWorkflow() {}

  /**
   * The four steps, in the only order they may run.
   *
   * <p>The two deterministic steps come first on purpose. Authoritative learner state is settled by
   * Spring before any agent is asked for an opinion, so a failed or slow agent can never leave the
   * evidence ledger half-written.
   */
  public enum Step {
    RECORD_EVALUATION_EVIDENCE(0, false, false),
    RECOMPUTE_MASTERY(1, false, false),
    DIAGNOSE(2, true, true),
    // ADAPT carries a request identity but makes no provider call of its own: it hands work to the
    // outbox and the dispatcher calls the model later. That distinction decides whether the step may
    // be executed inside a transaction, so it is a property rather than a comment.
    ADAPT(3, true, false);

    private final int index;
    private final boolean invokesAgent;
    private final boolean remoteCall;

    Step(int index, boolean invokesAgent, boolean remoteCall) {
      this.index = index;
      this.invokesAgent = invokesAgent;
      this.remoteCall = remoteCall;
    }

    public int index() {
      return index;
    }

    /** Whether this step carries a request identity that correlates to the AI execution ledger. */
    public boolean invokesAgent() {
      return invokesAgent;
    }

    /**
     * Whether executing this step blocks on a model or provider.
     *
     * <p>A step that does must never run inside a transaction; a step that does not may commit its
     * effect together with its workflow marker, which is what closes the crash window between them.
     */
    public boolean remoteCall() {
      return remoteCall;
    }

    public static Step first() {
      return RECORD_EVALUATION_EVIDENCE;
    }

    /** The next step, or empty when this was the last one. Never wraps. */
    public java.util.Optional<Step> next() {
      Step[] all = values();
      return index + 1 < all.length ? java.util.Optional.of(all[index + 1]) : java.util.Optional.empty();
    }
  }

  /** Terminal states are distinguished so an operator can tell a stop from a failure. */
  public enum Status {
    RUNNING,
    /** Every step that policy required has completed. */
    COMPLETED,
    /** Policy deterministically declined to continue. A legitimate outcome, not an error. */
    STOPPED,
    CANCELLED,
    TIMED_OUT,
    /** A step exhausted its bounded attempts. Inspectable and re-triggerable by an operator. */
    FAILED;

    public boolean terminal() {
      return this != RUNNING;
    }
  }

  public enum StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    /** Policy determined this step was not required. */
    SKIPPED,
    CANCELLED,
    TIMED_OUT,
    FAILED;

    public boolean terminal() {
      return this != PENDING && this != RUNNING;
    }
  }

  /** The authoritative identifiers a composition needs; none of them come from an agent. */
  public record Run(
      UUID id,
      String workflowType,
      String policyVersion,
      String triggerKey,
      UUID learnerId,
      UUID skillId,
      UUID curriculumVersionId,
      UUID attemptId,
      UUID assessmentVersionId,
      java.math.BigDecimal normalizedScore,
      String evaluationRequestId,
      Status status,
      Step currentStep,
      String terminalReason,
      String interactionId,
      String traceId,
      Instant deadlineAt,
      Instant startedAt,
      Instant completedAt) {}

  /**
   * Proof that one worker, and only one, owns an in-flight attempt of a step.
   *
   * <p>Held across the remote call and presented again to finish it. A worker whose run was
   * cancelled or timed out meanwhile no longer matches, so its completion is refused rather than
   * overwriting the terminal state that replaced it.
   */
  public record StepClaim(UUID runId, Step step, int attemptCount, UUID executionToken) {}

  /** One separately observable, retryable and attributable step of a run. */
  public record StepRun(
      UUID id,
      UUID runId,
      Step step,
      StepStatus status,
      int attemptCount,
      String reasonCode,
      String requestId,
      UUID resultRef,
      UUID executionToken,
      Instant claimedAt,
      Instant startedAt,
      Instant completedAt) {}
}
