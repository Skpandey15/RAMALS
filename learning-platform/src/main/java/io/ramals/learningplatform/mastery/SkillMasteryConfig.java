package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;

/** Per-skill mastery configuration resolved from the pinned skill version. */
public record SkillMasteryConfig(BigDecimal masteryThreshold, int requiredEvidenceCount) {
}
