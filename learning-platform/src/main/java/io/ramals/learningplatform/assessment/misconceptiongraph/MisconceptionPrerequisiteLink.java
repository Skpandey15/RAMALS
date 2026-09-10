package io.ramals.learningplatform.assessment.misconceptiongraph;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code core.misconception_prerequisite_link} row: an authored, non-authoritative
 * {@code MISCONCEPTION_PREREQUISITE_LINK} edge from a published misconception to a {@code
 * core.skill} that is a curriculum prerequisite of the skill owning the misconception's target
 * node (M2-ADR-033 §1). It means "this misconception is commonly rooted in that unsecured
 * prerequisite skill."
 *
 * <p>Immutable value object. It is an authored diagnostic hint: it does not prove a root cause,
 * gate progression, alter mastery, or trigger a probe. It carries no learner state and no
 * confidence.
 *
 * @param prerequisiteSkillId a {@code core.skill(id)} -- never a {@code core.learning_objective},
 *     which has no prerequisite endpoint. The database asserts at publish time that a matching
 *     {@code core.skill_prerequisite} row exists for the misconception's owning skill and
 *     curriculum version.
 * @param rationale why this authored root-cause hint holds -- never blank
 */
public record MisconceptionPrerequisiteLink(
    UUID id,
    UUID misconceptionId,
    UUID prerequisiteSkillId,
    MisconceptionGraphStatus status,
    String rationale,
    Instant createdAt,
    Instant publishedAt) {

  /** True once this edge is {@code PUBLISHED} and therefore immutable. */
  public boolean isPublished() {
    return status == MisconceptionGraphStatus.PUBLISHED;
  }
}
