package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.evidence.Evidence;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeightedMasteryCalculatorTests {

  private static final BigDecimal T = new BigDecimal("0.80");

  private final WeightedMasteryCalculator calculator = new WeightedMasteryCalculator();

  private static Evidence evidence(String type, String normalized, int items) {
    return new Evidence(
        null, null, null, type, "ASSESSMENT_ATTEMPT", null, null, "DIAGNOSTIC_SCORING_V1",
        null, "lineage", new BigDecimal(normalized), new BigDecimal(normalized), items, items,
        "interaction", null, null);
  }

  @Test
  void weightedAverageOfSameTypeEvidence() {
    MasteryOutcome outcome = calculator.compute(
        List.of(evidence("DIAGNOSTIC", "1.0000", 1), evidence("DIAGNOSTIC", "0.0000", 1)), T, 1);

    assertThat(outcome.masteryScore()).isEqualByComparingTo("0.5000");
    assertThat(outcome.evidenceCount()).isEqualTo(2);
    assertThat(outcome.itemsConsidered()).isEqualTo(2);
    assertThat(outcome.status()).isEqualTo(MasteryStatus.NEEDS_PRACTICE);
  }

  @Test
  void evidenceTypeWeightsAreAppliedAcrossMixedTypes() {
    // DIAGNOSTIC weight 1.00 (score 0), PRACTICE weight 2.00 (score 1): (0*1 + 1*2) / (1 + 2).
    MasteryOutcome outcome = calculator.compute(
        List.of(evidence("DIAGNOSTIC", "0.0000", 1), evidence("PRACTICE", "1.0000", 1)), T, 1);

    assertThat(outcome.masteryScore()).isEqualByComparingTo("0.6667");
    assertThat(outcome.status()).isEqualTo(MasteryStatus.NEEDS_PRACTICE);
  }

  @Test
  void noEvidenceIsInsufficientWithZeroScore() {
    MasteryOutcome outcome = calculator.compute(List.of(), T, 5);

    assertThat(outcome.masteryScore()).isEqualByComparingTo("0.0000");
    assertThat(outcome.status()).isEqualTo(MasteryStatus.INSUFFICIENT_EVIDENCE);
    assertThat(outcome.itemsConsidered()).isZero();
  }

  @Test
  void highScoreButThinVolumeStaysInsufficient() {
    MasteryOutcome outcome = calculator.compute(
        List.of(evidence("DIAGNOSTIC", "1.0000", 1)), T, 5);

    assertThat(outcome.masteryScore()).isEqualByComparingTo("1.0000");
    assertThat(outcome.status()).isEqualTo(MasteryStatus.INSUFFICIENT_EVIDENCE);
  }

  @Test
  void thresholdBandsMapToStatuses() {
    assertThat(calculator.compute(List.of(evidence("DIAGNOSTIC", "1.0000", 5)), T, 5).status())
        .isEqualTo(MasteryStatus.MASTERED);
    assertThat(calculator.compute(List.of(evidence("DIAGNOSTIC", "0.7500", 5)), T, 5).status())
        .isEqualTo(MasteryStatus.DEVELOPING);
    assertThat(calculator.compute(List.of(evidence("DIAGNOSTIC", "0.6000", 5)), T, 5).status())
        .isEqualTo(MasteryStatus.NEEDS_PRACTICE);
    assertThat(calculator.compute(List.of(evidence("DIAGNOSTIC", "0.4000", 5)), T, 5).status())
        .isEqualTo(MasteryStatus.NEEDS_RETEACH);
  }

  @Test
  void adjustmentEvidenceDoesNotWeighIntoRawMastery() {
    MasteryOutcome outcome = calculator.compute(List.of(
        evidence("DIAGNOSTIC", "1.0000", 5),
        evidence("ADJUSTMENT", "0.0000", 3)), T, 5);

    assertThat(outcome.masteryScore()).isEqualByComparingTo("1.0000");
    assertThat(outcome.evidenceCount()).isEqualTo(1);
    assertThat(outcome.itemsConsidered()).isEqualTo(5);
  }

  @Test
  void computationIsExactlyReproducible() {
    List<Evidence> evidence = List.of(
        evidence("DIAGNOSTIC", "0.6000", 5), evidence("QUIZ", "0.9000", 2));

    MasteryOutcome first = calculator.compute(evidence, T, 5);
    MasteryOutcome second = calculator.compute(evidence, T, 5);

    assertThat(first.masteryScore()).isEqualByComparingTo(second.masteryScore());
    assertThat(first.masteryScore().scale()).isEqualTo(4);
    assertThat(first.status()).isEqualTo(second.status());
  }
}
