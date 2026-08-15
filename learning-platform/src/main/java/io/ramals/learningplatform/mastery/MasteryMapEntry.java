package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;

/** Latest mastery for one skill, for the learner-facing mastery map. */
public record MasteryMapEntry(
    String skillCode,
    BigDecimal masteryScore,
    BigDecimal evidenceConfidence,
    String masteryStatus,
    int aggregateVersion) {
}
