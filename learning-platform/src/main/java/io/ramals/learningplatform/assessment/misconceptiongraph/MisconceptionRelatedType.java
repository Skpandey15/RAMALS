package io.ramals.learningplatform.assessment.misconceptiongraph;

/**
 * The closed set of sub-types a {@code MISCONCEPTION_RELATED} edge may carry (M2-ADR-033 §1).
 *
 * <ul>
 *   <li>{@link #CO_OCCURS_WITH} and {@link #CONTRASTS_WITH} are <em>symmetric by meaning</em>: an
 *       edge is stored once, under the canonical ordering
 *       {@code misconceptionAId < misconceptionBId}, so "A related B" and "B related A" can never
 *       be two separate logical edges.
 *   <li>{@link #SPECIALISES} is <em>inherently directed</em> ("A is a more specific case of B"). It
 *       keeps its authored {@code a -> b} direction, is exempt from the canonical ordering, and is
 *       acyclic (enforced in PostgreSQL by {@code trg_misconception_relationship_guard} and
 *       re-checked here). {@code GENERALISES} is simply {@code SPECIALISES} read the other way and
 *       is never a stored value.
 * </ul>
 *
 * <p>None of these types asserts causality, probability, test order, diagnostic priority, or that a
 * learner holds either misconception (M2-ADR-033 §4).
 */
public enum MisconceptionRelatedType {
  CO_OCCURS_WITH(true),
  SPECIALISES(false),
  CONTRASTS_WITH(true);

  private final boolean symmetric;

  MisconceptionRelatedType(boolean symmetric) {
    this.symmetric = symmetric;
  }

  /**
   * Whether this type is symmetric by meaning, and therefore stored once under the canonical
   * ordering of the two misconception ids.
   */
  public boolean isSymmetric() {
    return symmetric;
  }

  /** Whether this type is directed and its authored {@code a -> b} order carries meaning. */
  public boolean isDirected() {
    return !symmetric;
  }
}
