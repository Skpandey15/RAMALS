package io.ramals.learningplatform.evidence;

import java.math.BigDecimal;

/**
 * A scored per-skill observation offered to the evidence ledger. Neutral of the assessment module
 * so the ledger depends on no upstream scoring types.
 */
public record SkillEvidenceInput(
    String skillCode,
    BigDecimal observedScore,
    BigDecimal normalizedScore,
    int itemsAnswered,
    int itemsCorrect) {
}
