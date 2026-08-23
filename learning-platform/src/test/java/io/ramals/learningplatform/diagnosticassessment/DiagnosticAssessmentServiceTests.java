package io.ramals.learningplatform.diagnosticassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.ai.AiUnavailableException;
import io.ramals.learningplatform.ai.DiagnosticAssessmentPort;
import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.grounding.AuthorizedGroundingFacts;
import io.ramals.learningplatform.grounding.GroundedContext;
import io.ramals.learningplatform.grounding.GroundedContextFactory;
import io.ramals.learningplatform.grounding.GroundedContextValidator;
import io.ramals.learningplatform.grounding.GroundingRetrievalPolicy;
import io.ramals.learningplatform.grounding.GroundingRetrievalPort;
import io.ramals.learningplatform.grounding.GroundingRetrievalService;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort;
import io.ramals.learningplatform.grounding.ProposalGateDecisionPort.PreParseRejection;
import io.ramals.learningplatform.grounding.ProposalGateReason;
import io.ramals.learningplatform.grounding.ProposalGateResult;
import io.ramals.learningplatform.grounding.ProposalGroundingGate;
import io.ramals.learningplatform.grounding.ProposalGroundingPolicy;
import io.ramals.learningplatform.grounding.ProposalGroundingRequest;
import io.ramals.learningplatform.execution.AiExecution;
import io.ramals.learningplatform.execution.AiExecutionCommission;
import io.ramals.learningplatform.execution.DiagnosticAssessmentExecutionRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

/**
 * M2-T09 orchestration: what is recorded, what is replayed, and what a failure is allowed to mean.
 *
 * <p>Covers E08 and the transport/malformed paths. The distinction these exist to protect is that a
 * call that never produced a proposal and a proposal that failed the rules are different outcomes:
 * the first writes no decision and raises, the second writes a decision and returns.
 */
class DiagnosticAssessmentServiceTests {

  private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
  private static final UUID CURRICULUM = UUID.randomUUID();

  private RecordingDecisions decisions;
  private RecordingExecutions executions;
  private DiagnosticAssessmentProposalGate gate;

  @BeforeEach
  void setUp() {
    decisions = new RecordingDecisions();
    executions = new RecordingExecutions();
    gate =
        new DiagnosticAssessmentProposalGate(
            new ProposalGroundingGate(
                new GroundedContextValidator(JsonMapper.builder().findAndAddModules().build()),
                new ProposalGroundingPolicy()));
    MDC.put("interactionId", "interaction-1");
    MDC.put("traceId", "trace-1");
  }

  // -- E08: replay ------------------------------------------------------------------------------------

  @Test
  void e08_replayingTheSameAcceptedProposalYieldsOneLogicalDecision() {
    DiagnosticAssessmentService service = service(envelopeAccepting());
    GroundedContext context = DiagnosticAssessmentProposalGateTests.context();

    var first = evaluateAndCommit(service, envelopeAccepting(), context, "interaction-1", "trace-1", "r-1");
    var second = evaluateAndCommit(service, envelopeAccepting(), context, "interaction-1", "trace-1", "r-1");

    assertThat(first.accepted()).isTrue();
    assertThat(second).isEqualTo(first);
    // Two appends carrying one identity. The database's UNIQUE (proposal_id, policy_version)
    // collapses them; what this asserts is that the identity offered is stable, because a replay
    // that presented a fresh proposal id would defeat that constraint rather than exercise it.
    assertThat(decisions.appended).hasSize(2);
    assertThat(decisions.appended.get(0).proposalId())
        .isEqualTo(decisions.appended.get(1).proposalId());
    assertThat(decisions.results.get(0).accepted()).isEqualTo(decisions.results.get(1).accepted());
  }

  @Test
  void e08_replayingARejectedProposalStaysRejectedAndRecordsTheSameReasons() {
    DiagnosticAssessmentService service = service(envelopeRejecting());
    GroundedContext context = DiagnosticAssessmentProposalGateTests.context();

    var first = evaluateAndCommit(service, envelopeRejecting(), context, "interaction-1", "trace-1", "r-1");
    var second = evaluateAndCommit(service, envelopeRejecting(), context, "interaction-1", "trace-1", "r-1");

    assertThat(first.accepted()).isFalse();
    assertThat(second.reasons()).isEqualTo(first.reasons());
  }

  // -- correlation and provenance ------------------------------------------------------------------------

  @Test
  void theDecisionCarriesTheCorrelationIdentityItWasMadeUnder() {
    DiagnosticAssessmentService service = service(envelopeAccepting());

    evaluateAndCommit(service, 
        envelopeAccepting(),
        DiagnosticAssessmentProposalGateTests.context(),
        "interaction-1",
        "trace-1",
        "r-1");

    assertThat(decisions.correlations).hasSize(1);
    assertThat(decisions.correlations.get(0).interactionId()).isEqualTo("interaction-1");
    assertThat(decisions.correlations.get(0).traceId()).isEqualTo("trace-1");
  }

  @Test
  void theDecisionJoinsTheAgentRunAndTheContextItWasJudgedAgainst() {
    DiagnosticAssessmentService service = service(envelopeAccepting());

    var outcome =
        evaluateAndCommit(service, 
            envelopeAccepting(),
            DiagnosticAssessmentProposalGateTests.context(),
            "interaction-1",
            "trace-1",
            "r-1");

    ProposalGroundingRequest recorded = decisions.appended.get(0);
    assertThat(recorded.agentRunId()).isEqualTo("run-1").isEqualTo(outcome.agentRunId());
    assertThat(recorded.contextId()).isEqualTo(outcome.contextId());
    // requestId is the platform's, not the agent's: it is what joins this decision to ai_execution.
    assertThat(recorded.requestId()).isEqualTo("r-1");
  }

  // -- malformed and transport failures ----------------------------------------------------------------------

  @Test
  void aPayloadThatCannotBeReadAsTheContractIsRejectedRatherThanThrown() {
    DiagnosticAssessmentService service = service(envelopeAccepting());
    AiProposalEnvelope malformed = envelope(Map.of("diagnoses", "not-an-array"));

    var outcome =
        evaluateAndCommit(service, 
            malformed, DiagnosticAssessmentProposalGateTests.context(), "interaction-1", "trace-1",
            "r-1");

    assertThat(outcome.accepted()).isFalse();
    assertThat(outcome.reasons()).containsExactly(ProposalGateReason.PROPOSAL_INVALID);
    assertThat(decisions.preParseRejections).hasSize(1);
    PreParseRejection rejection = decisions.preParseRejections.get(0);
    assertThat(rejection.proposalId()).isEqualTo("p-1");
    assertThat(rejection.agentRunId()).isEqualTo("run-1");
    assertThat(rejection.requestId()).isEqualTo("r-1");
    assertThat(rejection.contextId()).isEqualTo("ctx-diagnostic-1");
    assertThat(rejection.publicReason()).isEqualTo(ProposalGateReason.PROPOSAL_INVALID);
    assertThat(rejection.parserReasonCode()).isEqualTo("PROPOSAL_DIAGNOSES_INVALID");
    assertThat(rejection.correlation().interactionId()).isEqualTo("interaction-1");
    assertThat(rejection.correlation().traceId()).isEqualTo("trace-1");
    assertThat(decisions.appended).isEmpty();
  }

  @Test
  void replayingTheSameMalformedLogicalRequestPreservesOneStableAuditIdentity() {
    DiagnosticAssessmentService service = service(envelopeAccepting());
    AiProposalEnvelope malformed = envelope(Map.of("diagnoses", "not-an-array"));
    GroundedContext context = DiagnosticAssessmentProposalGateTests.context();

    var first = evaluateAndCommit(service, malformed, context, "interaction-1", "trace-1", "r-1");
    var replay = evaluateAndCommit(service, malformed, context, "interaction-1", "trace-1", "r-1");

    assertThat(replay).isEqualTo(first);
    assertThat(decisions.preParseRejections).hasSize(2);
    assertThat(decisions.preParseRejections)
        .extracting(PreParseRejection::proposalId)
        .containsOnly("p-1");
    assertThat(decisions.preParseRejections)
        .extracting(PreParseRejection::parserReasonCode)
        .containsOnly("PROPOSAL_DIAGNOSES_INVALID");
  }

  @Test
  void anEmptyProposalPayloadIsRejected() {
    DiagnosticAssessmentService service = service(envelopeAccepting());

    var outcome =
        evaluateAndCommit(service, 
            envelope(Map.of()), DiagnosticAssessmentProposalGateTests.context(), "interaction-1",
            "trace-1", "r-1");

    assertThat(outcome.accepted()).isFalse();
  }

  @Test
  void anUnknownClassificationIsRejectedRatherThanGuessedAt() {
    DiagnosticAssessmentService service = service(envelopeAccepting());
    AiProposalEnvelope unknown =
        envelope(
            DiagnosticAssessmentProposalGateTests.payload(
                "0.8000",
                List.of(
                    Map.of(
                        "skillCode", "offsets",
                        "classification", "PROBABLY_FINE",
                        "reason", "invented",
                        "evidenceIds", List.of("e-1")))));

    var outcome =
        evaluateAndCommit(service, 
            unknown, DiagnosticAssessmentProposalGateTests.context(), "interaction-1", "trace-1",
            "r-1");

    assertThat(outcome.accepted()).isFalse();
  }

  @Test
  void aProviderTimeoutRaisesAndWritesNoDecision() {
    // Transport failure is not business rejection. A decision row would claim the platform
    // considered a proposal it never received.
    DiagnosticAssessmentService service =
        new DiagnosticAssessmentService(
            retrieval(),
            (request, deadlineMillis) -> {
              throw new AiUnavailableException(
                  "AI_DEADLINE_EXCEEDED", "no time remained for diagnostic assessment");
            },
            gate,
            executions,
            recordingWriter(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.assess("subject-1", CURRICULUM, "r-1"))
        .isInstanceOf(AiUnavailableException.class);
    assertThat(decisions.appended).isEmpty();
    assertThat(executions.failureCodes).containsExactly("AI_DEADLINE_EXCEEDED");
    assertThat(executions.successfulRequests).isEmpty();
  }

  @Test
  void theRequestCarriesTheContextSpringBuiltAndNoLearnerIdentifierOfItsOwn() {
    List<DiagnosticAssessmentRequest> sent = new ArrayList<>();
    DiagnosticAssessmentService service =
        new DiagnosticAssessmentService(
            retrieval(),
            (request, deadlineMillis) -> {
              sent.add(request);
              return envelopeAccepting(request.groundedContext().contextId());
            },
            gate,
            executions,
            recordingWriter(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    service.assess("subject-1", CURRICULUM, "r-1");

    assertThat(sent).hasSize(1);
    DiagnosticAssessmentRequest request = sent.get(0);
    assertThat(request.groundedContext()).isNotNull();
    assertThat(request.contractVersion()).isEqualTo(DiagnosticAssessmentRequest.CONTRACT_VERSION);
    // The only learner identity that crosses is the opaque reference inside the context.
    assertThat(request.groundedContext().learnerRef()).isNotBlank();
    assertThat(executions.successfulRequests).containsExactly(request);
    assertThat(executions.failureCodes).isEmpty();
  }

  @Test
  void aDuplicateLogicalRequestIsRefusedBeforeCallingTheAgentOrWritingAnotherDecision() {
    List<DiagnosticAssessmentRequest> sent = new ArrayList<>();
    executions.commission = AiExecutionCommission.inProgress();
    DiagnosticAssessmentService service =
        new DiagnosticAssessmentService(
            retrieval(),
            (request, deadlineMillis) -> {
              sent.add(request);
              return envelopeAccepting();
            },
            gate,
            executions,
            recordingWriter(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.assess("subject-1", CURRICULUM, "r-1"))
        .isInstanceOf(AiUnavailableException.class)
        .extracting("code")
        .isEqualTo("AI_EXECUTION_ALREADY_COMMISSIONED");
    assertThat(sent).isEmpty();
    assertThat(decisions.appended).isEmpty();
    assertThat(executions.successfulRequests).isEmpty();
    assertThat(executions.failureCodes).isEmpty();
  }

  // -- fixtures ---------------------------------------------------------------------------------------------

  private DiagnosticAssessmentService service(AiProposalEnvelope envelope) {
    return new DiagnosticAssessmentService(
        retrieval(), (request, deadlineMillis) -> envelope, gate,
        executions,
        recordingWriter(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /**
   * Writes both rows through the same fakes production writes through.
   *
   * <p>Atomicity itself is a database property and is asserted against PostgreSQL; what this proves
   * is that the service still hands both writes to one place.
   */
  private DiagnosticOutcomeWriter recordingWriter() {
    return (request, envelope, startedAt, completedAt, decision) -> {
      executions.recordSuccess(request, envelope, startedAt, completedAt);
      switch (decision) {
        case DiagnosticOutcomeWriter.PendingDecision.Gated gated ->
            decisions.appendDecision(gated.proposal(), gated.result(), gated.correlation());
        case DiagnosticOutcomeWriter.PendingDecision.PreParse preParse ->
            decisions.appendPreParseRejection(preParse.rejection());
      }
    };
  }

  private static DiagnosticAssessmentRequest diagnosticRequest(
      String requestId, String interactionId, GroundedContext context) {
    return new DiagnosticAssessmentRequest(
        DiagnosticAssessmentRequest.CONTRACT_VERSION,
        interactionId,
        requestId,
        new Constraints(InteractionClass.INTERACTIVE_AI, 12_000, null, null, null),
        context);
  }

  /** Mirrors the production sequence: evaluate in memory, then commit both rows together. */
  private DiagnosticAssessmentService.Outcome evaluateAndCommit(
      DiagnosticAssessmentService service,
      AiProposalEnvelope envelope,
      GroundedContext context,
      String interactionId,
      String traceId,
      String requestId) {
    var evaluation = service.evaluate(envelope, context, interactionId, traceId, requestId);
    recordingWriter()
        .commitSuccess(diagnosticRequest(requestId, interactionId, context), envelope, NOW, NOW,
            evaluation.decision());
    return evaluation.outcome();
  }

  /** A retrieval service over a fake port, so a learner reference is never caller-supplied. */
  private GroundingRetrievalService retrieval() {
    GroundingRetrievalPort port =
        new GroundingRetrievalPort() {
          @Override
          public Optional<AuthorizedGroundingFacts> retrieve(
              String authenticatedSubject, UUID curriculumVersionId, Instant asOf,
              GroundingRetrievalPolicy policy) {
            return Optional.of(
                new AuthorizedGroundingFacts(
                    UUID.randomUUID(), DiagnosticAssessmentProposalGateTests.context().items()));
          }

          @Override
          public void appendRetrievalRecord(GroundedContext context, UUID learnerId) {
            // The record is asserted by the grounding tests; this fake only needs to not fail.
          }
        };
    return new GroundingRetrievalService(
        port,
        new GroundedContextFactory(
            new GroundedContextValidator(JsonMapper.builder().findAndAddModules().build())),
        GroundingRetrievalPolicy.V1,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static AiProposalEnvelope envelopeAccepting() {
    return envelopeAccepting("ctx-diagnostic-1");
  }

  private static AiProposalEnvelope envelopeAccepting(String ignoredContextId) {
    return envelope(
        DiagnosticAssessmentProposalGateTests.payload(
            "0.8000",
            List.of(
                Map.of(
                    "skillCode", "offsets",
                    "classification", "WEAK",
                    "reason", "Repeated incorrect answers involving committed offsets.",
                    "evidenceIds", List.of("e-1")))));
  }

  private static AiProposalEnvelope envelopeRejecting() {
    return envelope(
        DiagnosticAssessmentProposalGateTests.payload(
            "0.8000",
            List.of(
                Map.of(
                    "skillCode", "offsets",
                    "classification", "STRONG",
                    "reason", "Asserted strongly on one observation.",
                    "evidenceIds", List.of("e-1")))));
  }

  private static AiProposalEnvelope envelope(Map<String, Object> proposal) {
    return new AiProposalEnvelope(
        "1.0", "p-1", AgentType.DIAGNOSTIC, "DIAGNOSTIC_ASSESSMENT_AGENT_V1", "run-1",
        "DIAGNOSTIC_ASSESSMENT", "DIAGNOSTIC_ASSESSMENT_PROMPT_V1", "diagnostic-default",
        TrustLevel.NON_AUTHORITATIVE, null, null, proposal, null, null);
  }

  /** Records what the service asked to persist, without a database. */
  private static final class RecordingDecisions implements ProposalGateDecisionPort {

    @Override
    public java.util.Optional<RecordedDecision> findDecision(
        String requestId, io.ramals.learningplatform.grounding.ProposalType proposalType) {
      // These tests exercise first-run behaviour, where no prior decision exists.
      return java.util.Optional.empty();
    }

    private final List<ProposalGroundingRequest> appended = new ArrayList<>();
    private final List<ProposalGateResult> results = new ArrayList<>();
    private final List<DecisionCorrelation> correlations = new ArrayList<>();
    private final List<PreParseRejection> preParseRejections = new ArrayList<>();

    @Override
    public void appendPreParseRejection(PreParseRejection rejection) {
      preParseRejections.add(rejection);
    }

    @Override
    public void appendDecision(
        ProposalGroundingRequest proposal,
        ProposalGateResult result,
        DecisionCorrelation correlation) {
      appended.add(proposal);
      results.add(result);
      correlations.add(correlation);
    }
  }

  private static final class RecordingExecutions
      implements DiagnosticAssessmentExecutionRecorder {
    private AiExecutionCommission commission = AiExecutionCommission.claimed();
    private final List<DiagnosticAssessmentRequest> successfulRequests = new ArrayList<>();
    private final List<String> failureCodes = new ArrayList<>();

    @Override
    public AiExecutionCommission commission(DiagnosticAssessmentRequest request) {
      return commission;
    }

    @Override
    public AiExecution recordSuccess(
        DiagnosticAssessmentRequest request,
        AiProposalEnvelope proposal,
        Instant startedAt,
        Instant completedAt) {
      successfulRequests.add(request);
      return null;
    }

    @Override
    public AiExecution recordFailure(
        DiagnosticAssessmentRequest request,
        String errorCode,
        Instant startedAt,
        Instant completedAt) {
      failureCodes.add(errorCode);
      return null;
    }
  }
}
