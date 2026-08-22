package io.ramals.learningplatform.diagnosticassessment;

import io.ramals.learningplatform.ai.DiagnosticAssessmentPort;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.grounding.GroundedContext;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import io.ramals.learningplatform.grounding.GroundingRetrievalService;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort.DecisionCorrelation;
import io.ramals.learningplatform.grounding.ProposalGateReason;
import io.ramals.learningplatform.grounding.ProposalGateResult;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Orchestrates one diagnostic assessment: retrieve, ask, gate, record (M2-T09).
 *
 * <p>Spring owns every step that carries authority. It resolves the learner from the authenticated
 * subject, builds the context, decides whether the returned proposal is acceptable, and writes the
 * decision. The agent contributes the proposal and nothing else.
 *
 * <p>Deliberately updates no mastery, no progression and no evidence ledger. An accepted proposal
 * means the reading is grounded, consistent and within policy -- not that the platform has adopted it
 * as fact. What consumes an accepted diagnosis is M2-T10 and M2-T14; inventing a domain table here
 * to hold it would create exactly the authority this task withholds.
 */
public class DiagnosticAssessmentService {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiagnosticAssessmentService.class);

  /** The diagnostic budget. The caller's remaining time still binds when it is shorter. */
  static final long DEADLINE_MS = 12_000;

  /**
   * Sources a diagnostic reading cannot be built without.
   *
   * <p>The same set the grounding policy requires for a DIAGNOSTIC proposal. Stated here because
   * retrieval needs it before any proposal exists, and asserted equal to the policy by test: two
   * copies of one rule in two processes is one copy too many, and the test is what keeps them
   * honest.
   */
  static final Set<SourceType> REQUIRED_SOURCES =
      Set.of(SourceType.LEARNER_EVIDENCE, SourceType.MASTERY, SourceType.CURRICULUM_POLICY);

  private final GroundingRetrievalService grounding;
  private final DiagnosticAssessmentPort agent;
  private final DiagnosticAssessmentProposalGate gate;
  private final ProposalGateDecisionPort decisions;
  private final Clock clock;

  public DiagnosticAssessmentService(
      GroundingRetrievalService grounding,
      DiagnosticAssessmentPort agent,
      DiagnosticAssessmentProposalGate gate,
      ProposalGateDecisionPort decisions,
      Clock clock) {
    this.grounding = grounding;
    this.agent = agent;
    this.gate = gate;
    this.decisions = decisions;
    this.clock = clock;
  }

  /** The decision, and the identity needed to find everything behind it. */
  public record Outcome(
      boolean accepted,
      List<ProposalGateReason> reasons,
      String proposalId,
      String agentRunId,
      String contextId) {}

  /**
   * Runs the flow for one authenticated learner.
   *
   * @param authenticatedSubject the OIDC subject. The learner is resolved from it inside retrieval,
   *     and no caller-supplied learner identifier is accepted anywhere on this path -- which is what
   *     makes constructing another learner's context unreachable rather than merely checked.
   */
  public Outcome assess(String authenticatedSubject, UUID curriculumVersionId, String requestId) {
    GroundedContext context =
        grounding.retrieve(authenticatedSubject, curriculumVersionId, REQUIRED_SOURCES);

    String interactionId = MDC.get("interactionId");
    String traceId = MDC.get("traceId");

    AiProposalEnvelope envelope =
        agent.requestDiagnosticAssessment(
            new DiagnosticAssessmentRequest(
                DiagnosticAssessmentRequest.CONTRACT_VERSION,
                interactionId,
                requestId,
                new Constraints(
                    InteractionClass.INTERACTIVE_AI, (int) DEADLINE_MS, null, null, null),
                context),
            DEADLINE_MS);

    return decide(envelope, context, interactionId, traceId, requestId);
  }

  /**
   * Parses, gates and records one returned proposal.
   *
   * <p>Transport failure and business rejection are kept distinct, because conflating them destroys
   * the only signal that separates "the platform is broken" from "the platform worked and said no".
   * A call that never produced a proposal raises out of the client and writes no decision; a proposal
   * that arrived and failed the rules is a successful system outcome with a reason code and a row.
   *
   * <p>A payload that cannot be read as the contract is a rejection, not an exception thrown away:
   * something was returned, and the record should say what happened to it.
   */
  Outcome decide(
      AiProposalEnvelope envelope,
      GroundedContext context,
      String interactionId,
      String traceId,
      String requestId) {
    DiagnosticAssessmentProposal proposal;
    try {
      proposal =
          DiagnosticAssessmentProposal.parse(
              envelope.proposal(),
              envelope.proposalId(),
              requestId,
              envelope.agentRunId(),
              context.contextId());
    } catch (DiagnosticAssessmentProposal.MalformedProposalException malformed) {
      LOGGER
          .atWarn()
          .addKeyValue("operation", "ai.diagnosticAssessment.gate")
          .addKeyValue("outcome", "REJECTED")
          .addKeyValue("reasonCode", malformed.reasonCode())
          .addKeyValue("proposalId", envelope.proposalId())
          .addKeyValue("agentRunId", envelope.agentRunId())
          .addKeyValue("interactionId", interactionId)
          .log("diagnostic assessment proposal could not be read as its contract");
      return new Outcome(
          false,
          List.of(ProposalGateReason.PROPOSAL_INVALID),
          envelope.proposalId(),
          envelope.agentRunId(),
          context.contextId());
    }

    ProposalGateResult result = gate.evaluate(proposal, context, clock.instant());
    decisions.appendDecision(
        DiagnosticAssessmentProposalGate.asGroundingRequest(proposal),
        result,
        new DecisionCorrelation(interactionId, traceId));

    LOGGER
        .atInfo()
        .addKeyValue("operation", "ai.diagnosticAssessment.gate")
        .addKeyValue("outcome", result.accepted() ? "ACCEPTED" : "REJECTED")
        .addKeyValue("reasonCodes", result.reasons().stream().map(Enum::name).toList())
        .addKeyValue("proposalId", proposal.proposalId())
        .addKeyValue("agentRunId", proposal.agentRunId())
        .addKeyValue("contextId", proposal.contextId())
        .addKeyValue("interactionId", interactionId)
        .addKeyValue("traceId", traceId)
        .log("diagnostic assessment gate decided");

    return new Outcome(
        result.accepted(),
        result.reasons(),
        proposal.proposalId(),
        proposal.agentRunId(),
        proposal.contextId());
  }
}
