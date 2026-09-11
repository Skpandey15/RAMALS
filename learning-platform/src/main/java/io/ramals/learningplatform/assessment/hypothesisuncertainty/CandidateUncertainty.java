package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import io.ramals.learningplatform.assessment.DiagnosticConfidenceBand;
import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import java.math.BigDecimal;

/**
 * One candidate's place in a {@link HypothesisUncertaintyResult} (M2-ADR-034 Amendment 1 §K).
 * Immutable. Carries no raw learner answer, prompt text, model rationale, or chain-of-thought --
 * governed identity, the reused confidence band, and the normalized decimal only.
 *
 * @param band exactly {@link io.ramals.learningplatform.assessment.DiagnosticConfidenceCalculatorV1}'s
 *     own output for this candidate's per-interaction evidence counts -- unmodified, unmodifiable
 * @param participates {@code band != INSUFFICIENT_EVIDENCE}; whether this candidate carries any of
 *     the normalized distribution
 * @param normalizedValue scale-4 {@link BigDecimal}, present iff {@code participates} and the
 *     owning result's status is {@code APPLICABLE}; {@code null} otherwise -- never {@code 0.0000}
 *     for a non-participating candidate (Amendment 1 §D)
 */
public record CandidateUncertainty(
    DiagnosticHypothesis hypothesis,
    DiagnosticConfidenceBand band,
    boolean participates,
    BigDecimal normalizedValue) {
}
