package io.ramals.learningplatform.assessment.misconceptiongraph;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@code PUBLISHED} {@code core.misconception_prerequisite_link} whose misconception is in the
 * returned set (M2-ADR-033 §5, §10): an authored hint that the misconception "is commonly rooted in
 * that unsecured prerequisite skill". Immutable, authored knowledge only.
 *
 * <p>It is an <b>authored prerequisite hint</b>, never a computed cause. It does not say a learner
 * has that gap, does not rank, does not gate progression, and is read by no selection engine
 * (M2-ADR-033 §4/§6). The far endpoint is a plain {@code core.skill} (its id and stable code) --
 * the same kind of curriculum fact {@link CurriculumPrerequisiteView} carries -- never a
 * {@code learning_objective}.
 *
 * @param rationale authored explanatory text only -- no confidence or causal certainty may be
 *     derived from it (prompt §25)
 */
public record PublishedMisconceptionPrerequisiteLinkView(
    UUID id,
    UUID misconceptionId,
    UUID prerequisiteSkillId,
    String prerequisiteSkillCode,
    String rationale,
    Instant publishedAt) {
}
