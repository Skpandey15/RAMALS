package io.ramals.learningplatform.diagnosticassessment;

import io.ramals.learningplatform.ai.DiagnosticAssessmentPort;
import io.ramals.learningplatform.ai.AiUnavailableException;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.grounding.GroundedContext;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import io.ramals.learningplatform.grounding.GroundingRetrievalService;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort.DecisionCorrelation;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort.PreParseRejection;
import io.ramals.learningplatform.grounding.ProposalGateReason;
import io.ramals.learningplatform.grounding.ProposalGateResult;
import io.ramals.learningplatform.grounding.ProposalType;
import io.ramals.learningplatform.execution.AiExecutionCommission;
import io.ramals.learningplatform.qualification.QualificationFault;
import io.ramals.learningplatform.execution.DiagnosticAssessmentExecutionRecorder;
import io.ramals.learningplatform.observability.CorrelationContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Orchestrates one diagnostic assessment: retrieve, commission, ask, gate, record (M2-T09/T10).
 *
 * <p>Spring owns every step that carries authority. It resolves the learner from the authenticated
 * subject, builds the context, decides whether the returned proposal is acceptable, and writes the
 * decision. The agent contributes the proposal and nothing else.
 *
 * <p>Deliberately updates no mastery, no progression and no evidence ledger. An accepted proposal
 * means the reading is grounded, consistent and within policy -- not that the platform has adopted
 * it as fact. M2-T10 evaluates that behavior; future orchestration may consume it under its own
 * deterministic policy. Inventing a domain table here would create exactly the authority this task
 * withholds.
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
  private final DiagnosticAssessmentExecutionRecorder executions;
  private final DiagnosticOutcomeWriter outcomes;
  private final Clock clock;

  public DiagnosticAssessmentService(
      GroundingRetrievalService grounding,
      DiagnosticAssessmentPort agent,
      DiagnosticAssessmentProposalGate gate,
      DiagnosticAssessmentExecutionRecorder executions,
      DiagnosticOutcomeWriter outcomes,
      Clock clock) {
    this.grounding = grounding;
    this.agent = agent;
    this.gate = gate;
    this.executions = executions;
    this.outcomes = outcomes;
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

    DiagnosticAssessmentRequest request =
        new DiagnosticAssessmentRequest(
            DiagnosticAssessmentRequest.CONTRACT_VERSION,
            interactionId,
            requestId,
            new Constraints(
                InteractionClass.INTERACTIVE_AI, (int) DEADLINE_MS, null, null, null),
            context);
    AiExecutionCommission commission = executions.commission(request);
    if (!commission.dispatchAllowed()) {
      throw new AiUnavailableException(
          "AI_EXECUTION_ALREADY_COMMISSIONED",
          "This diagnostic assessment request has already been commissioned.");
    }
    QualificationFault.pause(
        QualificationFault.Window.WORKFLOW_AFTER_DIAGNOSTIC_COMMISSION,
        null,
        requestId);

    Instant startedAt = clock.instant();
    AiProposalEnvelope envelope;
    try {
      envelope = agent.requestDiagnosticAssessment(request, DEADLINE_MS);
    } catch (AiUnavailableException failure) {
      executions.recordFailure(request, failure.code(), startedAt, clock.instant());
      throw failure;
    }
    Instant completedAt = clock.instant();

    // The gate is a pure function of the proposal and the context, so it runs here, in memory, with
    // no transaction open and nothing written yet.
    Evaluation evaluation = evaluate(envelope, context, interactionId, traceId, requestId);

    // Then one transaction for both rows. Recording the success first and the verdict second would
    // leave a window where a process death produces a SUCCEEDED execution with no decision, and that
    // state is unrecoverable: the ledger keeps a proposal digest, not the proposal.
    outcomes.commitSuccess(request, envelope, startedAt, completedAt, evaluation.decision());
    QualificationFault.pause(
        QualificationFault.Window.WORKFLOW_AFTER_DIAGNOSTIC_OUTCOME_COMMIT,
        null,
        requestId);

    logDecision(evaluation, interactionId, traceId);
    return evaluation.outcome();
  }

  /** Logged after the commit, so the record and the log line cannot disagree about what happened. */
  private static void logDecision(Evaluation evaluation, String interactionId, String traceId) {
    Outcome outcome = evaluation.outcome();
    boolean malformed = evaluation.parserReasonCode() != null;
    try (CorrelationContext.Scope ignored =
        CorrelationContext.withCorrelation(interactionId, traceId)) {
      var event = malformed || !outcome.accepted() ? LOGGER.atWarn() : LOGGER.atInfo();
      event
          .addKeyValue("operation", "ai.diagnosticAssessment.gate")
          .addKeyValue("outcome", outcome.accepted() ? "ACCEPTED" : "REJECTED")
          .addKeyValue("reasonCodes", outcome.reasons().stream().map(Enum::name).toList())
          .addKeyValue("parserReasonCode", evaluation.parserReasonCode())
          .addKeyValue("proposalId", outcome.proposalId())
          .addKeyValue("agentRunId", outcome.agentRunId())
          .addKeyValue("contextId", outcome.contextId())
          .log("diagnostic assessment gate decided");
    }
  }

  /**
   * Parses and gates one returned proposal, writing nothing.
   *
   * <p>Pure by design. Everything this produces is handed to a single transaction afterwards, so
   * that the execution success and its verdict share a fate; a version of this method that wrote as
   * it went is what created the SUCCEEDED-with-no-decision state in the first place.
   *
   * <p>Transport failure and business rejection are kept distinct, because conflating them destroys
   * the only signal that separates "the platform is broken" from "the platform worked and said no".
   * A call that never produced a proposal raises out of the client and never reaches here; a
   * proposal that arrived and failed the rules is a successful system outcome with a reason code and
   * a row.
   *
   * <p>A payload that cannot be read as the contract is a rejection, not an exception thrown away:
   * something was returned, and the record should say what happened to it. It is persisted in the
   * same transaction as the execution success, so a malformed proposal stays as auditable as a
   * gated one.
   */
  Evaluation evaluate(
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
      PreParseRejection rejection =
          new PreParseRejection(
              envelope.proposalId(),
              requestId,
              envelope.agentRunId(),
              context.contextId(),
              ProposalType.DIAGNOSTIC,
              ProposalGateReason.PROPOSAL_INVALID,
              malformed.reasonCode(),
              new DecisionCorrelation(interactionId, traceId));
      return new Evaluation(
          new DiagnosticOutcomeWriter.PendingDecision.PreParse(rejection),
          new Outcome(
              false,
              List.of(ProposalGateReason.PROPOSAL_INVALID),
              envelope.proposalId(),
              envelope.agentRunId(),
              context.contextId()),
          malformed.reasonCode());
    }

    ProposalGateResult result = gate.evaluate(proposal, context, clock.instant());
    return new Evaluation(
        new DiagnosticOutcomeWriter.PendingDecision.Gated(
            DiagnosticAssessmentProposalGate.asGroundingRequest(proposal),
            result,
            new DecisionCorrelation(interactionId, traceId)),
        new Outcome(
            result.accepted(),
            result.reasons(),
            proposal.proposalId(),
            proposal.agentRunId(),
            proposal.contextId()),
        null);
  }

  /**
   * What the gate concluded, ready to persist and to report.
   *
   * @param parserReasonCode set only when the payload could not be read as the contract
   */
  record Evaluation(
      DiagnosticOutcomeWriter.PendingDecision decision,
      Outcome outcome,
      String parserReasonCode) {}
}
