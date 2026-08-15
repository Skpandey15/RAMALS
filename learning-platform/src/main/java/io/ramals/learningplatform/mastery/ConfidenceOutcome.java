package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;

/** The deterministic confidence blend and its four components, for persistence and audit. */
public record ConfidenceOutcome(
    BigDecimal confidence,
    BigDecimal volumeSufficiency,
    BigDecimal objectiveCoverage,
    BigDecimal recency,
    BigDecimal consistency) {
}
