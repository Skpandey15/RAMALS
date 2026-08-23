package io.ramals.learningplatform.orchestration;

import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate;
import io.ramals.learningplatform.mastery.MasteryStatus;
import io.ramals.learningplatform.orchestration.LearningWorkflow.Step;
import java.math.BigDecimal;
import java.time.Duration;

/**
 * The deterministic trigger and eligibility rules for a controlled composition (M2-T14).
 *
 * <p>Pure, versioned and free of any model or clock dependency, for the same reason the proposal
 * gates are: an eligibility rule that consulted a model would let the AI plane decide when it gets
 * to run. Every method here answers "may the workflow continue?" from Spring-owned facts only.
 */
public final class LearningWorkflowPolicy {

  public static final String POLICY_VERSION = "WORKFLOW_POLICY_V1";

  /**
   * Attempts one step may make before the run fails. Three is enough to ride out a provider blip
   * and small enough that a poison step becomes visible rather than being retried indefinitely.
   */
  public static final int MAX_STEP_ATTEMPTS = 3;

  /**
   * How long a claim may go unheard from before another worker may take the step.
   *
   * <p>Must exceed the longest step execution deadline, which is the diagnostic provider call at 12
   * seconds. Sized well above it: reclaiming a step whose worker is merely slow wastes a model call,
   * and the cost of waiting is only latency on a path that already tolerates a five-minute run.
   *
   * <p>A lease that is too short degrades safely rather than dangerously. Reclaiming issues a new
   * execution token, so the original worker's completion no longer matches and is rejected by the
   * same compare-and-set that rejects a cancelled worker's. The result is duplicated effort, not a
   * duplicated authoritative effect.
   *
   * <p>This bound is only sound because every step is replay-safe. Reclaiming a step that appended
   * an authoritative row without recording that it had would produce a second one.
   */
  public static final Duration CLAIM_LEASE = Duration.ofMinutes(1);

  /**
   * Absolute wall-clock budget for one run. It is the outermost of the bounds: even if every step
   * is individually within its attempt ceiling, a composition that has been running for this long
   * is not going to produce a useful adaptation for the learner who submitted the answer.
   */
  public static final Duration RUN_DEADLINE = Duration.ofMinutes(5);

  private LearningWorkflowPolicy() {}

  /** Why a workflow may not start or continue. ACCEPTED-equivalent is {@link #ELIGIBLE}. */
  public record Eligibility(boolean eligible, String reasonCode) {

    public static final Eligibility ELIGIBLE = new Eligibility(true, null);

    public static Eligibility not(String reasonCode) {
      return new Eligibility(false, reasonCode);
    }
  }

  /**
   * Whether a gated evaluation may start a composition at all.
   *
   * <p>Only an ACCEPTED gate decision qualifies. A REJECTED or MANUAL_REVIEW evaluation is not a
   * weaker signal to be used cautiously downstream; it is content the deterministic gate declined,
   * and letting it seed evidence would route rejected model output into authoritative state by the
   * side door M2-T12 exists to close.
   */
  public static Eligibility evaluationEligible(
      EvaluationProposalGate.Outcome outcome, BigDecimal normalizedScore) {
    if (outcome == null || !outcome.allowsAuthoritativeEffect()) {
      return Eligibility.not("EVALUATION_NOT_ACCEPTED");
    }
    if (normalizedScore == null
        || normalizedScore.signum() < 0
        || normalizedScore.compareTo(BigDecimal.ONE) > 0) {
      return Eligibility.not("EVALUATION_SCORE_UNUSABLE");
    }
    return Eligibility.ELIGIBLE;
  }

  /**
   * Whether the diagnostic agent should be asked anything (G02).
   *
   * <p>A learner who has just been assessed as MASTERED has no diagnosis to make, so the workflow
   * stops instead of spending a model call to be told so. This is the "no unnecessary agents"
   * criterion, and it is deliberately a function of the recomputed snapshot rather than of the
   * proposal that preceded it.
   */
  public static Eligibility diagnosisEligible(MasteryStatus status) {
    if (status == null) {
      return Eligibility.not("MASTERY_STATUS_UNKNOWN");
    }
    return status == MasteryStatus.MASTERED
        ? Eligibility.not("DIAGNOSIS_NOT_REQUIRED")
        : Eligibility.ELIGIBLE;
  }

  /**
   * Whether an accepted diagnosis should produce an adaptation (G03).
   *
   * <p>A diagnosis the gate did not accept must not be adapted on. Recording the stop is the point:
   * "we diagnosed and deliberately did not change the learner's path" is a business outcome, and a
   * silent absence of adaptation is indistinguishable from a dropped workflow.
   */
  public static Eligibility adaptationEligible(boolean diagnosisAccepted) {
    return diagnosisAccepted
        ? Eligibility.ELIGIBLE
        : Eligibility.not("ADAPTATION_PROPOSAL_REJECTED");
  }

  /**
   * Whether a step may be attempted again.
   *
   * <p>Bounded by attempt count alone. Making this depend on the failure kind was tempting and is
   * how a "transient" classification quietly becomes an unbounded loop when a provider reports a
   * permanent fault as a timeout.
   */
  public static boolean mayRetry(int attemptCount) {
    return attemptCount < MAX_STEP_ATTEMPTS;
  }

  /** The next step, or empty when the composition is complete. Strictly forward-only. */
  public static java.util.Optional<Step> next(Step completed) {
    return completed.next();
  }
}
