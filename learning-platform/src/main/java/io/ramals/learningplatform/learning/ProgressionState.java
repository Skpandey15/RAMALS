package io.ramals.learningplatform.learning;

/** A learner's progression state for a skill, derived from mastery and prerequisite readiness. */
public enum ProgressionState {
  LOCKED,
  ELIGIBLE,
  NEEDS_PRACTICE,
  MASTERED,
  RETENTION_DUE
}
