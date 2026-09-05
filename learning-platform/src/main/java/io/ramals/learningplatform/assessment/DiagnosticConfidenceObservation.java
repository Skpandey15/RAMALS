package io.ramals.learningplatform.assessment;

import java.time.Instant;
import java.util.UUID;

/**
 * DIAGNOSTIC_CONFIDENCE_V1 (M2-ADR-023 §2): one persisted {@code core.diagnostic_confidence_observation}
 * row, read back for audit -- the answer to "why is diagnostic confidence HIGH (or LOW, or
 * MODERATE) for this hypothesis, as of this observation". Never a diagnosis; see
 * {@link DiagnosticHypothesis} and {@link DiagnosticConfidenceBand}.
 *
 * @param triggeringProvenanceId the {@code core.diagnostic_probe_provenance} row (H5's own
 *     evidence, plus every earlier one for this hypothesis tuple) whose scoring produced this
 *     observation -- a reference, not a copy, of that row's own facts
 */
public record DiagnosticConfidenceObservation(
    UUID id,
    UUID learnerId,
    UUID sourceObjectiveId,
    UUID targetObjectiveId,
    ProbeRelationshipType relationshipType,
    UUID triggeringProvenanceId,
    int supportingCount,
    int contradictoryCount,
    int inconclusiveCount,
    DiagnosticConfidenceBand band,
    String policyVersion,
    Instant createdAt) {
}
