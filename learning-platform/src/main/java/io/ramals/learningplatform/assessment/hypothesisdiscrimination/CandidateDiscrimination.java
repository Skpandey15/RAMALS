package io.ramals.learningplatform.assessment.hypothesisdiscrimination;

import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One candidate probe's discrimination score in a {@link HypothesisDiscriminationResult}
 * (M2-ADR-034 Amendment 2 §D/§I/§J). Immutable.
 *
 * @param score scale-4 {@link BigDecimal} in {@code [0.0000, 1.0000]}, always present when the
 *     owning result's status is {@code SCORABLE} -- never {@code null}, unlike {@link
 *     io.ramals.learningplatform.assessment.hypothesisuncertainty.CandidateUncertainty}'s
 *     {@code normalizedValue}: every candidate probe is scoreable in the sense that a score is
 *     always mathematically defined (a non-scoreable probe's score is exactly {@code 0.0000} by
 *     construction, Amendment 2 §D)
 */
public record CandidateDiscrimination(
    UUID probeItemVersionId, DiagnosticHypothesis hypothesis, BigDecimal score) {
}
