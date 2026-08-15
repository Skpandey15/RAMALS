package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MasteryStatusPolicyTests {

  private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.75");

  private final MasteryStatusPolicy policy = new MasteryStatusPolicy();

  @Test
  void nonMasteredStatusesArePassedThroughUnchanged() {
    assertThat(policy.refine(MasteryStatus.NEEDS_PRACTICE, new BigDecimal("0.90"),
        CONFIDENCE_THRESHOLD, Set.of(), Set.of())).isEqualTo(MasteryStatus.NEEDS_PRACTICE);
    assertThat(policy.refine(MasteryStatus.INSUFFICIENT_EVIDENCE, new BigDecimal("0.90"),
        CONFIDENCE_THRESHOLD, Set.of(), Set.of())).isEqualTo(MasteryStatus.INSUFFICIENT_EVIDENCE);
  }

  @Test
  void masteredIsConfirmedWhenConfidentAndNoBandsRequired() {
    assertThat(policy.refine(MasteryStatus.MASTERED, new BigDecimal("0.80"),
        CONFIDENCE_THRESHOLD, Set.of(), Set.of())).isEqualTo(MasteryStatus.MASTERED);
  }

  @Test
  void lowConfidenceHoldsMasteredBackToDeveloping() {
    assertThat(policy.refine(MasteryStatus.MASTERED, new BigDecimal("0.60"),
        CONFIDENCE_THRESHOLD, Set.of(), Set.of())).isEqualTo(MasteryStatus.DEVELOPING);
  }

  @Test
  void failedDifficultyCoverageHoldsMasteredBackToDeveloping() {
    // High mastery and confidence, but the required HARD band is not covered.
    assertThat(policy.refine(MasteryStatus.MASTERED, new BigDecimal("0.90"),
        CONFIDENCE_THRESHOLD, Set.of("HARD"), Set.of("EASY", "MEDIUM")))
        .isEqualTo(MasteryStatus.DEVELOPING);
  }

  @Test
  void masteredIsConfirmedWhenRequiredBandsAreCovered() {
    assertThat(policy.refine(MasteryStatus.MASTERED, new BigDecimal("0.90"),
        CONFIDENCE_THRESHOLD, Set.of("EASY", "MEDIUM"), Set.of("EASY", "MEDIUM", "HARD")))
        .isEqualTo(MasteryStatus.MASTERED);
  }

  @Test
  void customConfidenceThresholdChangesTheGate() {
    BigDecimal confidence = new BigDecimal("0.70");
    assertThat(policy.refine(MasteryStatus.MASTERED, confidence,
        new BigDecimal("0.65"), Set.of(), Set.of())).isEqualTo(MasteryStatus.MASTERED);
    assertThat(policy.refine(MasteryStatus.MASTERED, confidence,
        new BigDecimal("0.75"), Set.of(), Set.of())).isEqualTo(MasteryStatus.DEVELOPING);
  }
}
