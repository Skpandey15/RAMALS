package io.ramals.learningplatform.diagnosticassessment;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort.DecisionCorrelation;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort.PreParseRejection;
import io.ramals.learningplatform.grounding.ProposalGateResult;
import io.ramals.learningplatform.grounding.ProposalGroundingRequest;
import java.time.Instant;

/**
 * Commits one successful diagnostic execution together with the verdict on it.
 *
 * <p>Both writes or neither. Recording the execution first and the decision second leaves a window
 * in which a process death produces a SUCCEEDED execution with no verdict -- and that state cannot
 * be recovered, because the execution ledger keeps a proposal digest rather than the proposal, so
 * there is nothing left to re-gate. Closing the window is cheaper than making the state recoverable,
 * and it avoids persisting model output for no reason other than recovery.
 *
 * <p>The provider call happens before any of this and outside every transaction. The gate is a pure
 * function of the proposal and the context, so it is evaluated in memory first and only its result
 * reaches the database.
 */
public interface DiagnosticOutcomeWriter {

  /**
   * Writes the success record and its decision in a single transaction.
   *
   * @param startedAt when the provider call began; already elapsed by the time this is called
   * @param completedAt when the provider call returned
   */
  void commitSuccess(
      DiagnosticAssessmentRequest request,
      AiProposalEnvelope envelope,
      Instant startedAt,
      Instant completedAt,
      PendingDecision decision);

  /**
   * The verdict awaiting persistence.
   *
   * <p>Two shapes, because a payload that could not be read as the contract is still an outcome
   * worth auditing and cannot be expressed as a gate result over a proposal that never parsed.
   */
  sealed interface PendingDecision {

    /** A proposal that parsed, and what the gate ruled about it. Accepted or rejected alike. */
    record Gated(
        ProposalGroundingRequest proposal,
        ProposalGateResult result,
        DecisionCorrelation correlation)
        implements PendingDecision {}

    /** A payload that could not be read as the contract. Durably auditable, never discarded. */
    record PreParse(PreParseRejection rejection) implements PendingDecision {}
  }
}
