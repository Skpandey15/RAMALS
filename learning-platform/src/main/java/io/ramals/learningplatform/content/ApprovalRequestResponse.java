package io.ramals.learningplatform.content;

import java.time.Instant;
import java.util.UUID;

public record ApprovalRequestResponse(
    UUID id, UUID candidateId, int candidateRevision, ApprovalState state, String candidatePayloadJson,
    String proposalDigest, String sourceProposalId, String policyVersion, String engineVersion,
    String interactionId, Instant createdAt, Instant expiresAt, String reviewerSubject,
    Instant reviewedAt, String reviewReason, UUID authoritativeItemVersionId) {
  public static ApprovalRequestResponse from(ApprovalRequest r) {
    return new ApprovalRequestResponse(r.id(), r.candidateId(), r.candidateRevision(), r.state(),
        r.candidatePayloadJson(), r.proposalDigest(), r.sourceProposalId(), r.policyVersion(),
        r.engineVersion(), r.interactionId(), r.createdAt(), r.expiresAt(), r.reviewerSubject(),
        r.reviewedAt(), r.reviewReason(), r.authoritativeItemVersionId());
  }
}
