package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.curriculum.AssessmentItemType;

/**
 * H4b foundation (M2-ADR-024): how a probe's response relates to the {@link DiagnosticHypothesis}
 * it was selected to test. Deliberately three-valued and deliberately not a stored, permanent
 * {@code is_correct} boolean -- that column stays exactly what it is on
 * {@code core.assessment_response}, a scoring fact; this is a separate, small, non-persisted
 * interpretation of it, kept extensible for evidence this platform cannot yet produce.
 */
public enum HypothesisEvidenceOutcome {

  /** The probe was answered incorrectly: consistent with the hypothesis, one more data point
   * toward it rather than proof of it. */
  SUPPORTING,

  /** The probe was answered correctly: weighs against the hypothesis. Neither this nor
   * {@link #SUPPORTING} is itself a diagnosis -- see {@link DiagnosticHypothesis}. */
  CONTRADICTORY,

  /** The probe's correctness cannot be deterministically interpreted as evidence either way. Not
   * reachable today: {@link AssessmentItemType#SINGLE_CHOICE} and
   * {@link AssessmentItemType#FILL_BLANK} are the only scoreable types
   * ({@link AssessmentItemType#scoreable()}), and both classify deterministically. This exists for
   * {@link AssessmentItemType#SHORT_ANSWER}/{@link AssessmentItemType#USE_CASE} -- gated behind
   * M2-ADR-022 and never reaching a learner's form yet -- so that when rubric-scored evidence
   * arrives, "the classifier already had a place for a non-boolean result" is true rather than a
   * breaking change every caller has to absorb then. */
  INCONCLUSIVE;

  /**
   * The single place {@code is_correct} is turned into evidence. Every item type this platform can
   * present to a learner today is {@link AssessmentItemType#scoreable()}, so {@link #INCONCLUSIVE}
   * is unreachable in practice -- not omitted, reserved.
   */
  public static HypothesisEvidenceOutcome classify(AssessmentItemType itemType, boolean isCorrect) {
    if (!itemType.scoreable()) {
      return INCONCLUSIVE;
    }
    return isCorrect ? CONTRADICTORY : SUPPORTING;
  }
}
