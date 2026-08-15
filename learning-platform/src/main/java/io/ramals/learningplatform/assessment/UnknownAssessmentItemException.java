package io.ramals.learningplatform.assessment;

/** Raised when a submitted response references an item outside the attempt's assessment version. */
public class UnknownAssessmentItemException extends RuntimeException {

  public UnknownAssessmentItemException(String itemId) {
    super("Response references an item that is not part of this assessment: " + itemId);
  }
}
