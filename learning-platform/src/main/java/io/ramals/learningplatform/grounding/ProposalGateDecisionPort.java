package io.ramals.learningplatform.grounding;

/** Persistence boundary for immutable proposal-gate outcomes. */
public interface ProposalGateDecisionPort {

  /**
   * Records a proposal that reached the runtime but could not be parsed as the proposal contract.
   *
   * <p>This is deliberately not a {@link ProposalGroundingRequest}: constructing one would turn
   * malformed, model-controlled content into a valid domain object. Every field here is runtime or
   * envelope metadata that remains trustworthy even when the payload is not.
   */
  void appendPreParseRejection(PreParseRejection rejection);

  /**
   * Records a decision with the correlation identity it was made under.
   *
   * <p>The correlation is separate from the proposal rather than on it, because a proposal's
   * identifiers are the agent run's and the correlation is the learner action's. Merging them would
   * invite a caller to supply an interaction id the agent chose.
   */
  void appendDecision(
      ProposalGroundingRequest proposal, ProposalGateResult result, DecisionCorrelation correlation);

  /**
   * The decision already recorded for a request identity, if there is one.
   *
   * <p>Exists for crash recovery, not for reads on the happy path. Dispatch is at-most-once: an
   * execution commissions before the provider is called, so a worker that dies after the verdict was
   * persisted cannot obtain it again by asking the model. It has to be able to look it up, or a
   * successful diagnosis is thrown away.
   *
   * @param requestId the deterministic request identity the decision was recorded under
   * @param proposalType narrows the lookup to one proposal family
   */
  java.util.Optional<RecordedDecision> findDecision(String requestId, ProposalType proposalType);

  /** A durable gate outcome, reduced to what a recovering caller needs to resume from. */
  record RecordedDecision(
      String requestId,
      String proposalId,
      String agentRunId,
      String contextId,
      boolean accepted,
      java.util.List<String> reasonCodes) {}

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

  /** Trustworthy audit metadata for a proposal rejected before contract parsing completed. */
  record PreParseRejection(
      String proposalId,
      String requestId,
      String agentRunId,
      String contextId,
      ProposalType proposalType,
      ProposalGateReason publicReason,
      String parserReasonCode,
      DecisionCorrelation correlation) {}
}
