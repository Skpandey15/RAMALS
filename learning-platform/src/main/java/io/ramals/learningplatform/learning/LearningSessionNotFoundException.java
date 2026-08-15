package io.ramals.learningplatform.learning;

/**
 * Raised when a session does not exist, cannot be parsed, or is not owned by the caller. The cases
 * are indistinguishable so session existence is not disclosed across learners.
 */
public class LearningSessionNotFoundException extends RuntimeException {

  public LearningSessionNotFoundException(String sessionId) {
    super("Learning session was not found: " + sessionId);
  }
}
