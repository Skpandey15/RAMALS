package io.ramals.learningplatform.orchestration;

import io.ramals.learningplatform.orchestration.LearningWorkflow.Run;

/**
 * The two composition steps that consult the AI plane.
 *
 * <p>They are ports rather than direct calls so the orchestrator depends on the shape of a step --
 * did it succeed, what identity does it correlate to -- and not on any agent client. That keeps the
 * state machine testable without a model, and it keeps the AI plane on the far side of an interface
 * the deterministic core owns.
 */
public interface WorkflowAgentStep {

  /**
   * The outcome of one agent step.
   *
   * @param succeeded whether the step produced a usable result at all. A gate that deliberately
   *     rejected a proposal still {@code succeeded}: the step ran and returned a verdict. Only a
   *     failure to obtain any verdict is {@code false}.
   * @param accepted whether the deterministic gate accepted the proposal. Drives eligibility for
   *     the following step, never the step's own retry decision.
   * @param retryable whether another attempt could plausibly produce a verdict. False when the
   *     durable execution record already settles the matter.
   * @param reasonCode stable, platform-authored code. Never model prose.
   * @param requestId the identity this step correlates to in {@code core.ai_execution}.
   */
  record Result(
      boolean succeeded, boolean accepted, boolean retryable, String reasonCode, String requestId) {

    public static Result accepted(String reasonCode, String requestId) {
      return new Result(true, true, true, reasonCode, requestId);
    }

    public static Result rejected(String reasonCode, String requestId) {
      return new Result(true, false, true, reasonCode, requestId);
    }

    /** No verdict was obtained, and trying again might get one. */
    public static Result failed(String reasonCode, String requestId) {
      return new Result(false, false, true, reasonCode, requestId);
    }

    /**
     * No verdict was obtained and no further attempt can produce one.
     *
     * <p>Distinct from {@link #failed} because retrying is not merely wasteful here but misleading:
     * a request whose execution is already terminal will be refused by commissioning every time, so
     * the run would spend its remaining attempts rediscovering a fact already on record and then
     * report exhaustion rather than the reason that actually stopped it.
     */
    public static Result terminal(String reasonCode, String requestId) {
      return new Result(false, false, false, reasonCode, requestId);
    }
  }

  /** Diagnoses the learner's state for the skill this run is about. */
  interface Diagnostic extends WorkflowAgentStep {
    Result diagnose(Run run);
  }

  /**
   * Hands the recomputed state to the adaptation path.
   *
   * <p>The snapshot is passed in rather than looked up, so adaptation consumes the exact state this
   * workflow produced. A step that fetched "the latest" would silently adopt a snapshot written by
   * an unrelated learner event between the two steps.
   */
  interface Adaptation extends WorkflowAgentStep {
    Result adapt(Run run, java.util.UUID masterySnapshotId);
  }
}
