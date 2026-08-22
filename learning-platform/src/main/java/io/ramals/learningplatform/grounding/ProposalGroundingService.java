package io.ramals.learningplatform.grounding;

import java.time.Clock;

/** Application boundary that guarantees every deterministic gate outcome is durably audited. */
public final class ProposalGroundingService {
  private final ProposalGroundingGate gate;
  private final ProposalGateDecisionPort decisions;
  private final Clock clock;

  public ProposalGroundingService(
      ProposalGroundingGate gate,
      ProposalGateDecisionPort decisions,
      Clock clock) {
    this.gate = gate;
    this.decisions = decisions;
    this.clock = clock;
  }

  public ProposalGateResult evaluate(
      ProposalGroundingRequest proposal,
      GroundedContext context) {
    if (proposal == null || proposal.proposalId() == null || proposal.requestId() == null
        || proposal.agentRunId() == null || proposal.contextId() == null
        || proposal.proposalType() == null) {
      throw new GroundingRetrievalException("PROPOSAL_AUDIT_IDENTITY_INVALID");
    }
    ProposalGateResult result = gate.evaluate(proposal, context, clock.instant());
    decisions.appendDecision(proposal, result);
    return result;
  }
}
