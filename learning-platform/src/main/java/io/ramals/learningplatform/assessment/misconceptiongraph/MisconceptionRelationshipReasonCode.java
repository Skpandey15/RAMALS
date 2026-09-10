package io.ramals.learningplatform.assessment.misconceptiongraph;

/**
 * Stable, typed reasons a misconception graph edge is refused (M2-ADR-033 §13). Every value maps to
 * a deterministic rule enforced in {@link MisconceptionRelationshipValidator} and, where a race is
 * possible, also at the database boundary. These are an API contract -- never an arbitrary string.
 *
 * <p>Only the codes for rules M2-ADR-033 and the current data model actually impose are listed. In
 * particular there is no {@code CROSS_DOMAIN_RELATIONSHIP_NOT_ALLOWED} /
 * {@code CURRICULUM_VERSION_MISMATCH} for {@code MISCONCEPTION_RELATED}: the ADR does not require
 * same-domain related edges (cross-objective authored links are the point), so inventing that
 * constraint would be adding un-authored semantics. For {@code MISCONCEPTION_PREREQUISITE_LINK} the
 * equivalent is enforced structurally by {@link #PREREQUISITE_LINK_NOT_A_CURRICULUM_PREREQUISITE}.
 */
public enum MisconceptionRelationshipReasonCode {

  /** A required identifier or field was null / blank. */
  SOURCE_REQUIRED,
  TARGET_REQUIRED,
  RELATIONSHIP_TYPE_REQUIRED,
  RATIONALE_REQUIRED,

  /** {@code sourceMisconceptionId == targetMisconceptionId} (M2-ADR-033 §3). */
  SELF_RELATIONSHIP_NOT_ALLOWED,

  /** An endpoint does not resolve to an existing {@code core.misconception} row. */
  SOURCE_MISCONCEPTION_NOT_FOUND,
  TARGET_MISCONCEPTION_NOT_FOUND,

  /** The referenced prerequisite does not resolve to an existing {@code core.skill} row. */
  PREREQUISITE_SKILL_NOT_FOUND,

  /**
   * A logically identical edge already exists (same canonical pair and type for a related edge;
   * same {@code (misconception, prerequisite skill)} for a prerequisite link). Also the code a
   * unique-constraint violation from a concurrent author is translated into.
   */
  DUPLICATE_RELATIONSHIP,

  /** A {@code PUBLISHED} edge would reference a still-{@code DRAFT} endpoint (M2-ADR-033 §1). */
  PUBLISHED_ENDPOINT_REQUIRED,

  /** Adding this {@code SPECIALISES} edge would close a direct or transitive cycle (M2-ADR-033 §3). */
  SPECIALISES_CYCLE_NOT_ALLOWED,

  /**
   * At publish time, no {@code core.skill_prerequisite} row relates the misconception's owning
   * skill to the referenced prerequisite skill for the misconception's curriculum version
   * (M2-ADR-033 §1/§7). This is also what keeps a prerequisite link inside one domain and one
   * curriculum version.
   */
  PREREQUISITE_LINK_NOT_A_CURRICULUM_PREREQUISITE,

  /** The misconception's target arc does not reach a learning objective, so no owning skill exists. */
  MISCONCEPTION_OWNING_SKILL_UNRESOLVABLE,

  /** An attempt to mutate a {@code PUBLISHED} edge (M2-ADR-033 §1, DB-enforced). */
  IMMUTABLE_PUBLISHED_RELATIONSHIP
}
