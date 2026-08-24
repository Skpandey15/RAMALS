package io.ramals.learningplatform.assessmentevaluation;

import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Decision;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

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

  /**
   * Reads the one accepted decision that is allowed to seed an authoritative workflow.
   *
   * <p>The lookup is deliberately by the persisted request identity only. A workflow trigger must
   * not be able to replace the decision's outcome, score, learner or target identifiers with values
   * carried by a redelivered message.
   */
  Optional<AcceptedEvaluationDecision> findAcceptedByRequestId(String requestId);

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
      String parserReasonCode,
      EvaluationTarget target) {

    /** Source-compatible constructor for decisions recorded before workflow target binding. */
    public EvaluationDecisionRecord(
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
        String parserReasonCode) {
      this(
          proposalId,
          requestId,
          agentRunId,
          contextId,
          answerEvidenceId,
          answerVersion,
          rubricVersion,
          interactionId,
          traceId,
          decision,
          parserReasonCode,
          null);
    }
  }

  /** Spring-owned learner and version-pinned assessment facts bound to a gate decision. */
  record EvaluationTarget(
      UUID learnerId,
      UUID skillId,
      UUID curriculumVersionId,
      UUID attemptId,
      UUID assessmentVersionId) {

    public boolean complete() {
      return learnerId != null
          && skillId != null
          && curriculumVersionId != null
          && attemptId != null
          && assessmentVersionId != null;
    }
  }

  /** The minimal immutable projection a workflow trigger is allowed to consume. */
  record AcceptedEvaluationDecision(
      String requestId,
      EvaluationTarget target,
      BigDecimal normalizedScore,
      String scorePolicyVersion,
      String interactionId,
      String traceId) {}
}
