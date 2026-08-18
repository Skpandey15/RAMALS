package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.ramals.learningplatform.recommendation.RecommendationDecision;
import io.ramals.learningplatform.recommendation.RecommendedAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The deterministic recommendation wins whether the adaptation proposal agrees or disagrees. */
class AdaptationProposalGateTests {

  private SimpleMeterRegistry registry;
  private AdaptationProposalGate gate;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    gate = new AdaptationProposalGate(registry);
  }

  @Test
  void agreementReturnsDeterministicDecisionAndRecordsAgreement() {
    RecommendationDecision deterministic = new RecommendationDecision(
        RecommendedAction.PRACTICE, "BELOW_PRACTICE_BOUNDARY");

    AdaptationProposalGate.Result result = gate.compare(
        deterministic,
        new AdaptationProposalGate.Proposal("KAFKA", "PRACTICE"));

    assertThat(result.deterministicDecision()).isSameAs(deterministic);
    assertThat(result.disagreement()).isFalse();
    assertThat(registry.counter(
        "ramals.ai.disagreement", "agent", "adaptation", "outcome", "agree").count())
        .isEqualTo(1);
  }

  @Test
  void disagreementStillReturnsDeterministicDecision() {
    RecommendationDecision deterministic = new RecommendationDecision(
        RecommendedAction.COLLECT_EVIDENCE, "INSUFFICIENT_EVIDENCE");

    AdaptationProposalGate.Result result = gate.compare(
        deterministic,
        new AdaptationProposalGate.Proposal("KAFKA", "ADVANCE"));

    assertThat(result.deterministicDecision()).isSameAs(deterministic);
    assertThat(result.disagreement()).isTrue();
    assertThat(registry.counter(
        "ramals.ai.disagreement", "agent", "adaptation", "outcome", "disagree").count())
        .isEqualTo(1);
  }

  @Test
  void invalidOrMissingAiActionCannotBecomeARecommendation() {
    RecommendationDecision deterministic = new RecommendationDecision(
        RecommendedAction.RETEACH, "BELOW_RETEACH_BOUNDARY");

    assertThat(gate.compare(deterministic, null).deterministicDecision())
        .isSameAs(deterministic);
    assertThat(gate.compare(
        deterministic,
        new AdaptationProposalGate.Proposal("KAFKA", "AUTHORITATIVE"))
        .deterministicDecision()).isSameAs(deterministic);
  }
}
