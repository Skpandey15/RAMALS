package io.ramals.learningplatform.learning;

/** A command that drives a learning session transition. */
public enum LearningSessionCommand {
  PAUSE,
  RESUME,
  COMPLETE,
  ABANDON
}
