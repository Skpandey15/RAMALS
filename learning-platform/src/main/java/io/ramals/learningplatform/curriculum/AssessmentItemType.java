package io.ramals.learningplatform.curriculum;

import java.util.List;
import java.util.Locale;

/**
 * The item-type vocabulary V047 admits into {@code assessment_item_version.item_type}, and the one
 * place a stored value is turned into something Java can branch on safely.
 *
 * <p>Two of the four are deterministically scoreable today. {@link #SINGLE_CHOICE} and
 * {@link #FILL_BLANK} have a versioned scorer in {@link io.ramals.learningplatform.assessment}.
 * {@link #SHORT_ANSWER} and {@link #USE_CASE} are authorable content only: M2-ADR-022 governs
 * whether and how a gated AI evaluation may ever score them, and until that path exists they must
 * never reach a learner's form. {@link #scoreable()} is the single predicate every selection and
 * submission path is expected to consult, so that boundary is asked once rather than re-derived.
 */
public enum AssessmentItemType {

  SINGLE_CHOICE(true),
  FILL_BLANK(true),
  SHORT_ANSWER(false),
  USE_CASE(false);

  private final boolean scoreable;

  AssessmentItemType(boolean scoreable) {
    this.scoreable = scoreable;
  }

  /** Whether a deterministic scorer exists for this type today. */
  public boolean scoreable() {
    return scoreable;
  }

  /**
   * Parses a stored item type, failing closed on anything unrecognized.
   *
   * @throws UnknownAssessmentItemTypeException if {@code value} is null, blank, or not a known type
   */
  public static AssessmentItemType of(String value) {
    if (value == null || value.isBlank()) {
      throw new UnknownAssessmentItemTypeException("An assessment item type is required.");
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    for (AssessmentItemType type : values()) {
      if (type.name().equals(normalized)) {
        return type;
      }
    }
    throw new UnknownAssessmentItemTypeException(
        "Unknown assessment item type: " + value + ". Known types: " + List.of(values()) + ".");
  }
}
