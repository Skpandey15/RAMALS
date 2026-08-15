package io.ramals.learningplatform.mastery;

/** Deterministic mastery status derived from score, evidence volume, and the skill threshold. */
public enum MasteryStatus {
  INSUFFICIENT_EVIDENCE,
  NEEDS_RETEACH,
  NEEDS_PRACTICE,
  DEVELOPING,
  MASTERED
}
