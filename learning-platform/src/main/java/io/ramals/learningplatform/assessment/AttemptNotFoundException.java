package io.ramals.learningplatform.assessment;

/**
 * Raised when an attempt does not exist, cannot be parsed, or is not owned by the caller. These
 * cases are deliberately indistinguishable so attempt existence is not disclosed across learners.
 */
public class AttemptNotFoundException extends RuntimeException {

  public AttemptNotFoundException(String attemptId) {
    super("Assessment attempt was not found: " + attemptId);
  }
}
