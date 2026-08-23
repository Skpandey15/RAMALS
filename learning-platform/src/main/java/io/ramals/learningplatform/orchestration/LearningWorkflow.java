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
    RECORD_EVALUATION_EVIDENCE(0, false),
    RECOMPUTE_MASTERY(1, false),
    DIAGNOSE(2, true),
    ADAPT(3, true);

    private final int index;
    private final boolean invokesAgent;

    Step(int index, boolean invokesAgent) {
      this.index = index;
      this.invokesAgent = invokesAgent;
    }

    public int index() {
      return index;
    }

    /** Whether this step calls the AI plane, and therefore carries a request identity. */
    public boolean invokesAgent() {
      return invokesAgent;
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
      Instant startedAt,
      Instant completedAt) {}
}
