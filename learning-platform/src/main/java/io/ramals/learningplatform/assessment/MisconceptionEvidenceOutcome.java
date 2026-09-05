package io.ramals.learningplatform.assessment;

/**
 * Granular diagnostic ontology foundation (M2-ADR-026): how a response to a misconception-evidence-
 * eligible {@code SINGLE_CHOICE} item relates to one specific {@link Misconception} under test. A
 * deliberately separate classifier from {@link HypothesisEvidenceOutcome} -- not a modification,
 * overload, or reinterpretation of it. The two answer different questions from different inputs:
 * {@code HypothesisEvidenceOutcome.classify} takes only {@code (itemType, isCorrect)}; this takes
 * whether the specific selected option is itself tagged to the misconception under test, a genuine
 * third input {@code HypothesisEvidenceOutcome} cannot express.
 *
 * <p><b>Eligibility is a precondition, not a fourth value.</b> An item carrying no {@code PUBLISHED}
 * {@code core.assessment_item_option_misconception} row for the misconception under test is not
 * misconception-evidence-eligible for it at all -- {@link #classify} is never called in that case,
 * the same way H4b's own resolver separates "not eligible" from its evidence-bearing outcomes.
 */
public enum MisconceptionEvidenceOutcome {

  /** The selected option was incorrect and is itself {@code PUBLISHED}-tagged to the misconception
   * under test. */
  SUPPORTING,

  /** The selected option was the item's correct answer, on an item that is misconception-evidence-
   * eligible for the misconception under test. */
  CONTRADICTORY,

  /** The selected option was incorrect, but is tagged to a different misconception, or is untagged
   * entirely. Wrong for an unrelated or unestablished reason neither confirms nor refutes the
   * misconception under test. */
  INCONCLUSIVE;

  /**
   * The single place a scored response is turned into misconception evidence. Callers must have
   * already established eligibility (a {@code PUBLISHED} mapping for the misconception under test
   * exists on this item) before calling this -- ineligibility is not a value this method returns.
   *
   * @param isCorrect whether the selected option was the item's correct answer
   * @param selectedOptionTaggedToMisconceptionUnderTest whether the specific option actually
   *     selected is itself {@code PUBLISHED}-tagged to the misconception under test
   */
  public static MisconceptionEvidenceOutcome classify(
      boolean isCorrect, boolean selectedOptionTaggedToMisconceptionUnderTest) {
    if (isCorrect) {
      return CONTRADICTORY;
    }
    return selectedOptionTaggedToMisconceptionUnderTest ? SUPPORTING : INCONCLUSIVE;
  }
}
