package io.ramals.learningplatform.grounding;

import java.math.BigDecimal;
import java.util.List;

/** Proposal metadata normalized by a proposal-specific schema/semantic adapter before gating. */
public record ProposalGroundingRequest(
    String contractVersion,
    String proposalId,
    String requestId,
    String agentRunId,
    String contextId,
    ProposalType proposalType,
    BigDecimal confidence,
    List<GroundedClaim> claims) {

  public ProposalGroundingRequest {
    claims = claims == null ? List.of() : List.copyOf(claims);
  }
}
