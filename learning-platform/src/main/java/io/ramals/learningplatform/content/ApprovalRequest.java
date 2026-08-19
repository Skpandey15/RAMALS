package io.ramals.learningplatform.content;

import java.time.Instant;
import java.util.UUID;

public record ApprovalRequest(
    UUID id,
    UUID candidateId,
    int candidateRevision,
    ApprovalState state,
    String candidatePayloadJson,
    String proposalDigest,
    String sourceProposalId,
    String contractVersion,
    String agentType,
    String agentVersion,
    String modelRoute,
    String modelId,
    String promptVersion,
    String policyVersion,
    String engineVersion,
    String interactionId,
    String createdBy,
    Instant createdAt,
    Instant updatedAt,
    Instant expiresAt,
    String reviewerSubject,
    Instant reviewedAt,
    String reviewReason,
    UUID authoritativeItemVersionId) {
}
