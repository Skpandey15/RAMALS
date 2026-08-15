package io.ramals.learningplatform.assessment;

/** Raised when a submission targets an attempt that is not open for submission. */
public class InvalidAttemptStateException extends RuntimeException {

  public InvalidAttemptStateException(String status) {
    super("The attempt is not open for submission (status: " + status + ").");
  }
}
