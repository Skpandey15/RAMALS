package io.ramals.learningplatform.diagnosticassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The advisory diagnostic-probe proposal parser accepts exactly the v1 contract, and fails closed. */
class DiagnosticProbeProposalTests {

  private static final String MC = "01900000-0000-7000-8000-0000000000a1";
  private static final String NODE = "01900000-0000-7000-8000-0000000000b1";

  private static Map<String, Object> validPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("contractVersion", "1.0");
    payload.put("proposalType", "DIAGNOSTIC_PROBE_CANDIDATE");
    payload.put("proposalId", "prop-1");
    payload.put("requestId", "req-1");
    payload.put("agentRunId", "run-1");
    payload.put("interactionId", "int-1");
    payload.put("domain", "KAFKA");
    payload.put("targetMisconceptionId", MC);
    payload.put("targetNode", new LinkedHashMap<>(Map.of("kind", "CONCEPT", "id", NODE)));
    payload.put("probeIntent", "DISCRIMINATE_BETWEEN_EVIDENCE_STATES");
    payload.put("candidateProbeRef", null);
    payload.put("evidenceRefs", List.of("ev-1", "ev-2"));
    payload.put("rationale", "Additional discriminating evidence would help narrow the ambiguity.");
    return payload;
  }

  private static DiagnosticProbeProposal parse(Map<String, Object> payload) {
    return DiagnosticProbeProposal.parse(payload, "prop-1", "req-1", "run-1");
  }

  @Test
  @DisplayName("a valid v1 payload parses into the contract")
  void validPayloadParses() {
    DiagnosticProbeProposal proposal = parse(validPayload());

    assertThat(proposal.contractVersion()).isEqualTo("1.0");
    assertThat(proposal.interactionId()).isEqualTo("int-1");
    assertThat(proposal.domain()).isEqualTo("KAFKA");
    assertThat(proposal.targetMisconceptionId()).isEqualTo(UUID.fromString(MC));
    assertThat(proposal.targetNode().kind())
        .isEqualTo(DiagnosticProbeProposal.TargetNode.Kind.CONCEPT);
    assertThat(proposal.targetNode().id()).isEqualTo(UUID.fromString(NODE));
    assertThat(proposal.probeIntent())
        .isEqualTo(DiagnosticProbeProposal.ProbeIntent.DISCRIMINATE_BETWEEN_EVIDENCE_STATES);
    assertThat(proposal.candidateProbeRef()).isNull();
    assertThat(proposal.evidenceRefs()).containsExactly("ev-1", "ev-2");
  }

  @Test
  @DisplayName("an absent payload is a rejection, not an exception thrown away")
  void absentPayloadIsMalformed() {
    assertThatThrownBy(() -> parse(Map.of()))
        .isInstanceOfSatisfying(
            DiagnosticProbeProposal.MalformedProbeProposalException.class,
            malformed -> assertThat(malformed.reasonCode()).isEqualTo("PROPOSAL_PAYLOAD_ABSENT"));
  }

  @Test
  @DisplayName("an unknown top-level field is rejected, never silently ignored (M2-ADR-032 22)")
  void unknownFieldIsRejected() {
    Map<String, Object> payload = validPayload();
    payload.put("somethingExtra", "x");
    assertThat(
            catchThrowableOfType(
                    DiagnosticProbeProposal.MalformedProbeProposalException.class, () -> parse(payload))
                .reasonCode())
        .isEqualTo("PROPOSAL_UNKNOWN_FIELD");
  }

  @Test
  @DisplayName("a forbidden field (bare confidence/probability/rank/diagnosis/learner id) is rejected")
  void forbiddenFieldsAreRejected() {
    for (String forbidden :
        List.of("confidence", "probability", "rank", "score", "diagnosis", "rootCause", "learnerId")) {
      Map<String, Object> payload = validPayload();
      payload.put(forbidden, 0.9);
      assertThat(
              catchThrowableOfType(
                      DiagnosticProbeProposal.MalformedProbeProposalException.class,
                      () -> parse(payload))
                  .reasonCode())
          .as("field %s", forbidden)
          .isEqualTo("PROPOSAL_FORBIDDEN_FIELD");
    }
  }

  @Test
  @DisplayName("the wrong contract version or proposal type fails closed")
  void wrongConstantsFailClosed() {
    Map<String, Object> badVersion = validPayload();
    badVersion.put("contractVersion", "2.0");
    assertThat(
            catchThrowableOfType(
                    DiagnosticProbeProposal.MalformedProbeProposalException.class,
                    () -> parse(badVersion))
                .reasonCode())
        .isEqualTo("PROPOSAL_CONTRACT_VERSION_INVALID");

    Map<String, Object> badType = validPayload();
    badType.put("proposalType", "DIAGNOSTIC_ASSESSMENT");
    assertThat(
            catchThrowableOfType(
                    DiagnosticProbeProposal.MalformedProbeProposalException.class,
                    () -> parse(badType))
                .reasonCode())
        .isEqualTo("PROPOSAL_TYPE_INVALID");
  }

  @Test
  @DisplayName("an unrecognised probeIntent or targetNode.kind is a rejection")
  void unrecognisedEnumsAreRejected() {
    Map<String, Object> badIntent = validPayload();
    badIntent.put("probeIntent", "PICK_THE_NEXT_QUESTION");
    assertThat(
            catchThrowableOfType(
                    DiagnosticProbeProposal.MalformedProbeProposalException.class,
                    () -> parse(badIntent))
                .reasonCode())
        .isEqualTo("PROPOSAL_PROBE_INTENT_UNKNOWN");

    Map<String, Object> badKind = validPayload();
    badKind.put("targetNode", new LinkedHashMap<>(Map.of("kind", "TOPIC", "id", NODE)));
    assertThat(
            catchThrowableOfType(
                    DiagnosticProbeProposal.MalformedProbeProposalException.class,
                    () -> parse(badKind))
                .reasonCode())
        .isEqualTo("PROPOSAL_TARGET_NODE_INVALID");
  }

  @Test
  @DisplayName("a non-UUID target, an over-long rationale, and duplicate/empty evidence refs are rejected")
  void boundedFieldsAreEnforced() {
    Map<String, Object> badUuid = validPayload();
    badUuid.put("targetMisconceptionId", "not-a-uuid");
    assertThat(
            catchThrowableOfType(
                    DiagnosticProbeProposal.MalformedProbeProposalException.class,
                    () -> parse(badUuid))
                .reasonCode())
        .isEqualTo("PROPOSAL_TARGET_MISCONCEPTION_INVALID");

    Map<String, Object> longRationale = validPayload();
    longRationale.put("rationale", "x".repeat(1001));
    assertThat(
            catchThrowableOfType(
                    DiagnosticProbeProposal.MalformedProbeProposalException.class,
                    () -> parse(longRationale))
                .reasonCode())
        .isEqualTo("PROPOSAL_RATIONALE_INVALID");

    Map<String, Object> emptyEvidence = validPayload();
    emptyEvidence.put("evidenceRefs", List.of());
    assertThat(
            catchThrowableOfType(
                    DiagnosticProbeProposal.MalformedProbeProposalException.class,
                    () -> parse(emptyEvidence))
                .reasonCode())
        .isEqualTo("PROPOSAL_EVIDENCE_REFS_INVALID");

    Map<String, Object> dupEvidence = validPayload();
    dupEvidence.put("evidenceRefs", List.of("ev-1", "ev-1"));
    assertThat(
            catchThrowableOfType(
                    DiagnosticProbeProposal.MalformedProbeProposalException.class,
                    () -> parse(dupEvidence))
                .reasonCode())
        .isEqualTo("PROPOSAL_EVIDENCE_REFS_INVALID");
  }

  @Test
  @DisplayName("correlation identifiers are bound by the caller, never read from the payload")
  void correlationBoundByCaller() {
    Map<String, Object> payload = validPayload();
    payload.put("proposalId", "payload-chosen");
    payload.put("requestId", "payload-chosen");
    payload.put("agentRunId", "payload-chosen");

    DiagnosticProbeProposal proposal =
        DiagnosticProbeProposal.parse(payload, "caller-prop", "caller-req", "caller-run");

    assertThat(proposal.proposalId()).isEqualTo("caller-prop");
    assertThat(proposal.requestId()).isEqualTo("caller-req");
    assertThat(proposal.agentRunId()).isEqualTo("caller-run");
  }
}
