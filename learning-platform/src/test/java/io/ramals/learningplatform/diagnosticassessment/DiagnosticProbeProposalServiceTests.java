package io.ramals.learningplatform.diagnosticassessment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evaluation seam: the advisory proposal is never a hard dependency of diagnostic progression,
 * it is idempotent, it records complete provenance with correlation, and it cannot touch mastery,
 * evidence, progression or DIAGNOSTIC_SELECTION.
 */
class DiagnosticProbeProposalServiceTests {

  private static final UUID MC = UUID.fromString("01900000-0000-7000-8000-0000000000d1");
  private static final UUID OBJECTIVE_ID = UUID.fromString("01900000-0000-7000-8000-0000000000f1");
  private static final UUID LEARNER = UUID.fromString("01900000-0000-7000-8000-0000000000c1");

  private final DiagnosticProbeTargetPort targetPort =
      new DiagnosticProbeTargetPort() {
        @Override
        public Optional<ResolvedMisconception> findMisconception(UUID id) {
          return MC.equals(id)
              ? Optional.of(new ResolvedMisconception(id, true, OBJECTIVE_ID, null))
              : Optional.empty();
        }

        @Override
        public Optional<DiagnosticProbeProposal.TargetNode.Kind> findDiagnosticNodeKind(UUID id) {
          return Optional.empty();
        }
      };

  /** An in-memory append-only decision store, matching the port's idempotency contract. */
  private static final class RecordingDecisionPort implements DiagnosticProbeProposalDecisionPort {
    final List<DiagnosticProbeProposalDecision> appended = new ArrayList<>();
    boolean fail;

    @Override
    public void append(DiagnosticProbeProposalDecision decision) {
      if (fail) {
        throw new IllegalStateException("simulated persistence failure");
      }
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

  private DiagnosticProbeProposalService service(DiagnosticProbeProposalDecisionPort decisions) {
    return new DiagnosticProbeProposalService(
        new DiagnosticProbeProposalGate(), targetPort, decisions);
  }

  private static DiagnosticProbeProposalContext context() {
    return new DiagnosticProbeProposalContext(
        LEARNER,
        "int-1",
        "KAFKA",
        Set.of(MC),
        Set.of("ev-1", "ev-2"),
        Set.of(),
        Set.of("diagnostics.current-domain-report"));
  }

  private static Map<String, Object> validPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("contractVersion", "1.0");
    payload.put("proposalType", "DIAGNOSTIC_PROBE_CANDIDATE");
    payload.put("proposalId", "prop-1");
    payload.put("requestId", "req-1");
    payload.put("agentRunId", "run-1");
    payload.put("interactionId", "int-1");
    payload.put("domain", "KAFKA");
    payload.put("targetMisconceptionId", MC.toString());
    payload.put(
        "targetNode",
        new LinkedHashMap<>(Map.of("kind", "LEARNING_OBJECTIVE", "id", OBJECTIVE_ID.toString())));
    payload.put("probeIntent", "COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE");
    payload.put("candidateProbeRef", null);
    payload.put("evidenceRefs", List.of("ev-1"));
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
        "prompt-probe",
        "p1",
        "route-diag",
        "anthropic",
        "claude",
        "rv1",
        TrustLevel.UNVERIFIED,
        "0.5",
        List.of(),
        proposal,
        null,
        null);
  }

  private static DiagnosticProbeProposalService.Correlation correlation() {
    return new DiagnosticProbeProposalService.Correlation("int-1", "trace-1", "req-1");
  }

  @Test
  @DisplayName("no proposal at all -> ABSENT, nothing recorded, deterministic path unaffected")
  void absentProposalIsANoOp() {
    RecordingDecisionPort decisions = new RecordingDecisionPort();

    DiagnosticProbeProposalService.Outcome outcome =
        service(decisions).evaluate(null, context(), correlation());

    assertThat(outcome.status()).isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ABSENT);
    assertThat(outcome.acceptedAndUsable()).isFalse();
    assertThat(decisions.appended).isEmpty();

    // An envelope whose proposal map is empty is the same as no proposal.
    assertThat(
            service(decisions).evaluate(envelope(Map.of()), context(), correlation()).status())
        .isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ABSENT);
  }

  @Test
  @DisplayName("a malformed payload -> MALFORMED, one audit row, deterministic path unaffected")
  void malformedProposalIsRecordedAndRejected() {
    RecordingDecisionPort decisions = new RecordingDecisionPort();
    Map<String, Object> bad = validPayload();
    bad.put("confidence", 0.99);

    DiagnosticProbeProposalService.Outcome outcome =
        service(decisions).evaluate(envelope(bad), context(), correlation());

    assertThat(outcome.status()).isEqualTo(DiagnosticProbeProposalService.Outcome.Status.MALFORMED);
    assertThat(outcome.parserReasonCode()).isEqualTo("PROPOSAL_FORBIDDEN_FIELD");
    assertThat(decisions.appended).hasSize(1);
    DiagnosticProbeProposalDecision row = decisions.appended.get(0);
    assertThat(row.accepted()).isFalse();
    assertThat(row.reasonCodes()).containsExactly("PROPOSAL_MALFORMED");
    assertThat(row.parserReasonCode()).isEqualTo("PROPOSAL_FORBIDDEN_FIELD");
    assertThat(row.interactionId()).isEqualTo("int-1");
    assertThat(row.traceId()).isEqualTo("trace-1");
    assertThat(row.learnerId()).isEqualTo(LEARNER);
  }

  @Test
  @DisplayName("a below-gate proposal -> REJECTED with stable codes and a complete audit row")
  void gatedProposalIsRecorded() {
    RecordingDecisionPort decisions = new RecordingDecisionPort();
    Map<String, Object> stale = validPayload();
    stale.put("domain", "KUBERNETES");

    DiagnosticProbeProposalService.Outcome outcome =
        service(decisions).evaluate(envelope(stale), context(), correlation());

    assertThat(outcome.status()).isEqualTo(DiagnosticProbeProposalService.Outcome.Status.REJECTED);
    assertThat(outcome.reasons())
        .contains(DiagnosticProbeProposalGateReason.DOMAIN_BINDING_MISMATCH);
    DiagnosticProbeProposalDecision row = decisions.appended.get(0);
    assertThat(row.accepted()).isFalse();
    assertThat(row.reasonCodes()).contains("DOMAIN_BINDING_MISMATCH");
    assertThat(row.targetMisconceptionId()).isEqualTo(MC);
    assertThat(row.probeIntent()).isEqualTo("COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE");
    assertThat(row.citedEvidenceRefs()).containsExactly("ev-1");
    assertThat(row.allowedEvidenceRefs()).containsExactly("ev-1", "ev-2");
    assertThat(row.policyVersion()).isEqualTo("DIAGNOSTIC_PROBE_PROPOSAL_GATE_V1");
    assertThat(row.promptTemplateId()).isEqualTo("prompt-probe");
    assertThat(row.modelRoute()).isEqualTo("route-diag");
    assertThat(row.resolvedProvider()).isEqualTo("anthropic");
  }

  @Test
  @DisplayName("a valid proposal -> ACCEPTED; acceptance is well-formed/grounded/in-scope, not 'runs'")
  void validProposalAccepted() {
    RecordingDecisionPort decisions = new RecordingDecisionPort();

    DiagnosticProbeProposalService.Outcome outcome =
        service(decisions).evaluate(envelope(validPayload()), context(), correlation());

    assertThat(outcome.status()).isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ACCEPTED);
    assertThat(decisions.appended.get(0).accepted()).isTrue();
    assertThat(decisions.appended.get(0).reasonCodes()).containsExactly("ACCEPTED");
  }

  @Test
  @DisplayName("re-evaluating the same proposal returns the persisted verdict; no duplicate row")
  void idempotentOnProposalIdentity() {
    RecordingDecisionPort decisions = new RecordingDecisionPort();
    DiagnosticProbeProposalService service = service(decisions);

    DiagnosticProbeProposalService.Outcome first =
        service.evaluate(envelope(validPayload()), context(), correlation());
    DiagnosticProbeProposalService.Outcome second =
        service.evaluate(envelope(validPayload()), context(), correlation());

    assertThat(first.status()).isEqualTo(DiagnosticProbeProposalService.Outcome.Status.ACCEPTED);
    assertThat(second.status()).isEqualTo(first.status());
    assertThat(decisions.appended).hasSize(1);
  }

  @Test
  @DisplayName("a real persistence failure surfaces as a dedicated type, not a silent clean record")
  void persistenceFailureIsSurfaced() {
    RecordingDecisionPort decisions = new RecordingDecisionPort();
    decisions.fail = true;

    assertThatThrownBy(
            () ->
                service(decisions)
                    .evaluate(envelope(validPayload()), context(), correlation()))
        .isInstanceOf(
            DiagnosticProbeProposalService.DiagnosticProbeProposalPersistenceException.class);
  }

  @Test
  @DisplayName("13 -- the gate/service cannot reach mastery, evidence, progression, or selection writers")
  void cannotReachAuthoritativeOrSelectionState() {
    JavaClasses probeClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.ramals.learningplatform.diagnosticassessment");

    noClasses()
        .that()
        .haveSimpleNameStartingWith("DiagnosticProbeProposal")
        .or()
        .haveSimpleName("JdbcDiagnosticProbeProposalDecisionRepository")
        .or()
        .haveSimpleName("JdbcDiagnosticProbeTargetRepository")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("io.ramals.learningplatform.mastery.MasteryRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("io.ramals.learningplatform.mastery.MasteryService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("io.ramals.learningplatform.evidence.EvidenceRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("io.ramals.learningplatform.evidence.EvidenceService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("io.ramals.learningplatform.learning.ProgressionRepository")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("io.ramals.learningplatform.assessment.DiagnosticSubmissionService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("io.ramals.learningplatform.assessment.DiagnosticService")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("io.ramals.learningplatform.assessment.AdaptiveDiagnosticSelector")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName(
            "io.ramals.learningplatform.assessment.HypothesisDrivenProbeDiagnosticSelector")
        .because(
            "M2-ADR-032: an advisory diagnostic-probe proposal never writes learner state and never "
                + "reaches DIAGNOSTIC_SELECTION_V1-V5 -- eligibility and execution stay deterministic")
        .check(probeClasses);
  }
}
