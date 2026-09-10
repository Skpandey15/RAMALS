package io.ramals.learningplatform.assessment.misconceptiongraph;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code core.misconception_relationship} row: an authored, non-authoritative
 * {@code MISCONCEPTION_RELATED} edge between two published misconceptions (M2-ADR-033 §1).
 *
 * <p>Immutable value object. It carries identity, its two endpoints, its sub-type, its authored
 * lifecycle status, and a required rationale for auditability. It carries <b>no</b> learner id, no
 * mastery, no confidence, no probability, no evidence observation, no ranking score, and no weight
 * (M2-ADR-033 §4) -- there is nowhere here to put one.
 *
 * @param misconceptionAId for a symmetric type ({@link MisconceptionRelatedType#isSymmetric()})
 *     this is the smaller of the two ids under the canonical ordering; for {@link
 *     MisconceptionRelatedType#SPECIALISES} it is the more specific misconception (the {@code a} of
 *     "a specialises b")
 * @param misconceptionBId the other endpoint; for {@code SPECIALISES} the more general misconception
 * @param rationale why this authored relationship holds -- never blank
 */
public record MisconceptionRelationship(
    UUID id,
    UUID misconceptionAId,
    UUID misconceptionBId,
    MisconceptionRelatedType relatedType,
    MisconceptionGraphStatus status,
    String rationale,
    Instant createdAt,
    Instant publishedAt) {

  /** True once this edge is {@code PUBLISHED} and therefore immutable. */
  public boolean isPublished() {
    return status == MisconceptionGraphStatus.PUBLISHED;
  }
}
