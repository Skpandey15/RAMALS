package io.ramals.learningplatform.grounding;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProposalGroundingGateTests {
  private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
  private final ProposalGroundingGate gate = new ProposalGroundingGate(
      new GroundedContextValidator(JsonMapper.builder().findAndAddModules().build()),
      new ProposalGroundingPolicy());

  @Test
  void authoritativeCitationsAndIndependentMinimumsPass() {
    GroundedContext context = context(List.of(
        item("e-1", SourceType.LEARNER_EVIDENCE, ContextAuthority.AUTHORITATIVE_FACT),
        item("m-1", SourceType.MASTERY, ContextAuthority.AUTHORITATIVE_FACT),
        item("p-1", SourceType.CURRICULUM_POLICY, ContextAuthority.AUTHORITATIVE_FACT)));

    ProposalGateResult result = gate.evaluate(proposal(context, "0.6500",
        List.of(new GroundedClaim("KAFKA_BROKER", Set.of("e-1", "m-1")))), context, NOW);

    assertThat(result.accepted()).isTrue();
    assertThat(result.reasons()).containsExactly(ProposalGateReason.ACCEPTED);
    assertThat(result.referencedEvidenceIds()).containsExactlyInAnyOrder("e-1", "m-1");
  }

  @Test
  void fabricatedAndUnsupportedClaimsAreRejectedDeterministically() {
    GroundedContext context = context(List.of(
        item("e-1", SourceType.LEARNER_EVIDENCE, ContextAuthority.AUTHORITATIVE_FACT),
        item("m-1", SourceType.MASTERY, ContextAuthority.AUTHORITATIVE_FACT),
        item("p-1", SourceType.CURRICULUM_POLICY, ContextAuthority.AUTHORITATIVE_FACT)));
    ProposalGroundingRequest proposal = proposal(context, "0.9000", List.of(
        new GroundedClaim("unsupported", Set.of()),
        new GroundedClaim("fabricated", Set.of("invented-id"))));

    ProposalGateResult first = gate.evaluate(proposal, context, NOW);
    ProposalGateResult second = gate.evaluate(proposal, context, NOW);

    assertThat(first).isEqualTo(second);
    assertThat(first.accepted()).isFalse();
    assertThat(first.reasons()).containsExactly(
        ProposalGateReason.CLAIM_UNSUPPORTED,
        ProposalGateReason.EVIDENCE_REFERENCE_UNKNOWN);
  }

  @Test
  void modelSummaryAndLowSelfReportedConfidenceCannotPass() {
    GroundedContext context = context(List.of(
        item("e-1", SourceType.LEARNER_EVIDENCE, ContextAuthority.AUTHORITATIVE_FACT),
        item("m-1", SourceType.MASTERY, ContextAuthority.AUTHORITATIVE_FACT),
        item("p-1", SourceType.CURRICULUM_POLICY, ContextAuthority.AUTHORITATIVE_FACT),
        item("summary-1", SourceType.DOMAIN_POLICY, ContextAuthority.MODEL_GENERATED_SUMMARY)));

    ProposalGateResult result = gate.evaluate(proposal(context, "0.6400",
        List.of(new GroundedClaim("claim", Set.of("summary-1")))), context, NOW);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons()).containsExactly(
        ProposalGateReason.CLAIM_UNSUPPORTED,
        ProposalGateReason.CONFIDENCE_BELOW_POLICY,
        ProposalGateReason.EVIDENCE_REFERENCE_NON_AUTHORITATIVE);
  }

  @Test
  void staleOrMismatchedContextFailsClosedBeforeEvidenceEvaluation() {
    GroundedContext context = context(List.of(
        item("e-1", SourceType.LEARNER_EVIDENCE, ContextAuthority.AUTHORITATIVE_FACT),
        item("m-1", SourceType.MASTERY, ContextAuthority.AUTHORITATIVE_FACT),
        item("p-1", SourceType.CURRICULUM_POLICY, ContextAuthority.AUTHORITATIVE_FACT)));
    ProposalGroundingRequest mismatched = new ProposalGroundingRequest(
        "1.0", "proposal", "request", "run", "another-context", ProposalType.DIAGNOSTIC,
        new BigDecimal("0.9000"), List.of(new GroundedClaim("claim", Set.of("e-1"))));

    assertThat(gate.evaluate(mismatched, context, NOW).reasons())
        .containsExactly(ProposalGateReason.CONTEXT_ID_MISMATCH);
    assertThat(gate.evaluate(proposal(context, "0.9000",
        List.of(new GroundedClaim("claim", Set.of("e-1")))), context, NOW.plusSeconds(301)).reasons())
        .containsExactly(ProposalGateReason.GROUNDING_INVALID);
  }

  private static ProposalGroundingRequest proposal(
      GroundedContext context, String confidence, List<GroundedClaim> claims) {
    return new ProposalGroundingRequest("1.0", "proposal", "request", "run",
        context.contextId(), ProposalType.DIAGNOSTIC, new BigDecimal(confidence), claims);
  }

  private static GroundedContext context(List<GroundedContextItem> items) {
    return new GroundedContext("1.0", "context", "learner", NOW, NOW.plusSeconds(300),
        "GROUNDING_RETRIEVAL_V1", items);
  }

  private static GroundedContextItem item(
      String id, SourceType type, ContextAuthority authority) {
    return new GroundedContextItem(id, type, "v1", authority, "FACT", "value", NOW, null);
  }
}
