package io.ramals.learningplatform.assessmentevaluation;

import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AssessmentEvaluationContext;
import io.ramals.learningplatform.ai.contract.AssessmentEvaluationRequest;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.EvaluationDecisionRecord;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationProposal.MalformedProposalException;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationProposal.RuntimeIdentity;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Decision;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.DeterministicCheck;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Reason;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parses, gates and immutably records one M2-T12 assessment-evaluation proposal.
 *
 * <p>This service deliberately has no EvidenceService or MasteryService dependency. Its output is
 * the permission boundary a later authoritative workflow may consume; rejected and manual-review
 * outcomes cannot reach either domain writer through this component.
 */
public class AssessmentEvaluationDecisionService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(AssessmentEvaluationDecisionService.class);

  private final EvaluationProposalGate gate;
  private final AssessmentEvaluationDecisionPort decisions;
  private final Clock clock;

  public AssessmentEvaluationDecisionService(
      EvaluationProposalGate gate,
      AssessmentEvaluationDecisionPort decisions,
      Clock clock) {
    this.gate = gate;
    this.decisions = decisions;
    this.clock = clock;
  }

  /**
   * Returns the persisted gate decision. Replays with identical content collapse to the first row.
   */
  @Transactional
  public Decision decide(
      AiProposalEnvelope envelope,
      AssessmentEvaluationRequest request,
      DeterministicCheck deterministicCheck) {
    requireTraceableInputs(envelope, request);
    DeterministicCheck comparison =
        deterministicCheck == null ? DeterministicCheck.notApplicable() : deterministicCheck;
    AssessmentEvaluationContext evaluation = request.evaluationContext();

    Decision decision;
    String parserReasonCode = null;
    if (envelope.agentType() != AgentType.ASSESSMENT) {
      parserReasonCode = "EVALUATION_ENVELOPE_AGENT_INVALID";
      decision = gate.rejectBeforeParse(Reason.ENVELOPE_AGENT_INVALID, comparison);
    } else if (envelope.trustLevel() != TrustLevel.NON_AUTHORITATIVE) {
      parserReasonCode = "EVALUATION_ENVELOPE_TRUST_INVALID";
      decision = gate.rejectBeforeParse(Reason.ENVELOPE_TRUST_INVALID, comparison);
    } else {
      try {
        RuntimeIdentity identity =
            new RuntimeIdentity(
                envelope.proposalId(),
                request.requestId(),
                envelope.agentRunId(),
                request.groundedContext().contextId(),
                evaluation.answerVersion(),
                evaluation.rubricVersion());
        AssessmentEvaluationProposal proposal =
            AssessmentEvaluationProposal.parse(envelope.proposal(), identity);
        decision = gate.evaluate(proposal, request, comparison, clock.instant());
      } catch (MalformedProposalException malformed) {
        parserReasonCode = malformed.reasonCode();
        decision = gate.rejectBeforeParse(Reason.PROPOSAL_INVALID, comparison);
      }
    }

    String traceId = MDC.get("traceId");
    if (!bounded(traceId)) {
      traceId = request.interactionId();
    }
    decisions.append(
        new EvaluationDecisionRecord(
            envelope.proposalId(),
            request.requestId(),
            envelope.agentRunId(),
            request.groundedContext().contextId(),
            evaluation.answerEvidenceId(),
            evaluation.answerVersion(),
            evaluation.rubricVersion(),
            request.interactionId(),
            traceId,
            decision,
            parserReasonCode));

    logDecision(envelope, request, decision, parserReasonCode, traceId);
    return decision;
  }

  private static void requireTraceableInputs(
      AiProposalEnvelope envelope, AssessmentEvaluationRequest request) {
    if (envelope == null
        || request == null
        || request.evaluationContext() == null
        || request.groundedContext() == null
        || !bounded(envelope.proposalId())
        || !bounded(envelope.agentRunId())
        || !bounded(request.interactionId())
        || !bounded(request.requestId())
        || !bounded(request.groundedContext().contextId())
        || !bounded(request.evaluationContext().answerEvidenceId())
        || !bounded(request.evaluationContext().answerVersion())
        || !bounded(request.evaluationContext().rubricVersion())) {
      throw new IllegalArgumentException(
          "evaluation decision requires complete runtime-owned trace identities");
    }
  }

  private static boolean bounded(String value) {
    return value != null && !value.isBlank() && value.length() <= 64;
  }

  private static void logDecision(
      AiProposalEnvelope envelope,
      AssessmentEvaluationRequest request,
      Decision decision,
      String parserReasonCode,
      String traceId) {
    LOGGER
        .atInfo()
        .addKeyValue("operation", "ai.assessmentEvaluation.gate")
        .addKeyValue("outcome", decision.outcome().name())
        .addKeyValue("reasonCodes", decision.reasons().stream().map(Enum::name).toList())
        .addKeyValue("parserReasonCode", parserReasonCode)
        .addKeyValue("proposalId", envelope.proposalId())
        .addKeyValue("requestId", request.requestId())
        .addKeyValue("agentRunId", envelope.agentRunId())
        .addKeyValue("contextId", request.groundedContext().contextId())
        .addKeyValue("answerVersion", request.evaluationContext().answerVersion())
        .addKeyValue("rubricVersion", request.evaluationContext().rubricVersion())
        .addKeyValue("interactionId", request.interactionId())
        .addKeyValue("traceId", traceId)
        .log("assessment evaluation gate decided");
  }
}
