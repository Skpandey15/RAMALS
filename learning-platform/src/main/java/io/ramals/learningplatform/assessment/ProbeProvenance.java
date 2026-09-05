package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * DIAGNOSTIC_SELECTION_V5 (M2-ADR-025): one persisted {@code core.diagnostic_probe_provenance} row,
 * read back for audit -- the answer to "why did this item enter this packet". Never a diagnosis;
 * see {@link DiagnosticHypothesis}.
 */
public record ProbeProvenance(
    UUID attemptId,
    UUID itemVersionId,
    UUID sourceAttemptId,
    UUID sourceItemVersionId,
    UUID sourceObjectiveId,
    ProbeRelationshipType relationshipType,
    UUID targetObjectiveId,
    UUID authorizingRelationshipId) {
}
