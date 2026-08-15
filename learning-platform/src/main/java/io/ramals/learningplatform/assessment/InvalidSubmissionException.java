package io.ramals.learningplatform.assessment;

/** Raised when a submission is structurally invalid (duplicate item or unusable option selection). */
public class InvalidSubmissionException extends RuntimeException {

  public InvalidSubmissionException(String reason) {
    super(reason);
  }
}
