package io.ramals.learningplatform.assessment;

/**
 * Why an item earned its slot in a form.
 *
 * <p>Persisted per selected item so a form can be audited against the rule that produced it. The
 * set of items alone cannot distinguish a form that satisfied its coverage requirement from one
 * that happens to look as though it did.
 */
public enum SelectionReason {

  /** First item taken for its skill, satisfying the one-item-per-skill coverage floor. */
  SKILL_COVERAGE,

  /** Taken because its difficulty band was otherwise unrepresented in the form. */
  DIFFICULTY_COVERAGE,

  /** Taken to reach the configured form size once coverage was already satisfied. */
  FILL,

  // -----------------------------------------------------------------------------------------
  // DIAGNOSTIC_SELECTION_V2 only. V045's three reasons above describe coverage of a form;
  // these describe what the learner's own mastery evidence said about a skill at the moment of
  // selection. V1 never emits these -- see AdaptiveDiagnosticSelector.
  // -----------------------------------------------------------------------------------------

  /** The learner has no mastery snapshot yet for this skill: the diagnostic baseline case. */
  UNSEEN_ITEM,

  /** Taken at the skill's already-evidenced band because evidence confidence has not yet met
   * its threshold -- more evidence is needed before this skill's status can be trusted. */
  LOW_CONFIDENCE,

  /** Taken at the skill's already-evidenced band because confidence is adequate but the mastery
   * score is below threshold: a remediation-flavoured pick, held rather than escalated. */
  WEAK_SKILL,

  /** Taken because the skill's required objectives are not yet fully covered by evidence, even
   * though confidence and mastery score both clear their thresholds. */
  OBJECTIVE_COVERAGE_GAP,

  /** Taken one band above the skill's already-evidenced band because confidence, mastery score,
   * and objective coverage all cleared their thresholds at that band -- evidence-driven
   * escalation, never more than one band at a time. */
  DIFFICULTY_PROGRESSION,

  /** The skill already clears every threshold at ADVANCED; taken anyway, at the lowest
   * priority, so a mastered skill is not dropped from selection entirely. Distinct from a
   * future spaced-repetition RETENTION_CHECK, which does not exist yet and is not this. */
  MASTERY_CONFIRMATION,

  /** DIAGNOSTIC_SELECTION_V3 only -- see PrerequisiteAwareDiagnosticSelector. The skill's own
   * evidence would otherwise justify a band above FOUNDATIONAL, but at least one of its
   * curriculum prerequisites has not reached MASTERED, so the band was capped at FOUNDATIONAL and
   * this skill's priority demoted (M2-ADR-023 §1: evidence, never a gate -- the skill is still
   * selected, just not trusted at an escalated band it may not have earned). */
  PREREQUISITE_NOT_SECURED,

  /** DIAGNOSTIC_SELECTION_V4 only -- see HypothesisConfirmationDiagnosticSelector. "H4a": cross
   * -attempt regression confirmation of the SAME skill, not H4b's (future, unbuilt) hypothesis
   * -driven probe of a different, causally related skill. This skill's two most recent mastery
   * snapshots show a status regression by V4's own frozen mastery-rank contract (the previous one
   * ranked better than the latest), a cross-attempt signal that something unexpected happened -- a
   * fluke miss, or a real, newly-surfaced gap. Cross-attempt because the current one-shot
   * batch-submit model has no way to ask a follow-up within the same attempt; this is that
   * follow-up, deferred to the learner's next diagnostic session rather than never asked at all.
   * Confirming or refuting it is given priority over routine coverage; the band tested is whatever
   * the skill's own signal (held, possibly further capped by an unsecured prerequisite) already
   * decided -- this reason changes priority and its own label, never the band. */
  HYPOTHESIS_CONFIRMATION
}
