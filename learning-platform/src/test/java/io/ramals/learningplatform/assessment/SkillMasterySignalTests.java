package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.curriculum.AssessmentDifficulty;
import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.MasteryStatus;
import io.ramals.learningplatform.mastery.SkillMasteryConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link SkillMasterySignal#from} reads an already-computed snapshot and the skill's configured
 * thresholds; it never recomputes them. Every case below asserts on the ordering documented on the
 * class: confidence, then mastery score, then objective coverage, then band escalation.
 */
class SkillMasterySignalTests {

  private static final BigDecimal CONFIDENCE_THRESHOLD = new BigDecimal("0.7500");
  private static final BigDecimal MASTERY_THRESHOLD = new BigDecimal("0.7500");

  @Test
  void noSnapshotIsUnseenAtFoundational() {
    SkillMasterySignal signal = SkillMasterySignal.noEvidence();

    assertThat(signal.targetDifficulty()).isEqualTo(AssessmentDifficulty.FOUNDATIONAL);
    assertThat(signal.reason()).isEqualTo(SelectionReason.UNSEEN_ITEM);
    assertThat(signal.priority()).isZero();
  }

  @Test
  void confidenceBelowThresholdIsLowConfidenceHeldAtTheEvidencedBand() {
    SkillMasterySignal signal = SkillMasterySignal.from(
        snapshot("0.5000", "0.9000", "1.0000",
            Set.of(MasteryDifficultyBand.MEDIUM)),
        config());

    assertThat(signal.reason()).isEqualTo(SelectionReason.LOW_CONFIDENCE);
    assertThat(signal.targetDifficulty()).isEqualTo(AssessmentDifficulty.INTERMEDIATE);
    assertThat(signal.priority()).isEqualTo(1);
  }

  @Test
  void adequateConfidenceButWeakScoreIsWeakSkillHeld() {
    SkillMasterySignal signal = SkillMasterySignal.from(
        snapshot("0.8000", "0.5000", "1.0000",
            Set.of(MasteryDifficultyBand.EASY)),
        config());

    assertThat(signal.reason()).isEqualTo(SelectionReason.WEAK_SKILL);
    assertThat(signal.targetDifficulty()).isEqualTo(AssessmentDifficulty.FOUNDATIONAL);
    assertThat(signal.priority()).isEqualTo(2);
  }

  @Test
  void strongScoreAndConfidenceButIncompleteObjectiveCoverageIsObjectiveGap() {
    SkillMasterySignal signal = SkillMasterySignal.from(
        snapshot("0.9000", "0.9000", "0.5000",
            Set.of(MasteryDifficultyBand.EASY)),
        config());

    assertThat(signal.reason()).isEqualTo(SelectionReason.OBJECTIVE_COVERAGE_GAP);
    assertThat(signal.targetDifficulty()).isEqualTo(AssessmentDifficulty.FOUNDATIONAL);
    assertThat(signal.priority()).isEqualTo(3);
  }

  @Test
  void everyThresholdClearedAtFoundationalEscalatesExactlyOneBandToIntermediate() {
    SkillMasterySignal signal = SkillMasterySignal.from(
        snapshot("0.9000", "0.9000", "1.0000",
            Set.of(MasteryDifficultyBand.EASY)),
        config());

    assertThat(signal.reason()).isEqualTo(SelectionReason.DIFFICULTY_PROGRESSION);
    assertThat(signal.targetDifficulty()).isEqualTo(AssessmentDifficulty.INTERMEDIATE);
    assertThat(signal.priority()).isEqualTo(4);
  }

  @Test
  void everyThresholdClearedAtIntermediateEscalatesToAdvancedNeverSkippingIt() {
    SkillMasterySignal signal = SkillMasterySignal.from(
        snapshot("0.9000", "0.9000", "1.0000",
            Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM)),
        config());

    assertThat(signal.targetDifficulty()).isEqualTo(AssessmentDifficulty.ADVANCED);
  }

  @Test
  void everyThresholdClearedAtAdvancedIsMasteryConfirmationNotEscalation() {
    SkillMasterySignal signal = SkillMasterySignal.from(
        snapshot("0.9000", "0.9000", "1.0000",
            Set.of(MasteryDifficultyBand.EASY, MasteryDifficultyBand.MEDIUM,
                MasteryDifficultyBand.HARD)),
        config());

    assertThat(signal.reason()).isEqualTo(SelectionReason.MASTERY_CONFIRMATION);
    assertThat(signal.targetDifficulty()).isEqualTo(AssessmentDifficulty.ADVANCED);
    assertThat(signal.priority()).isEqualTo(5);
  }

  @Test
  void noCoveredBandsYetIsTreatedAsFoundationalNotAsAbsentEvidence() {
    // A snapshot exists (evidence has been recorded) but no band's evidence has landed yet -- still
    // distinct from noEvidence(), because a mastery snapshot existing at all already changes which
    // reason applies (LOW_CONFIDENCE/WEAK_SKILL, not UNSEEN_ITEM).
    SkillMasterySignal signal = SkillMasterySignal.from(
        snapshot("0.9000", "0.9000", "1.0000", Set.of()),
        config());

    assertThat(signal.reason()).isEqualTo(SelectionReason.DIFFICULTY_PROGRESSION);
    assertThat(signal.targetDifficulty()).isEqualTo(AssessmentDifficulty.INTERMEDIATE);
  }

  private static SkillMasteryConfig config() {
    return new SkillMasteryConfig(
        MASTERY_THRESHOLD, CONFIDENCE_THRESHOLD, 4, 1, List.of("EASY", "MEDIUM"));
  }

  private static MasterySnapshot snapshot(
      String confidence, String masteryScore, String objectiveCoverage,
      Set<MasteryDifficultyBand> coveredBands) {
    return new MasterySnapshot(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        1, new BigDecimal(masteryScore), MasteryStatus.DEVELOPING, MASTERY_THRESHOLD,
        new BigDecimal(confidence), CONFIDENCE_THRESHOLD, 4, 12,
        "WEIGHTED_MASTERY_V1", "EVIDENCE_CONFIDENCE_V2", "MASTERY_STATUS_POLICY_V2",
        new BigDecimal(objectiveCoverage), coveredBands, "interaction", Instant.EPOCH);
  }
}
