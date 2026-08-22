package io.ramals.learningplatform.grounding;

/** Persistence boundary for immutable proposal-gate outcomes. */
public interface ProposalGateDecisionPort {
  void appendDecision(ProposalGroundingRequest proposal, ProposalGateResult result);
}
