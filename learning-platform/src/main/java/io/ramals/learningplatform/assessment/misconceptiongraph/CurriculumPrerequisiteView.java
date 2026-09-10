package io.ramals.learningplatform.assessment.misconceptiongraph;

import java.util.UUID;

/**
 * One existing curriculum prerequisite of the {@link MisconceptionGraphView}'s owning skill,
 * co-located with the node's misconception knowledge (M2-ADR-033 §5, prompt §11). Read straight
 * from the authoritative {@code core.skill_prerequisite} (skill-to-skill, curriculum-version-scoped,
 * {@code V003}); this view re-implements none of its semantics and adds nothing to it.
 *
 * <p>It is a curriculum-structure fact, not a per-learner gap and not a root cause: it says "the
 * skill this node belongs to has that prerequisite skill", nothing about any learner.
 */
public record CurriculumPrerequisiteView(UUID prerequisiteSkillId, String prerequisiteSkillCode) {
}
