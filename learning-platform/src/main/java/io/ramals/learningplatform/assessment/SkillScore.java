package io.ramals.learningplatform.assessment;

import java.math.BigDecimal;

/**
 * Deterministic per-skill diagnostic score. {@code observedScore} is the raw proportion correct;
 * {@code normalizedScore} applies the versioned guessing-aware correction.
 */
public record SkillScore(
    String skillCode,
    int itemsAnswered,
    int itemsCorrect,
    BigDecimal observedScore,
    BigDecimal normalizedScore) {
}
