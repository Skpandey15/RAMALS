package io.ramals.learningplatform.learning;

/** Raised when a transition's expected version no longer matches, i.e. a concurrent change won. */
public class SessionConflictException extends RuntimeException {

  public SessionConflictException(String sessionId) {
    super("Learning session was modified concurrently: " + sessionId);
  }
}
