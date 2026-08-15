package io.ramals.learningplatform.learning;

/** Lifecycle status of a learning session. COMPLETED and ABANDONED are terminal. */
public enum LearningSessionStatus {
  ACTIVE,
  PAUSED,
  COMPLETED,
  ABANDONED
}
