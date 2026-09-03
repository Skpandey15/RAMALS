package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The V2 mastery policy, and the V1 behaviour it deliberately leaves alone.
 *
 * <p>V1 could not produce MASTERED for any seeded skill. Two independent gates saw inputs that were
 * structurally empty: objective coverage was passed as 0, capping confidence at 0.65 against a 0.75
 * threshold, and covered difficulty bands were passed as an empty set against a requirement no
 * empty set satisfies. Neither was an arithmetic error, so neither is repaired by changing
 * arithmetic. V2 is the same model reading facts the ledger now records.
 *
 * <p>These tests hold both halves at once: that V1 still behaves exactly as it did, and that V2
 * reaches MASTERED only when every documented gate is genuinely satisfied.
 */
class MasteryPolicyV2Tests {

  private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.7500");
  private static final Set<MasteryDifficultyBand> REQUIRES_EASY_AND_MEDIUM =
      Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM);

  private final EvidenceConfidenceCalculator confidenceV1 = new EvidenceConfidenceCalculator();
  private final EvidenceConfidenceCalculatorV2 confidenceV2 = new EvidenceConfidenceCalculatorV2();
  private final MasteryStatusPolicy statusV1 = new MasteryStatusPolicy();
  private final MasteryStatusPolicyV2 statusV2 = new MasteryStatusPolicyV2();

  // ---------------------------------------------------------------------------------------------
  // V1 is frozen, and its ceiling is documented rather than fixed in place.
  // ---------------------------------------------------------------------------------------------

  @Test
  void v1ConfidenceRemainsCappedAtZeroPointSixFiveWhenObjectiveCoverageIsZero() {
    // The legacy ceiling, stated as arithmetic: volume, recency and consistency can each be perfect
    // and still only sum to 0.65, because objective coverage is 35% of the blend and V1's only
    // caller passed 0. This is why MASTERED was unreachable, and it is left true on purpose --
    // every snapshot already written under V1 was computed in exactly this world.
    ConfidenceOutcome best = confidenceV1.compute(new ConfidenceInputs(
        5, 5, 0, 1, 0, List.of(new BigDecimal("1.0000"), new BigDecimal("1.0000"))));

    assertThat(best.volumeSufficiency()).isEqualByComparingTo("1.0000");
    assertThat(best.objectiveCoverage()).isEqualByComparingTo("0.0000");
    assertThat(best.recency()).isEqualByComparingTo("1.0000");
    assertThat(best.consistency()).isEqualByComparingTo("1.0000");
    assertThat(best.confidence()).isEqualByComparingTo("0.6500");
    assertThat(best.confidence()).isLessThan(CONFIDENCE_THRESHOLD);
  }

  @Test
  void v1StatusPolicyStillWithholdsMasteryOnAnEmptyCoveredSet() {
    MasteryStatus refined = statusV1.refine(
        MasteryStatus.MASTERED, new BigDecimal("1.0000"), CONFIDENCE_THRESHOLD,
        Set.of("EASY", "MEDIUM"), Set.of());

    assertThat(refined).isEqualTo(MasteryStatus.DEVELOPING);
  }

  @Test
  void v2AgreesWithV1WhereverV1CouldBeCalledHonestly() {
    // Same blend, same numbers: the repair added inputs, not arithmetic. Anywhere the inputs are
    // identical the two versions must agree, or something was quietly changed as well as fixed.
    for (int covered = 0; covered <= 2; covered++) {
      ConfidenceInputs inputs = new ConfidenceInputs(3, 5, covered, 2, 10,
          List.of(new BigDecimal("1.0000"), new BigDecimal("0.5000")));
      assertThat(confidenceV2.compute(inputs)).isEqualTo(confidenceV1.compute(inputs));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Objective coverage
  // ---------------------------------------------------------------------------------------------

  @Test
  void objectiveCoverageRisesWithGenuineCoverageAndLiftsConfidenceWithIt() {
    ConfidenceOutcome none = confidenceV2.compute(inputs(5, 5, 0, 2));
    ConfidenceOutcome half = confidenceV2.compute(inputs(5, 5, 1, 2));
    ConfidenceOutcome full = confidenceV2.compute(inputs(5, 5, 2, 2));

    assertThat(none.objectiveCoverage()).isEqualByComparingTo("0.0000");
    assertThat(half.objectiveCoverage()).isEqualByComparingTo("0.5000");
    assertThat(full.objectiveCoverage()).isEqualByComparingTo("1.0000");
    assertThat(none.confidence()).isEqualByComparingTo("0.6500");
    assertThat(half.confidence()).isEqualByComparingTo("0.8250");
    assertThat(full.confidence()).isEqualByComparingTo("1.0000");
    // The ceiling V1 could not pass is passed only once coverage is real.
    assertThat(none.confidence()).isLessThan(CONFIDENCE_THRESHOLD);
    assertThat(full.confidence()).isGreaterThanOrEqualTo(CONFIDENCE_THRESHOLD);
  }

  @Test
  void repeatedEvidenceOnOneObjectiveDoesNotBecomeFullCoverage() {
    // Twenty observations of the same objective are twenty observations of one objective. Volume
    // and coverage are different quantities, and only volume may be earned by repetition.
    ConfidenceOutcome repeated = confidenceV2.compute(new ConfidenceInputs(
        20, 5, 1, 3, 0, List.of(new BigDecimal("1.0000"))));

    assertThat(repeated.volumeSufficiency()).isEqualByComparingTo("1.0000");
    assertThat(repeated.objectiveCoverage()).isEqualByComparingTo("0.3333");

    // Worth stating plainly, because it is easy to misread this model: objective coverage is a
    // weighted component of confidence, not a gate. At 1 of 3 objectives it still contributes
    // 0.35*0.3333, and with everything else perfect the blend reaches 0.7667 -- above the
    // threshold. Breadth is enforced as a hard requirement only by the difficulty-band gate in
    // MasteryStatusPolicyV2. Narrow-but-deep evidence is therefore penalised here, not refused.
    assertThat(repeated.confidence()).isEqualByComparingTo("0.7667");
    assertThat(repeated.confidence())
        .isLessThan(confidenceV2.compute(new ConfidenceInputs(
            20, 5, 3, 3, 0, List.of(new BigDecimal("1.0000")))).confidence());
  }

  @Test
  void aSkillThatRequiresNoObjectiveIsNotPunishedForIt() {
    // requiredObjectives == 0 is "nothing to cover", not "covered nothing".
    assertThat(confidenceV2.compute(inputs(5, 5, 0, 0)).objectiveCoverage())
        .isEqualByComparingTo("1.0000");
  }

  // ---------------------------------------------------------------------------------------------
  // Difficulty-band coverage
  // ---------------------------------------------------------------------------------------------

  @Test
  void evidenceOnlyAtEasyDoesNotSatisfyASkillRequiringEasyAndMedium() {
    MasteryStatus refined = statusV2.refine(
        MasteryStatus.MASTERED, new BigDecimal("1.0000"), CONFIDENCE_THRESHOLD,
        REQUIRES_EASY_AND_MEDIUM, Set.of(MasteryDifficultyBand.EASY));

    assertThat(refined).isEqualTo(MasteryStatus.DEVELOPING);
  }

  @Test
  void evidenceAcrossTheRequiredBandsSatisfiesTheBandGate() {
    assertThat(statusV2.refine(
        MasteryStatus.MASTERED, new BigDecimal("1.0000"), CONFIDENCE_THRESHOLD,
        REQUIRES_EASY_AND_MEDIUM, REQUIRES_EASY_AND_MEDIUM))
        .isEqualTo(MasteryStatus.MASTERED);

    // Broader coverage than required also satisfies it; the rule is containment, not equality.
    assertThat(statusV2.refine(
        MasteryStatus.MASTERED, new BigDecimal("1.0000"), CONFIDENCE_THRESHOLD,
        REQUIRES_EASY_AND_MEDIUM,
        Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM,
            MasteryDifficultyBand.HARD)))
        .isEqualTo(MasteryStatus.MASTERED);
  }

  // ---------------------------------------------------------------------------------------------
  // The gates are independent, and all of them bind.
  // ---------------------------------------------------------------------------------------------

  @Test
  void aHighScoreWithInadequateCoverageIsNotMastered() {
    // Perfect confidence, missing a band.
    assertThat(statusV2.refine(
        MasteryStatus.MASTERED, new BigDecimal("1.0000"), CONFIDENCE_THRESHOLD,
        REQUIRES_EASY_AND_MEDIUM, Set.of(MasteryDifficultyBand.MEDIUM)))
        .isEqualTo(MasteryStatus.DEVELOPING);
  }

  @Test
  void adequateCoverageWithInsufficientConfidenceIsNotMastered() {
    // Every band covered, confidence one ten-thousandth short.
    assertThat(statusV2.refine(
        MasteryStatus.MASTERED, new BigDecimal("0.7499"), CONFIDENCE_THRESHOLD,
        REQUIRES_EASY_AND_MEDIUM, REQUIRES_EASY_AND_MEDIUM))
        .isEqualTo(MasteryStatus.DEVELOPING);
  }

  @Test
  void neitherGateEverRaisesAStatus() {
    // Confidence and coverage guard the claim of mastery; they never manufacture one. A learner
    // scoring badly stays where the mastery model put them however broadly they were measured.
    for (MasteryStatus provisional : List.of(MasteryStatus.INSUFFICIENT_EVIDENCE,
        MasteryStatus.NEEDS_RETEACH, MasteryStatus.NEEDS_PRACTICE, MasteryStatus.DEVELOPING)) {
      assertThat(statusV2.refine(
          provisional, new BigDecimal("1.0000"), CONFIDENCE_THRESHOLD,
          REQUIRES_EASY_AND_MEDIUM, REQUIRES_EASY_AND_MEDIUM))
          .isEqualTo(provisional);
    }
  }

  @Test
  void masteredIsReachableWhenEveryGateIsGenuinelySatisfied() {
    // Volume 5/5, both required objectives covered, fresh, consistent.
    ConfidenceOutcome confidence = confidenceV2.compute(new ConfidenceInputs(
        5, 5, 2, 2, 0,
        List.of(new BigDecimal("1.0000"), new BigDecimal("1.0000"), new BigDecimal("1.0000"))));

    MasteryStatus status = statusV2.refine(
        MasteryStatus.MASTERED, confidence.confidence(), CONFIDENCE_THRESHOLD,
        REQUIRES_EASY_AND_MEDIUM, REQUIRES_EASY_AND_MEDIUM);

    assertThat(confidence.confidence()).isGreaterThanOrEqualTo(CONFIDENCE_THRESHOLD);
    assertThat(status).isEqualTo(MasteryStatus.MASTERED);
  }

  private static ConfidenceInputs inputs(
      int items, int requiredItems, int coveredObjectives, int requiredObjectives) {
    return new ConfidenceInputs(items, requiredItems, coveredObjectives, requiredObjectives, 0,
        List.of(new BigDecimal("1.0000"), new BigDecimal("1.0000")));
  }
}
