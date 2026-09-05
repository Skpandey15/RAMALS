package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * DIAGNOSTIC_CONFIDENCE_V1 (M2-ADR-023 §2): pure, no-database tests of
 * {@link DiagnosticConfidenceCalculatorV1}'s staged, integer-only rule. Every case here is one row
 * of the boundary-vector table reviewed and approved before implementation -- see the class javadoc
 * for the rule and its justification.
 */
class DiagnosticConfidenceCalculatorV1Tests {

  private final DiagnosticConfidenceCalculatorV1 calculator = new DiagnosticConfidenceCalculatorV1();

  @Test
  void policyVersionIsDiagnosticConfidenceV1() {
    assertThat(DiagnosticConfidenceCalculatorV1.POLICY_VERSION).isEqualTo("DIAGNOSTIC_CONFIDENCE_V1");
  }

  @Test
  void noEvidenceAtAllIsInsufficientEvidence() {
    assertThat(band(0, 0)).isEqualTo(DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE);
  }

  @Test
  void onlyInconclusiveEvidenceIsStillInsufficientEvidence() {
    // INCONCLUSIVE never promotes a hypothesis out of INSUFFICIENT_EVIDENCE on its own -- it
    // contributes to neither s nor c.
    DiagnosticConfidenceResult result =
        calculator.compute(new DiagnosticConfidenceInputs(0, 0, 5));
    assertThat(result.band()).isEqualTo(DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE);
    assertThat(result.inconclusiveCount()).isEqualTo(5);
  }

  @Test
  void uncontestedSupportEscalatesAtTheFrozenThresholds() {
    assertThat(band(1, 0)).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(band(2, 0)).isEqualTo(DiagnosticConfidenceBand.MODERATE);
    assertThat(band(3, 0)).isEqualTo(DiagnosticConfidenceBand.HIGH);
    // Uncontested support keeps escalating to HIGH beyond the minimum threshold.
    assertThat(band(10, 0)).isEqualTo(DiagnosticConfidenceBand.HIGH);
  }

  @Test
  void everyApprovedBoundaryVectorClassifiesExactly() {
    assertThat(band(0, 0)).isEqualTo(DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE);
    assertThat(band(1, 0)).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(band(2, 0)).isEqualTo(DiagnosticConfidenceBand.MODERATE);
    assertThat(band(3, 0)).isEqualTo(DiagnosticConfidenceBand.HIGH);
    assertThat(band(2, 1)).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(band(3, 1)).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(band(4, 1)).isEqualTo(DiagnosticConfidenceBand.HIGH);
    assertThat(band(10, 1)).isEqualTo(DiagnosticConfidenceBand.HIGH);
    assertThat(band(3, 2)).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(band(3, 3)).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(band(4, 3)).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(band(10, 5)).isEqualTo(DiagnosticConfidenceBand.MODERATE);
    assertThat(band(100, 97)).isEqualTo(DiagnosticConfidenceBand.MODERATE);
    assertThat(band(1, 3)).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(band(0, 3)).isEqualTo(DiagnosticConfidenceBand.LOW);
  }

  @Test
  void exactDominanceBoundaryOfThreeToOneIsStrict() {
    // 3S/1C sits exactly at the 3:1 ratio (3 == 3*1) -- the strict ">" means this does not yet
    // qualify as dominant, and stays LOW. 4S/1C is the smallest case that crosses it, and reaches
    // HIGH -- proving the boundary is exact, not approximate.
    assertThat(band(3, 1)).isEqualTo(DiagnosticConfidenceBand.LOW);
    assertThat(band(4, 1)).isEqualTo(DiagnosticConfidenceBand.HIGH);
  }

  @Test
  void identicalNetMarginProducesDifferentBandsWhenProportionalDominanceDiffers() {
    // (4,1) and (100,97) both have netMargin = 3 -- the exact defect a margin-only rule would have
    // conflated. (4,1) is strongly dominant (4 > 3*1) and reaches HIGH; (100,97) is nearly balanced
    // directional evidence under this deterministic evidence-count model (100 <= 3*97) and stays at
    // MODERATE. A margin-only rule would have wrongly promoted (100,97) to HIGH alongside (4,1).
    DiagnosticConfidenceResult smallDominant = calculator.compute(new DiagnosticConfidenceInputs(4, 1, 0));
    DiagnosticConfidenceResult largeNearlyBalanced =
        calculator.compute(new DiagnosticConfidenceInputs(100, 97, 0));

    assertThat(smallDominant.supportingCount() - smallDominant.contradictoryCount())
        .isEqualTo(largeNearlyBalanced.supportingCount() - largeNearlyBalanced.contradictoryCount());
    assertThat(smallDominant.band()).isEqualTo(DiagnosticConfidenceBand.HIGH);
    assertThat(largeNearlyBalanced.band()).isEqualTo(DiagnosticConfidenceBand.MODERATE);
    assertThat(smallDominant.band()).isNotEqualTo(largeNearlyBalanced.band());
  }

  @Test
  void highIsRecoverableAfterAHistoricalContradictionGivenOverwhelmingLaterDominance() {
    // One historical contradiction does not permanently disqualify HIGH: 10S/1C is well past the
    // 3:1 dominance boundary and reaches HIGH despite the one contradiction.
    assertThat(band(10, 1)).isEqualTo(DiagnosticConfidenceBand.HIGH);
    assertThat(band(100, 1)).isEqualTo(DiagnosticConfidenceBand.HIGH);
  }

  @Test
  void balancedAndContradictionDominantEvidenceIsLow() {
    assertThat(band(3, 3)).isEqualTo(DiagnosticConfidenceBand.LOW); // balanced
    assertThat(band(1, 3)).isEqualTo(DiagnosticConfidenceBand.LOW); // contradiction-dominant
    assertThat(band(0, 3)).isEqualTo(DiagnosticConfidenceBand.LOW); // purely contradictory
  }

  @Test
  void inconclusiveObservationsNeverChangeTheDirectionalBand() {
    DiagnosticConfidenceResult withoutInconclusive =
        calculator.compute(new DiagnosticConfidenceInputs(4, 1, 0));
    DiagnosticConfidenceResult withInconclusive =
        calculator.compute(new DiagnosticConfidenceInputs(4, 1, 50));
    assertThat(withoutInconclusive.band()).isEqualTo(withInconclusive.band());
  }

  @Test
  void increasingSupportingCountForAFixedContradictoryCountNeverDecreasesConfidence() {
    int[] contradictoryCounts = {0, 1, 2, 3, 5, 97};
    for (int c : contradictoryCounts) {
      DiagnosticConfidenceBand previous = DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE;
      for (int s = 0; s <= 120; s++) {
        DiagnosticConfidenceBand current = band(s, c);
        assertThat(rank(current))
            .as("s=%d, c=%d must not rank below the previous s's band %s", s, c, previous)
            .isGreaterThanOrEqualTo(rank(previous));
        previous = current;
      }
    }
  }

  @Test
  void increasingContradictoryCountForAFixedSupportingCountNeverIncreasesConfidence() {
    // s == 0 is deliberately excluded: (0,0) is INSUFFICIENT_EVIDENCE (no evidence judged at all)
    // and (0,1) is LOW (evidence exists, all of it against) -- a rank increase, but not a case of
    // "more contradiction increasing confidence"; it is the unrelated INSUFFICIENT_EVIDENCE ->
    // judged transition already covered by the boundary-vector tests. This property is about
    // confidence strictly decreasing as contradiction accumulates once there is support to weigh
    // it against.
    int[] supportingCounts = {1, 2, 3, 5, 10, 100};
    for (int s : supportingCounts) {
      DiagnosticConfidenceBand previous = null;
      for (int c = 0; c <= 120; c++) {
        DiagnosticConfidenceBand current = band(s, c);
        if (previous != null) {
          assertThat(rank(current))
              .as("s=%d, c=%d must not rank above the previous c's band %s", s, c, previous)
              .isLessThanOrEqualTo(rank(previous));
        }
        previous = current;
      }
    }
  }

  @Test
  void sameInputsAlwaysProduceTheSameResult() {
    DiagnosticConfidenceInputs inputs = new DiagnosticConfidenceInputs(4, 1, 2);
    assertThat(calculator.compute(inputs)).isEqualTo(calculator.compute(inputs));
  }

  @Test
  void negativeCountsAreRejected() {
    assertThatThrownByNegativeSupporting();
    assertThatThrownByNegativeContradictory();
    assertThatThrownByNegativeInconclusive();
  }

  private void assertThatThrownByNegativeSupporting() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> new DiagnosticConfidenceInputs(-1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void assertThatThrownByNegativeContradictory() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> new DiagnosticConfidenceInputs(0, -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void assertThatThrownByNegativeInconclusive() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> new DiagnosticConfidenceInputs(0, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private DiagnosticConfidenceBand band(int supporting, int contradictory) {
    return calculator.compute(new DiagnosticConfidenceInputs(supporting, contradictory, 0)).band();
  }

  private static int rank(DiagnosticConfidenceBand band) {
    return switch (band) {
      case INSUFFICIENT_EVIDENCE -> 0;
      case LOW -> 1;
      case MODERATE -> 2;
      case HIGH -> 3;
    };
  }
}
