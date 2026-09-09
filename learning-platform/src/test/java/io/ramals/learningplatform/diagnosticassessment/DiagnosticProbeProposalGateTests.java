package io.ramals.learningplatform.diagnosticassessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deterministic acceptance boundary for advisory diagnostic-probe proposals (M2-ADR-032 4).
 * Every rejection is a stable reason code derived from persisted, versioned deterministic inputs;
 * the same inputs always produce the same result.
 */
class DiagnosticProbeProposalGateTests {

  private static final UUID MC_OBJECTIVE = UUID.fromString("01900000-0000-7000-8000-0000000000d1");
  private static final UUID MC_CONCEPT = UUID.fromString("01900000-0000-7000-8000-0000000000d2");
  private static final UUID MC_UNPUBLISHED = UUID.fromString("01900000-0000-7000-8000-0000000000d3");
  private static final UUID MC_MISSING = UUID.fromString("01900000-0000-7000-8000-0000000000d9");
  private static final UUID OBJECTIVE_ID = UUID.fromString("01900000-0000-7000-8000-0000000000f1");
  private static final UUID CONCEPT_NODE_ID = UUID.fromString("01900000-0000-7000-8000-0000000000f2");
  private static final UUID UNPUBLISHED_OBJ = UUID.fromString("01900000-0000-7000-8000-0000000000f3");
  private static final UUID OTHER_ID = UUID.fromString("01900000-0000-7000-8000-0000000000f9");
  private static final UUID PROBE_OK = UUID.fromString("01900000-0000-7000-8000-0000000000e1");
  private static final UUID PROBE_BAD = UUID.fromString("01900000-0000-7000-8000-0000000000e9");
  private static final UUID LEARNER = UUID.fromString("01900000-0000-7000-8000-0000000000c1");

  private final DiagnosticProbeProposalGate gate = new DiagnosticProbeProposalGate();

  private static final DiagnosticProbeTargetPort PORT =
      new DiagnosticProbeTargetPort() {
        @Override
        public Optional<ResolvedMisconception> findMisconception(UUID id) {
          if (MC_OBJECTIVE.equals(id)) {
            return Optional.of(new ResolvedMisconception(id, true, OBJECTIVE_ID, null));
          }
          if (MC_CONCEPT.equals(id)) {
            return Optional.of(new ResolvedMisconception(id, true, null, CONCEPT_NODE_ID));
          }
          if (MC_UNPUBLISHED.equals(id)) {
            return Optional.of(new ResolvedMisconception(id, false, UNPUBLISHED_OBJ, null));
          }
          return Optional.empty();
        }

        @Override
        public Optional<DiagnosticProbeProposal.TargetNode.Kind> findDiagnosticNodeKind(UUID id) {
          return CONCEPT_NODE_ID.equals(id)
              ? Optional.of(DiagnosticProbeProposal.TargetNode.Kind.CONCEPT)
              : Optional.empty();
        }
      };

  private static DiagnosticProbeProposalContext context() {
    return new DiagnosticProbeProposalContext(
        LEARNER,
        "int-1",
        "KAFKA",
        Set.of(MC_OBJECTIVE, MC_CONCEPT, MC_UNPUBLISHED),
        Set.of("ev-1", "ev-2", "ev-3"),
        Set.of(PROBE_OK),
        Set.of("diagnostics.current-domain-report"));
  }

  private static DiagnosticProbeProposal probe(
      String contractVersion,
      String interactionId,
      String domain,
      UUID misconceptionId,
      DiagnosticProbeProposal.TargetNode.Kind kind,
      UUID nodeId,
      UUID candidateProbeRef,
      List<String> evidence,
      String rationale) {
    return new DiagnosticProbeProposal(
        contractVersion,
        "prop-1",
        "req-1",
        "run-1",
        interactionId,
        domain,
        misconceptionId,
        new DiagnosticProbeProposal.TargetNode(kind, nodeId),
        DiagnosticProbeProposal.ProbeIntent.COLLECT_ADDITIONAL_MISCONCEPTION_EVIDENCE,
        candidateProbeRef,
        evidence,
        rationale);
  }

  private static DiagnosticProbeProposal happy() {
    return probe(
        "1.0", "int-1", "KAFKA", MC_OBJECTIVE,
        DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE, OBJECTIVE_ID, null,
        List.of("ev-1", "ev-2"),
        "One additional discriminating observation would help narrow the remaining ambiguity.");
  }

  @Test
  @DisplayName("1 -- a well-formed, evidence-grounded, in-scope proposal is accepted")
  void validProposalAccepted() {
    DiagnosticProbeProposalGateResult result = gate.evaluate(happy(), context(), PORT);

    assertThat(result.accepted()).isTrue();
    assertThat(result.reasons()).containsExactly(DiagnosticProbeProposalGateReason.ACCEPTED);
    assertThat(result.referencedEvidenceRefs()).containsExactlyInAnyOrder("ev-1", "ev-2");
    assertThat(result.policyVersion()).isEqualTo("DIAGNOSTIC_PROBE_PROPOSAL_GATE_V1");
  }

  @Test
  @DisplayName("1b -- a concept-arc target that matches the misconception's real node is accepted")
  void conceptArcAccepted() {
    DiagnosticProbeProposal proposal =
        probe("1.0", "int-1", "KAFKA", MC_CONCEPT,
            DiagnosticProbeProposal.TargetNode.Kind.CONCEPT, CONCEPT_NODE_ID, PROBE_OK,
            List.of("ev-3"), "Additional evidence would help discriminate the remaining states.");

    assertThat(gate.evaluate(proposal, context(), PORT).accepted()).isTrue();
  }

  @Test
  @DisplayName("2 -- a malformed proposal never reaches the gate as an object; the gate still rejects null")
  void nullProposalRejected() {
    DiagnosticProbeProposalGateResult result = gate.evaluate(null, context(), PORT);
    assertThat(result.rejected()).isTrue();
    assertThat(result.reasons())
        .containsExactly(DiagnosticProbeProposalGateReason.VALIDATION_UNAVAILABLE);
  }

  @Test
  @DisplayName("3 -- an out-of-scope / unknown target misconception is rejected")
  void unsupportedTargetRejected() {
    DiagnosticProbeProposalGateResult result =
        gate.evaluate(
            probe("1.0", "int-1", "KAFKA", MC_MISSING,
                DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE, OBJECTIVE_ID, null,
                List.of("ev-1"), "Additional evidence would help."),
            context(),
            PORT);

    assertThat(result.rejected()).isTrue();
    assertThat(result.reasons())
        .contains(
            DiagnosticProbeProposalGateReason.TARGET_MISCONCEPTION_OUT_OF_SCOPE,
            DiagnosticProbeProposalGateReason.TARGET_MISCONCEPTION_NOT_FOUND,
            DiagnosticProbeProposalGateReason.UNAUTHORIZED_IDENTIFIER_PRESENT);
  }

  @Test
  @DisplayName("3b -- an in-scope but unpublished misconception is rejected (M2-ADR-026 4)")
  void unpublishedTargetRejected() {
    DiagnosticProbeProposalGateResult result =
        gate.evaluate(
            probe("1.0", "int-1", "KAFKA", MC_UNPUBLISHED,
                DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE, UNPUBLISHED_OBJ, null,
                List.of("ev-1"), "Additional evidence would help."),
            context(),
            PORT);

    assertThat(result.reasons())
        .containsExactly(DiagnosticProbeProposalGateReason.TARGET_MISCONCEPTION_NOT_PUBLISHED);
  }

  @Test
  @DisplayName("3c -- a targetNode that is not the misconception's real exclusive-arc target is rejected")
  void arcMismatchRejected() {
    DiagnosticProbeProposalGateResult wrongId =
        gate.evaluate(
            probe("1.0", "int-1", "KAFKA", MC_OBJECTIVE,
                DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE, OTHER_ID, null,
                List.of("ev-1"), "Additional evidence would help."),
            context(),
            PORT);
    assertThat(wrongId.reasons())
        .contains(DiagnosticProbeProposalGateReason.TARGET_NODE_ARC_MISMATCH);

    DiagnosticProbeProposalGateResult wrongKind =
        gate.evaluate(
            probe("1.0", "int-1", "KAFKA", MC_CONCEPT,
                DiagnosticProbeProposal.TargetNode.Kind.SUB_CONCEPT, CONCEPT_NODE_ID, null,
                List.of("ev-1"), "Additional evidence would help."),
            context(),
            PORT);
    assertThat(wrongKind.reasons())
        .containsExactly(DiagnosticProbeProposalGateReason.TARGET_NODE_ARC_MISMATCH);
  }

  @Test
  @DisplayName("4 -- stale interaction or wrong domain binding is rejected")
  void staleBindingRejected() {
    DiagnosticProbeProposalGateResult interaction =
        gate.evaluate(
            probe("1.0", "int-OTHER", "KAFKA", MC_OBJECTIVE,
                DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE, OBJECTIVE_ID, null,
                List.of("ev-1"), "Additional evidence would help."),
            context(),
            PORT);
    assertThat(interaction.reasons())
        .containsExactly(DiagnosticProbeProposalGateReason.INTERACTION_BINDING_MISMATCH);

    DiagnosticProbeProposalGateResult domain =
        gate.evaluate(
            probe("1.0", "int-1", "KUBERNETES", MC_OBJECTIVE,
                DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE, OBJECTIVE_ID, null,
                List.of("ev-1"), "Additional evidence would help."),
            context(),
            PORT);
    assertThat(domain.reasons())
        .containsExactly(DiagnosticProbeProposalGateReason.DOMAIN_BINDING_MISMATCH);
  }

  @Test
  @DisplayName("5 -- a cited evidence reference outside E_allowed is rejected (E_proposed subset of E_allowed)")
  void evidenceNotInContextRejected() {
    DiagnosticProbeProposalGateResult result =
        gate.evaluate(
            probe("1.0", "int-1", "KAFKA", MC_OBJECTIVE,
                DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE, OBJECTIVE_ID, null,
                List.of("ev-1", "ev-not-supplied"), "Additional evidence would help."),
            context(),
            PORT);

    assertThat(result.reasons())
        .contains(
            DiagnosticProbeProposalGateReason.EVIDENCE_REFERENCE_NOT_IN_CONTEXT,
            DiagnosticProbeProposalGateReason.UNAUTHORIZED_IDENTIFIER_PRESENT);
  }

  @Test
  @DisplayName("a named candidate probe outside the authorized set is rejected; naming it is never authorization")
  void unauthorizedCandidateProbeRejected() {
    DiagnosticProbeProposalGateResult result =
        gate.evaluate(
            probe("1.0", "int-1", "KAFKA", MC_OBJECTIVE,
                DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE, OBJECTIVE_ID, PROBE_BAD,
                List.of("ev-1"), "Additional evidence would help."),
            context(),
            PORT);

    assertThat(result.reasons())
        .contains(DiagnosticProbeProposalGateReason.CANDIDATE_PROBE_REFERENCE_NOT_AUTHORIZED);
  }

  @Test
  @DisplayName("an interaction delegated no MCP capability cannot yield a recommendation")
  void noDelegatedCapabilityRejected() {
    DiagnosticProbeProposalContext noCaps =
        new DiagnosticProbeProposalContext(
            LEARNER, "int-1", "KAFKA", Set.of(MC_OBJECTIVE), Set.of("ev-1"), Set.of(), Set.of());

    assertThat(gate.evaluate(happy(), noCaps, PORT).reasons())
        .contains(DiagnosticProbeProposalGateReason.CAPABILITY_OUT_OF_SCOPE);
  }

  @Test
  @DisplayName("stale/invalid (incomplete) authoritative context fails closed, never best-effort accepts")
  void incompleteContextFailsClosed() {
    DiagnosticProbeProposalContext incomplete =
        new DiagnosticProbeProposalContext(
            LEARNER, "int-1", null, Set.of(MC_OBJECTIVE), Set.of("ev-1"), Set.of(),
            Set.of("diagnostics.current-domain-report"));

    assertThat(gate.evaluate(happy(), incomplete, PORT).reasons())
        .contains(DiagnosticProbeProposalGateReason.VALIDATION_UNAVAILABLE);
  }

  @Test
  @DisplayName("an unsupported contract version fails closed (4.1)")
  void unsupportedContractVersionRejected() {
    assertThat(
            gate.evaluate(
                    probe("9.9", "int-1", "KAFKA", MC_OBJECTIVE,
                        DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE, OBJECTIVE_ID,
                        null, List.of("ev-1"), "Additional evidence would help."),
                    context(),
                    PORT)
                .reasons())
        .containsExactly(DiagnosticProbeProposalGateReason.PROPOSAL_CONTRACT_VERSION_UNSUPPORTED);
  }

  @Test
  @DisplayName("probability, comparative, and resolution terminology in the rationale is rejected (8/9)")
  void forbiddenTerminologyRejected() {
    for (String phrase :
        List.of(
            "the learner probably has this misconception",
            "this is 90% likely",
            "the misconception is now resolved",
            "this confirms the root cause",
            "the diagnosis is verified")) {
      DiagnosticProbeProposalGateResult result =
          gate.evaluate(
              probe("1.0", "int-1", "KAFKA", MC_OBJECTIVE,
                  DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE, OBJECTIVE_ID, null,
                  List.of("ev-1"), phrase),
              context(),
              PORT);
      assertThat(result.reasons())
          .as("phrase: %s", phrase)
          .contains(DiagnosticProbeProposalGateReason.RATIONALE_FORBIDDEN_TERMINOLOGY);
    }
  }

  @Test
  @DisplayName("15 -- identical inputs always produce an identical result (gate determinism, not proposal)")
  void deterministicForIdenticalInputs() {
    DiagnosticProbeProposal proposal = happy();
    DiagnosticProbeProposalContext context = context();

    DiagnosticProbeProposalGateResult a = gate.evaluate(proposal, context, PORT);
    DiagnosticProbeProposalGateResult b = gate.evaluate(proposal, context, PORT);
    DiagnosticProbeProposalGateResult c = new DiagnosticProbeProposalGate().evaluate(proposal, context, PORT);

    assertThat(a.accepted()).isEqualTo(b.accepted()).isEqualTo(c.accepted());
    assertThat(a.reasons()).isEqualTo(b.reasons()).isEqualTo(c.reasons());
    assertThat(a.referencedEvidenceRefs()).isEqualTo(b.referencedEvidenceRefs());
  }

  @Test
  @DisplayName("rejection reasons are sorted by name for a stable audit")
  void reasonsAreSorted() {
    DiagnosticProbeProposalGateResult result =
        gate.evaluate(
            probe("9.9", "int-OTHER", "KUBERNETES", MC_MISSING,
                DiagnosticProbeProposal.TargetNode.Kind.LEARNING_OBJECTIVE, OTHER_ID, PROBE_BAD,
                List.of("ev-x"), "the misconception is resolved with high probability"),
            context(),
            PORT);

    List<String> names = result.reasons().stream().map(Enum::name).toList();
    assertThat(names).isSorted();
    assertThat(result.accepted()).isFalse();
  }
}
