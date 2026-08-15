package io.ramals.learningplatform.learner;

/** Raised when a learner has not yet set a learning goal. */
public class LearnerGoalNotSetException extends RuntimeException {

  public LearnerGoalNotSetException() {
    super("The learner has not set a learning goal.");
  }
}
