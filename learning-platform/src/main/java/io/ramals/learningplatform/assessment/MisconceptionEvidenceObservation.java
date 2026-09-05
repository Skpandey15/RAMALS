package io.ramals.learningplatform.assessment;

import java.time.Instant;
import java.util.UUID;

/**
 * Granular diagnostic runtime evidence capture (M2-ADR-027): one {@code core.misconception_
 * evidence_observation} row -- how one scored {@code SINGLE_CHOICE} response related to one
 * misconception, as of the moment that response was recorded. Never a diagnosis; a separate,
 * parallel evidence stream from H4b's {@code DiagnosticHypothesis}/H5's confidence observations.
 *
 * @param responseId the exact {@code core.assessment_response} row this evidence was computed
 *     from -- the provenance anchor (M2-ADR-027 §5), never a reconstructed attempt/item pair
 */
public record MisconceptionEvidenceObservation(
    UUID id,
    UUID responseId,
    UUID learnerId,
    UUID misconceptionId,
    MisconceptionEvidenceOutcome outcome,
    String policyVersion,
    Instant createdAt) {
}
