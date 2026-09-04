package io.ramals.learningplatform.diagnosis;

/**
 * What {@link GapDiagnosisService} concludes about one skill, from the learner's already-computed
 * mastery status and the curriculum's prerequisite graph. Never a claim the mastery engine itself
 * makes -- see {@link GapDiagnosisService} for why this stays a read-only interpretation layer.
 */
public enum GapClassification {

  /** No mastery snapshot exists yet, or the one that does is itself INSUFFICIENT_EVIDENCE. Not
   * enough signal to diagnose this skill at all -- distinct from a confirmed weakness. */
  INSUFFICIENT_EVIDENCE,

  /** A real, evidenced weakness whose prerequisites are all MASTERED. The foundation is solid, so
   * this is not inherited -- it is specific to this skill. */
  INDEPENDENT_GAP,

  /** A real, evidenced weakness where every direct prerequisite is also weak. The prerequisite
   * chain has been walked to its most-upstream weak skill(s); see
   * {@link SkillGapDiagnosis#candidateRootCauseSkillCodes()}. */
  PREREQUISITE_GAP,

  /** A real, evidenced weakness where at least one prerequisite is weak or unproven, but not all
   * of them -- inheritance cannot be confirmed or ruled out. */
  POSSIBLY_INHERITED_GAP,

  /** MASTERED on its own evidence. Confirmed regardless of what is or is not secured upstream. */
  CONFIRMED_STRENGTH
}
