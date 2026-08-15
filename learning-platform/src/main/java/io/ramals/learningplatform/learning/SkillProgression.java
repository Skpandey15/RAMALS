package io.ramals.learningplatform.learning;

import java.util.UUID;

/** A skill's resolved progression for a learner. */
public record SkillProgression(
    UUID skillId,
    String skillCode,
    ProgressionState state,
    String reasonCode,
    String masteryStatus) {
}
