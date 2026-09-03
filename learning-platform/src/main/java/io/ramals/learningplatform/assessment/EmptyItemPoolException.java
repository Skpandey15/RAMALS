package io.ramals.learningplatform.assessment;

/**
 * Raised when a published assessment version offers no verified item to select from. V017 makes
 * this unreachable through publication, so it is an invariant violation rather than a learner
 * error: better to refuse the attempt than to hand back a diagnostic with no questions in it.
 */
public class EmptyItemPoolException extends RuntimeException {

  public EmptyItemPoolException(String message) {
    super(message);
  }
}
