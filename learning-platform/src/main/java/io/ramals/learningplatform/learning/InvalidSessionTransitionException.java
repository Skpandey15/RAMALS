package io.ramals.learningplatform.learning;

/** Raised when a command is not a legal transition from the session's current status. */
public class InvalidSessionTransitionException extends RuntimeException {

  public InvalidSessionTransitionException(
      LearningSessionStatus status, LearningSessionCommand command) {
    super("Command " + command + " is not valid from status " + status + ".");
  }
}
