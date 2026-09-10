package io.ramals.learningplatform.diagnosticassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.ai.AiUnavailableException;
import io.ramals.learningplatform.ai.DelegatedAiContextMinter;
import io.ramals.learningplatform.ai.DiagnosticProbePort;
import io.ramals.learningplatform.ai.DomainContextAssembler;
import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.DomainContext;
import io.ramals.learningplatform.ai.contract.DomainType;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Step 3 orchestration (M2-ADR-032): the orchestrator solicits one bounded recommendation and
 * routes it through the unchanged PR #266 gate, and does nothing else.
 *
 * <ul>
 *   <li>a valid AI proposal reaches the gate and is recorded ACCEPTED;
 *   <li>every AI-plane failure (unavailable, timeout, transport, MCP, empty) becomes a {@code null}
 *       envelope and resolves to ABSENT with nothing written;
 *   <li>a returned-but-non-conforming payload is MALFORMED; a below-gate payload is REJECTED;
 *   <li>no groundable H6/H7 evidence, or no time left, means no model call at all;
 *   <li>the orchestrator never invokes diagnostic selection or probe execution.
 * </ul>
 */
class DiagnosticProbeRecommendationOrchestratorTests {

  private static final UUID LEARNER = UUID.fromString("01900000-0000-7000-8000-0000000000c1");
  private static final UUID MC = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID OBJECTIVE = UUID.fromString("01900000-0000-7000-8000-0000000000b1");
  private static final UUID EV_1 = UUID.fromString("01900000-0000-7000-8000-0000000000e1");
  private static final UUID EV_2 = UUID.fromString("01900000-0000-7000-8000-0000000000e2");
  private static final UUID UNAUTHORISED = UUID.fromString("01900000-0000-7000-8000-0000000000ff");
  private static final String DOMAIN = "KAFKA";
  private static final String INTERACTION = "int-1";

  // -- an in-memory append-only decision store, matching the PR #266 port contract -----------------

  private static final class RecordingDecisionPort implements DiagnosticProbeProposalDecisionPort {
    final List<DiagnosticProbeProposalDecision> appended = new ArrayList<>();

    @Override
    public void append(DiagnosticProbeProposalDecision decision) {
      appended.add(decision);
    }

    @Override
    public Optional<RecordedProbeDecision> findByProposalId(String proposalId) {
      return appended.stream()
          .filter(d -> d.proposalId().equals(proposalId))
          .findFirst()
          .map(
              d ->
                  new RecordedProbeDecision(
                      d.proposalId(),
                      d.interactionId(),
                      d.accepted(),
                      d.reasonCodes(),
                      d.parserReasonCode(),
                      d.policyVersion()));
    }
  }

  private final DiagnosticProbeTargetPort targetPort =
      new DiagnosticProbeTargetPort() {
        @Override
        public Optional<ResolvedMisconception> findMisconception(UUID id) {
          return MC.equals(id)
              ? Optional.of(new ResolvedMisconception(id, true, OBJECTIVE, null))
              : Optional.empty();
        }

        @Override
        public Optional<DiagnosticProbeProposal.TargetNode.Kind> findDiagnosticNodeKind(UUID id) {
          return Optional.empty();
        }
      };

  private final RecordingDecisionPort decisions = new RecordingDecisionPort();
  private final DiagnosticProbeProposalService proposalService =
      new DiagnosticProbeProposalService(new DiagnosticProbeProposalGate(), targetPort, decisions);

  private final DiagnosticProbeContextAssembler assembler =
      mock(DiagnosticProbeContextAssembler.class);
  private final DomainContextAssembler domainContextAssembler = mock(DomainContextAssembler.class);
  private final DiagnosticProbePort probePort = mock(DiagnosticProbePort.class);

  private final DiagnosticProbeRecommendationOrchestrator orchestrator =
      new DiagnosticProbeRecommendationOrchestrator(
          assembler,
          domainContextAssembler,
          probePort,
          proposalService,
          new DelegatedAiContextMinter(Optional.empty()));

  private static DiagnosticProbeProposalContext context() {
    return new DiagnosticProbeProposalContext(
        LEARNER,
        INTERACTION,
        DOMAIN,
        java.util.Set.of(MC),
        java.util.Set.of(EV_1.toString(), EV_2.toString()),
        java.util.Set.of(),
        java.util.Set.of("diagnostics.current-domain-report"));
  }

  private void givenGroundableContext() {
    when(assembler.assemble(eq(LEARNER), eq(DOMAIN), eq(INTERACTION)))
        .thenReturn(
            new DiagnosticProbeContextAssembler.Assembled(
                context(),
                true,
                io.ramals.learningplatform.assessment.DiagnosticReport.DiagnosticDataStatus
                    .HAS_EVIDENCE));
    when(domainContextAssembler.forDomain(eq(DOMAIN)))
        .thenReturn(Optional.of(new DomainContext(DOMAIN, DomainType.TECHNOLOGY, "v1")));
  }

  private static Map<String, Object> validPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("contractVersion", "1.0");
    payload.put("proposalType", "DIAGNOSTIC_PROBE_CANDIDATE");
    payload.put("proposalId", "prop-1");
    payload.put("requestId", "req-1");
    payload.put("agentRunId", "run-1");
    payload.put("interactionId", INTERACTION);
    payload.put("domain", DOMAIN);
    payload.put("targetMisconceptionId", MC.toString());
    payload.put(
        "targetNode",
        new LinkedHashMap<>(Map.of("kind", "LEARNING_OBJECTIVE", "id", OBJECTIVE.toString())));
    payload.put("probeIntent", "COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE");
    payload.put("candidateProbeRef", null);
    payload.put("evidenceRefs", List.of(EV_1.toString()));
    payload.put("rationale", "Additional discriminating evidence would help narrow the ambiguity.");
    return payload;
  }

  private static AiProposalEnvelope envelope(Map<String, Object> proposal) {
    return new AiProposalEnvelope(
        "1.0",
        "prop-1",
        AgentType.DIAGNOSTIC,
        "v1",
        "run-1",
        "DIAGNOSTIC_PROBE_CANDIDATE",
        "DIAGNOSTIC_PROBE_PROMPT_V1",
        "diagnostic-default",
        "anthropic",
        "claude-sonnet-5",
        "ROUTE_TABLE_V1",
        TrustLevel.NON_AUTHORITATIVE,
        null,
        List.of(),
        proposal,
        null,
        null);
  }

  private void whenModelReturns(Map<String, Object> proposal) {
    when(probePort.requestProbeRecommendation(any(), anyLong(), any()))
        .thenReturn(envelope(proposal));
  }

  private DiagnosticProbeProposalService.Outcome recommend() {
    return orchestrator.recommendNextProbe(LEARNER, DOMAIN, INTERACTION, "req-1", 12_000);
  }

  // -- 1: the groundable, valid path --------------------------------------------------------------

  @Test
  @DisplayName("1 -- a valid AI proposal reaches the unchanged gate and is recorded ACCEPTED")
  void validProposalIsAccepted() {
    givenGroundableContext();
    whenModelReturns(validPayload());

    DiagnosticProbeProposalService.Outcome outcome = recommend();

    assertThat(outcome.status())
        .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ACCEPTED);
    assertThat(decisions.appended).hasSize(1);
    assertThat(decisions.appended.get(0).accepted()).isTrue();
  }

  @Test
  @DisplayName("the request envelope carries the authoritative learner, interaction and resolved "
      + "domain -- never a value from an AI payload")
  void requestEnvelopeIsBuiltFromAuthoritativeContext() {
    givenGroundableContext();
    whenModelReturns(validPayload());

    recommend();

    ArgumentCaptor<AiRequestEnvelope> sent = ArgumentCaptor.forClass(AiRequestEnvelope.class);
    verify(probePort).requestProbeRecommendation(sent.capture(), anyLong(), any());
    assertThat(sent.getValue().interactionId()).isEqualTo(INTERACTION);
    assertThat(sent.getValue().requestId()).isEqualTo("req-1");
    assertThat(sent.getValue().learner().learnerRef()).isEqualTo(LEARNER.toString());
    assertThat(sent.getValue().domainContext().domainCode()).isEqualTo(DOMAIN);
    assertThat(sent.getValue().domainContext().domainType()).isEqualTo(DomainType.TECHNOLOGY);
  }

  @Test
  @DisplayName("16 -- re-evaluating the same proposal keeps one row and the same verdict")
  void repeatedEvaluationIsIdempotent() {
    givenGroundableContext();
    whenModelReturns(validPayload());

    DiagnosticProbeProposalService.Outcome first = recommend();
    DiagnosticProbeProposalService.Outcome second = recommend();

    assertThat(first.status())
        .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ACCEPTED);
    assertThat(second.status()).isEqualTo(first.status());
    assertThat(decisions.appended).hasSize(1);
  }

  // -- 2-4, 15: AI-plane / MCP unavailability -> ABSENT, nothing written -------------------------

  @Test
  @DisplayName("2 -- AI unavailable -> ABSENT, nothing recorded, deterministic path unchanged")
  void aiUnavailableResolvesToAbsent() {
    givenGroundableContext();
    when(probePort.requestProbeRecommendation(any(), anyLong(), any()))
        .thenThrow(new AiUnavailableException("AI_NOT_CONFIGURED", "off"));

    DiagnosticProbeProposalService.Outcome outcome = recommend();

    assertThat(outcome.status()).isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ABSENT);
    assertThat(decisions.appended).isEmpty();
  }

  @Test
  @DisplayName("3 -- a provider timeout (AI_DEADLINE_EXCEEDED) -> ABSENT")
  void providerTimeoutResolvesToAbsent() {
    givenGroundableContext();
    when(probePort.requestProbeRecommendation(any(), anyLong(), any()))
        .thenThrow(new AiUnavailableException("AI_DEADLINE_EXCEEDED", "no time"));

    assertThat(recommend().status())
        .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ABSENT);
    assertThat(decisions.appended).isEmpty();
  }

  @Test
  @DisplayName("4 -- an MCP/transport failure surfaced by the client -> ABSENT")
  void mcpOrTransportFailureResolvesToAbsent() {
    givenGroundableContext();
    when(probePort.requestProbeRecommendation(any(), anyLong(), any()))
        .thenThrow(new AiUnavailableException("AI_TRANSPORT_FAILURE", "unreachable"));

    assertThat(recommend().status())
        .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ABSENT);
    assertThat(decisions.appended).isEmpty();
  }

  // -- 5: a returned-but-malformed payload -> MALFORMED ------------------------------------------

  @Test
  @DisplayName("5 -- a 200 response whose proposal violates the Java contract -> MALFORMED, audited")
  void malformedReturnedProposalIsRecorded() {
    givenGroundableContext();
    Map<String, Object> bad = validPayload();
    bad.remove("rationale");
    whenModelReturns(bad);

    DiagnosticProbeProposalService.Outcome outcome = recommend();

    assertThat(outcome.status())
        .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.MALFORMED);
    assertThat(decisions.appended).hasSize(1);
    assertThat(decisions.appended.get(0).accepted()).isFalse();
  }

  @Test
  @DisplayName("12 -- a model that adds a `confidence` field -> MALFORMED (forbidden field)")
  void forbiddenConfidenceFieldIsMalformed() {
    givenGroundableContext();
    Map<String, Object> bad = validPayload();
    bad.put("confidence", 0.93);
    whenModelReturns(bad);

    assertThat(recommend().status())
        .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.MALFORMED);
  }

  // -- 6-11, 13: below-gate payloads -> REJECTED -----------------------------------------------

  private void assertRejectedWith(
      Map<String, Object> payload, DiagnosticProbeProposalGateReason... anyOf) {
    givenGroundableContext();
    whenModelReturns(payload);
    DiagnosticProbeProposalService.Outcome outcome = recommend();
    assertThat(outcome.status())
        .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.REJECTED);
    assertThat(decisions.appended).hasSize(1);
    assertThat(decisions.appended.get(0).accepted()).isFalse();
    assertThat(outcome.reasons()).containsAnyOf(anyOf);
  }

  @Test
  @DisplayName("6 -- an evidenceRef outside E_allowed -> REJECTED")
  void unauthorisedEvidenceRefIsRejected() {
    Map<String, Object> payload = validPayload();
    payload.put("evidenceRefs", List.of(UNAUTHORISED.toString()));
    assertRejectedWith(
        payload, DiagnosticProbeProposalGateReason.EVIDENCE_REFERENCE_NOT_IN_CONTEXT);
  }

  @Test
  @DisplayName("7 -- a targetMisconceptionId outside M_allowed -> REJECTED")
  void unauthorisedMisconceptionIsRejected() {
    Map<String, Object> payload = validPayload();
    payload.put("targetMisconceptionId", UNAUTHORISED.toString());
    assertRejectedWith(
        payload,
        DiagnosticProbeProposalGateReason.TARGET_MISCONCEPTION_OUT_OF_SCOPE,
        DiagnosticProbeProposalGateReason.TARGET_MISCONCEPTION_NOT_FOUND);
  }

  @Test
  @DisplayName("8 -- a targetNode that is not the misconception's real arc target -> REJECTED")
  void unauthorisedNodeIsRejected() {
    Map<String, Object> payload = validPayload();
    payload.put(
        "targetNode",
        new LinkedHashMap<>(Map.of("kind", "CONCEPT", "id", UNAUTHORISED.toString())));
    assertRejectedWith(payload, DiagnosticProbeProposalGateReason.TARGET_NODE_ARC_MISMATCH);
  }

  @Test
  @DisplayName("9 -- a non-empty candidateProbeRef -> REJECTED (the authorised set is empty in "
      + "step 3)")
  void nonEmptyCandidateProbeRefIsRejected() {
    Map<String, Object> payload = validPayload();
    payload.put("candidateProbeRef", UNAUTHORISED.toString());
    assertRejectedWith(
        payload, DiagnosticProbeProposalGateReason.CANDIDATE_PROBE_REFERENCE_NOT_AUTHORIZED);
  }

  @Test
  @DisplayName("10 -- an interactionId that is not the interaction's own -> REJECTED")
  void interactionMismatchIsRejected() {
    Map<String, Object> payload = validPayload();
    payload.put("interactionId", "int-other");
    assertRejectedWith(payload, DiagnosticProbeProposalGateReason.INTERACTION_BINDING_MISMATCH);
  }

  @Test
  @DisplayName("11 -- a domain that is not the interaction's authorised domain -> REJECTED")
  void domainMismatchIsRejected() {
    Map<String, Object> payload = validPayload();
    payload.put("domain", "KUBERNETES");
    assertRejectedWith(payload, DiagnosticProbeProposalGateReason.DOMAIN_BINDING_MISMATCH);
  }

  @Test
  @DisplayName("13 -- probability / ranking language in the rationale -> REJECTED")
  void forbiddenRationaleTerminologyIsRejected() {
    Map<String, Object> payload = validPayload();
    payload.put(
        "rationale",
        "This misconception is more likely than the others; another probe would confirm it.");
    assertRejectedWith(payload, DiagnosticProbeProposalGateReason.RATIONALE_FORBIDDEN_TERMINOLOGY);
  }

  // -- 14, 15: no groundable evidence / no time -> no model call at all --------------------------

  @Test
  @DisplayName("14 -- H6/H7 expose nothing to reason from -> no model call, ABSENT, nothing written")
  void noGroundableEvidenceMeansNoModelCall() {
    when(assembler.assemble(eq(LEARNER), eq(DOMAIN), eq(INTERACTION)))
        .thenReturn(
            new DiagnosticProbeContextAssembler.Assembled(
                context(),
                false,
                io.ramals.learningplatform.assessment.DiagnosticReport.DiagnosticDataStatus
                    .NO_EVIDENCE));

    DiagnosticProbeProposalService.Outcome outcome = recommend();

    assertThat(outcome.status()).isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ABSENT);
    assertThat(decisions.appended).isEmpty();
    verifyNoInteractions(probePort);
  }

  @Test
  @DisplayName("an unknown domain (no published curriculum) -> no model call, ABSENT")
  void unknownDomainMeansNoModelCall() {
    when(assembler.assemble(eq(LEARNER), eq(DOMAIN), eq(INTERACTION)))
        .thenReturn(
            new DiagnosticProbeContextAssembler.Assembled(
                context(),
                true,
                io.ramals.learningplatform.assessment.DiagnosticReport.DiagnosticDataStatus
                    .HAS_EVIDENCE));
    when(domainContextAssembler.forDomain(eq(DOMAIN))).thenReturn(Optional.empty());

    assertThat(recommend().status())
        .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ABSENT);
    verifyNoInteractions(probePort);
  }

  @Test
  @DisplayName("15 -- no time left for the read -> no model call, no authority effect")
  void deadlineExhaustedMeansNoModelCall() {
    givenGroundableContext();

    DiagnosticProbeProposalService.Outcome outcome =
        orchestrator.recommendNextProbe(LEARNER, DOMAIN, INTERACTION, "req-1", 0);

    assertThat(outcome.status()).isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ABSENT);
    assertThat(decisions.appended).isEmpty();
    verify(probePort, never()).requestProbeRecommendation(any(), anyLong(), any());
  }

  // -- "does nothing else": no selection, no execution -----------------------------------------

  @Test
  @DisplayName("the orchestrator holds no selection/execution collaborator it could call")
  void orchestratorDependsOnlyOnAssembleGateAndClient() {
    // Constructor arity is the proof: the only collaborators are the two assemblers, the AI client
    // port, the PR #266 proposal service, and the delegated-context minter. There is no
    // DiagnosticSelector, DiagnosticSubmissionService, MasteryService or ProbeExecutor parameter.
    assertThat(DiagnosticProbeRecommendationOrchestrator.class.getDeclaredConstructors())
        .singleElement()
        .satisfies(
            ctor ->
                assertThat(ctor.getParameterTypes())
                    .containsExactly(
                        DiagnosticProbeContextAssembler.class,
                        DomainContextAssembler.class,
                        DiagnosticProbePort.class,
                        DiagnosticProbeProposalService.class,
                        DelegatedAiContextMinter.class));
  }
}
