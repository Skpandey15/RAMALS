package io.ramals.learningplatform.grounding;

/** Persistence boundary for immutable proposal-gate outcomes. */
public interface ProposalGateDecisionPort {

  /**
   * Records a decision with the correlation identity it was made under.
   *
   * <p>The correlation is separate from the proposal rather than on it, because a proposal's
   * identifiers are the agent run's and the correlation is the learner action's. Merging them would
   * invite a caller to supply an interaction id the agent chose.
   */
  void appendDecision(
      ProposalGroundingRequest proposal, ProposalGateResult result, DecisionCorrelation correlation);

  /** M2-T07 callers that carry no correlation. Retained so their behaviour is unchanged. */
  default void appendDecision(ProposalGroundingRequest proposal, ProposalGateResult result) {
    appendDecision(proposal, result, DecisionCorrelation.absent());
  }

  /** The learner action and distributed trace a decision belongs to. */
  record DecisionCorrelation(String interactionId, String traceId) {
    public static DecisionCorrelation absent() {
      return new DecisionCorrelation(null, null);
    }
  }
}
