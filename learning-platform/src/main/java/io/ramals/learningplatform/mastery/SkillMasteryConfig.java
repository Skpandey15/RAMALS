package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;
import java.util.List;

/** Per-skill mastery and confidence configuration resolved from the pinned skill version. */
public record SkillMasteryConfig(
    BigDecimal masteryThreshold,
    BigDecimal confidenceThreshold,
    int requiredEvidenceCount,
    int requiredObjectives,
    List<String> requiredDifficultyBands) {
}
