package io.ramals.learningplatform.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasteryStatus;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecommendationPolicyTests {

  private final RecommendationPolicy policy = new RecommendationPolicy();

  private static MasterySnapshot snapshot(MasteryStatus status, String masteryScore) {
    return new MasterySnapshot(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
        new BigDecimal(masteryScore), status, new BigDecimal("0.8000"), new BigDecimal("0.5000"),
        new BigDecimal("0.7500"), 1, 1, "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V2",
        "MASTERY_STATUS_POLICY_V2", null, Set.of(), "interaction", null);
  }

  @Test
  void insufficientEvidenceCollectsEvidence() {
    RecommendationDecision decision = policy.decide(snapshot(MasteryStatus.INSUFFICIENT_EVIDENCE, "0.9000"));
    assertThat(decision.action()).isEqualTo(RecommendedAction.COLLECT_EVIDENCE);
    assertThat(decision.reasonCode()).isEqualTo("INSUFFICIENT_EVIDENCE");
  }

  @Test
  void needsReteachReteaches() {
    RecommendationDecision decision = policy.decide(snapshot(MasteryStatus.NEEDS_RETEACH, "0.3000"));
    assertThat(decision.action()).isEqualTo(RecommendedAction.RETEACH);
    assertThat(decision.reasonCode()).isEqualTo("BELOW_RETEACH_BOUNDARY");
  }

  @Test
  void needsPracticePractices() {
    RecommendationDecision decision = policy.decide(snapshot(MasteryStatus.NEEDS_PRACTICE, "0.6000"));
    assertThat(decision.action()).isEqualTo(RecommendedAction.PRACTICE);
    assertThat(decision.reasonCode()).isEqualTo("BELOW_PRACTICE_BOUNDARY");
  }

  @Test
  void developingBelowThresholdPractices() {
    RecommendationDecision decision = policy.decide(snapshot(MasteryStatus.DEVELOPING, "0.7200"));
    assertThat(decision.action()).isEqualTo(RecommendedAction.PRACTICE);
    assertThat(decision.reasonCode()).isEqualTo("APPROACHING_THRESHOLD");
  }

  @Test
  void developingAtOrAboveThresholdIsProvisionallyMastered() {
    RecommendationDecision decision = policy.decide(snapshot(MasteryStatus.DEVELOPING, "0.8500"));
    assertThat(decision.action()).isEqualTo(RecommendedAction.COLLECT_EVIDENCE);
    assertThat(decision.reasonCode()).isEqualTo("PROVISIONALLY_MASTERED_LOW_CONFIDENCE");
  }

  @Test
  void masteredAdvances() {
    RecommendationDecision decision = policy.decide(snapshot(MasteryStatus.MASTERED, "0.9000"));
    assertThat(decision.action()).isEqualTo(RecommendedAction.ADVANCE);
    assertThat(decision.reasonCode()).isEqualTo("MASTERED");
  }
}
