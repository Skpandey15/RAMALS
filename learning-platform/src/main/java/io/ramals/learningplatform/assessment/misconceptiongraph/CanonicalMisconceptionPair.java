package io.ramals.learningplatform.assessment.misconceptiongraph;

import java.util.UUID;

/**
 * The canonical {@code (a, b)} storage order for a {@code MISCONCEPTION_RELATED} edge, so a
 * symmetric relationship is stored once and "A related B" / "B related A" cannot both exist
 * (M2-ADR-033 §1).
 *
 * <ul>
 *   <li>Symmetric type ({@link MisconceptionRelatedType#isSymmetric()}): {@code a} is the smaller
 *       of the two ids and {@code b} the larger, under the same unsigned lexical ordering
 *       PostgreSQL's {@code uuid} type uses -- so the value here always satisfies the database's
 *       {@code ck_misconception_relationship_canonical_order} check.
 *   <li>Directed type ({@link MisconceptionRelatedType#SPECIALISES}): the authored
 *       {@code source -> target} order is preserved unchanged.
 * </ul>
 *
 * <p>The ordering is done on the canonical lowercase UUID string, which is a byte-for-byte lexical
 * match for PostgreSQL's {@code uuid} comparison, rather than {@link UUID#compareTo} (which compares
 * the two 64-bit halves as signed).
 */
public record CanonicalMisconceptionPair(UUID a, UUID b) {

  public static CanonicalMisconceptionPair of(
      MisconceptionRelatedType type, UUID source, UUID target) {
    if (type.isSymmetric() && source.toString().compareTo(target.toString()) > 0) {
      return new CanonicalMisconceptionPair(target, source);
    }
    return new CanonicalMisconceptionPair(source, target);
  }

  /** Whether {@code a} precedes {@code b} under the same ordering PostgreSQL's {@code uuid} uses. */
  public boolean isOrdered() {
    return a.toString().compareTo(b.toString()) < 0;
  }
}
