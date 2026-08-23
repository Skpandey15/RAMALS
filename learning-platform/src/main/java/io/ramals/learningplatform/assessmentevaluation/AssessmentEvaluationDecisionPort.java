package io.ramals.learningplatform.assessmentevaluation;

import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Decision;

/** Persistence boundary for one immutable, replay-safe evaluation decision. */
public interface AssessmentEvaluationDecisionPort {

  /**
   * Appends the decision or verifies that an idempotent replay is semantically equivalent.
   *
   * <p>A request identity reused for different decision content is a conflict, never a silent
   * no-op. Attempt-specific trace/correlation metadata is retained outside that semantic comparison.
   * Implementations must also require a matching successful ASSESSMENT ai_execution.
   */
  void append(EvaluationDecisionRecord record);

  /** Runtime-owned trace links and the deterministic result to retain. */
  record EvaluationDecisionRecord(
      String proposalId,
      String requestId,
      String agentRunId,
      String contextId,
      String answerEvidenceId,
      String answerVersion,
      String rubricVersion,
      String interactionId,
      String traceId,
      Decision decision,
      String parserReasonCode) {}
}
