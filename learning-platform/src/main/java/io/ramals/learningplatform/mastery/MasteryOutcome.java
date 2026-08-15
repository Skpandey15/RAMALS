package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;

/** The deterministic result of a mastery computation, before persistence. */
public record MasteryOutcome(
    BigDecimal masteryScore,
    MasteryStatus status,
    BigDecimal threshold,
    int evidenceCount,
    int itemsConsidered) {
}
