package io.ramals.learningplatform.curriculum;

/**
 * Raised when an item-type value has no place in the {@link AssessmentItemType} vocabulary.
 *
 * <p>The sibling of {@link UnknownDifficultyException} for the other closed vocabulary this
 * package owns. Kept as a distinct type rather than folded into it: the two exceptions are raised
 * by unrelated parsers over unrelated columns, and a caller catching one must not also swallow the
 * other by accident.
 */
public class UnknownAssessmentItemTypeException extends RuntimeException {

  public UnknownAssessmentItemTypeException(String message) {
    super(message);
  }
}
