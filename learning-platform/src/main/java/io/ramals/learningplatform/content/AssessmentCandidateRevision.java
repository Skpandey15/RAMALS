package io.ramals.learningplatform.content;

import java.time.Instant;
import java.util.UUID;

/** Immutable Spring-owned provenance record for one AI candidate revision. */
public record AssessmentCandidateRevision(
    UUID candidateId,
    int candidateRevision,
    String sourceProposalId,
    UUID assessmentVersionId,
    String itemCode,
    String skillCode,
    String objectiveCode,
    String itemType,
    String difficulty,
    String candidatePayloadJson,
    String proposalDigest,
    String trustState,
    String contractVersion,
    String agentType,
    String agentVersion,
    String modelRoute,
    String modelId,
    String modelIdUnavailableReason,
    String promptVersion,
    String interactionId,
    String createdBy,
    Instant createdAt,
    String idempotencyActor,
    String idempotencyKey,
    String idempotencyFingerprint) {
}
