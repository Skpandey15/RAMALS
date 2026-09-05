package io.ramals.learningplatform.assessment;

import java.util.UUID;

/**
 * DIAGNOSTIC_SELECTION_V5 (M2-ADR-025): one persisted {@code core.diagnostic_probe_provenance} row,
 * read back for audit -- the answer to "why did this item enter this packet". Never a diagnosis;
 * see {@link DiagnosticHypothesis}.
 *
 * @param id the row's own primary key -- H5 references this as
 *     {@code diagnostic_confidence_observation.triggering_provenance_id}, so a confidence
 *     observation always names the exact probe response that produced it.
 */
public record ProbeProvenance(
    UUID id,
    UUID attemptId,
    UUID itemVersionId,
    UUID sourceAttemptId,
    UUID sourceItemVersionId,
    UUID sourceObjectiveId,
    ProbeRelationshipType relationshipType,
    UUID targetObjectiveId,
    UUID authorizingRelationshipId) {
}
