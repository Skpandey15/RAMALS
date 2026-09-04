package io.ramals.learningplatform.assessment;

/**
 * H4b foundation (M2-ADR-024): the deterministic semantics a {@link DiagnosticHypothesis} may be
 * raised under. All four are first-class outputs of {@link ProbeRelationshipResolver}; only two are
 * backed by their own storage.
 */
public enum ProbeRelationshipType {

  /** Another verified item tagged to the same {@code learning_objective} as the trigger item, read
   * from {@code core.assessment_item_objective} (V046). No new storage -- the objective tagging
   * that already decides coverage is the same fact that decides this. */
  SAME_OBJECTIVE_CONFIRMATION,

  /** A required objective of one of the trigger skill's curriculum prerequisites, read from
   * {@code core.skill_prerequisite} (V003). No new storage, and no change to how M2-ADR-023 §1
   * already treats that graph -- this is one more *reader* of prerequisite evidence, not a new
   * gate; a weak or unsecured prerequisite is still never a reason to exclude the dependent skill
   * from ordinary selection. */
  PREREQUISITE_VALIDATION,

  /** A hand-authored link to a different, causally-plausible objective the curriculum graph does
   * not already assert -- e.g. an ACKS-durability miss is worth checking against the same skill's
   * idempotence objective. Backed by {@code core.diagnostic_probe_relationship}; only a
   * {@code PUBLISHED} row authorizes a hypothesis under this type. */
  ROOT_CAUSE_PROBE,

  /** A hand-authored link to an objective whose *correct* answer would specifically weigh against
   * the hypothesis raised by the trigger miss, narrowing rather than confirming it. Backed by the
   * same {@code core.diagnostic_probe_relationship} table as {@link #ROOT_CAUSE_PROBE}, distinct
   * only in authored intent -- evidence classification ({@link HypothesisEvidenceOutcome}) is
   * computed uniformly from correctness regardless of which type triggered the probe. */
  CONTRADICTION_CHECK
}
