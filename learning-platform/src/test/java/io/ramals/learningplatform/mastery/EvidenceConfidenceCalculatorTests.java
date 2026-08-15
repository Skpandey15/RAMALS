package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceConfidenceCalculatorTests {

  private final EvidenceConfidenceCalculator calculator = new EvidenceConfidenceCalculator();

  private static List<BigDecimal> scores(String... values) {
    return List.of(values).stream().map(BigDecimal::new).toList();
  }

  @Test
  void volumeSufficiencyRisesToOneAtRequiredCount() {
    assertThat(calculator.compute(new ConfidenceInputs(2, 5, 0, 0, 0, scores("0.5000")))
        .volumeSufficiency()).isEqualByComparingTo("0.4000");
    assertThat(calculator.compute(new ConfidenceInputs(5, 5, 0, 0, 0, scores("0.5000")))
        .volumeSufficiency()).isEqualByComparingTo("1.0000");
    assertThat(calculator.compute(new ConfidenceInputs(10, 5, 0, 0, 0, scores("0.5000")))
        .volumeSufficiency()).isEqualByComparingTo("1.0000");
  }

  @Test
  void objectiveCoverageIsCoveredOverRequiredAndVacuousWhenNoneRequired() {
    assertThat(calculator.compute(new ConfidenceInputs(5, 5, 1, 4, 0, scores("0.5000")))
        .objectiveCoverage()).isEqualByComparingTo("0.2500");
    assertThat(calculator.compute(new ConfidenceInputs(5, 5, 0, 0, 0, scores("0.5000")))
        .objectiveCoverage()).isEqualByComparingTo("1.0000");
  }

  @Test
  void recencyDecaysLinearlyOverTheHorizonAndFloorsAtZero() {
    assertThat(calculator.compute(new ConfidenceInputs(5, 5, 0, 0, 0, scores("0.5000")))
        .recency()).isEqualByComparingTo("1.0000");
    assertThat(calculator.compute(new ConfidenceInputs(5, 5, 0, 0, 90, scores("0.5000")))
        .recency()).isEqualByComparingTo("0.5000");
    assertThat(calculator.compute(new ConfidenceInputs(5, 5, 0, 0, 360, scores("0.5000")))
        .recency()).isEqualByComparingTo("0.0000");
  }

  @Test
  void consistencyFallsAsScoreSpreadGrows() {
    assertThat(calculator.compute(new ConfidenceInputs(5, 5, 0, 0, 0, scores("0.6000", "0.6000")))
        .consistency()).isEqualByComparingTo("1.0000");
    assertThat(calculator.compute(new ConfidenceInputs(5, 5, 0, 0, 0, scores("1.0000", "0.5000")))
        .consistency()).isEqualByComparingTo("0.5000");
    assertThat(calculator.compute(new ConfidenceInputs(5, 5, 0, 0, 0, scores("1.0000", "0.0000")))
        .consistency()).isEqualByComparingTo("0.0000");
  }

  @Test
  void noEvidenceIsZeroConfidence() {
    ConfidenceOutcome outcome = calculator.compute(new ConfidenceInputs(0, 5, 0, 0, 0, List.of()));
    assertThat(outcome.confidence()).isEqualByComparingTo("0.0000");
  }

  @Test
  void sparseDiagnosticStaysLowConfidence() {
    // One scored item of a required five, no covered objective, fresh, single score.
    ConfidenceOutcome outcome = calculator.compute(
        new ConfidenceInputs(1, 5, 0, 1, 0, scores("1.0000")));

    // 0.40*0.20 + 0.35*0.00 + 0.15*1.00 + 0.10*1.00 = 0.3300
    assertThat(outcome.confidence()).isEqualByComparingTo("0.3300");
  }

  @Test
  void computationIsExactlyReproducible() {
    ConfidenceInputs inputs = new ConfidenceInputs(3, 5, 2, 4, 30, scores("0.8000", "0.6000", "0.9000"));
    ConfidenceOutcome first = calculator.compute(inputs);
    ConfidenceOutcome second = calculator.compute(inputs);

    assertThat(first.confidence()).isEqualByComparingTo(second.confidence());
    assertThat(first.confidence().scale()).isEqualTo(4);
  }
}
