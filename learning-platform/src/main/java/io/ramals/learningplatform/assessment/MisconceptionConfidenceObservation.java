package io.ramals.learningplatform.assessment;

import java.time.Instant;
import java.util.UUID;

/**
 * M2-ADR-028: one persisted {@code core.misconception_confidence_observation} row -- the answer to
 * "how strongly does accumulated MISCONCEPTION_EVIDENCE_V1 evidence support misconception M for
 * learner L, as of the submission that computed this snapshot". Never a diagnosis, never a
 * probability, never mastery or progression authority; a separate, independent stream from H5's own
 * {@link DiagnosticConfidenceObservation}, sharing only the calculator that produced {@link #band}.
 *
 * @param attemptId the submission (M2-ADR-028's own snapshot-event identity, together with {@link
 *     #misconceptionId}) that recomputed this snapshot -- not the confidence identity itself, which
 *     is {@code (learnerId, misconceptionId)} and may span many attempts
 */
public record MisconceptionConfidenceObservation(
    UUID id,
    UUID attemptId,
    UUID learnerId,
    UUID misconceptionId,
    int supportingCount,
    int contradictoryCount,
    int inconclusiveCount,
    DiagnosticConfidenceBand band,
    String policyVersion,
    Instant createdAt) {
}
