package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.curriculum.AssessmentDifficulty;
import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import io.ramals.learningplatform.mastery.MasterySnapshot;
import io.ramals.learningplatform.mastery.SkillMasteryConfig;
import java.math.BigDecimal;
import java.util.Set;

/**
 * What one skill's latest mastery evidence says about which difficulty band an adaptive form
 * should draw from next, and why.
 *
 * <p>A pure read of the already-computed, already-authoritative {@link MasterySnapshot} the
 * mastery engine produced -- nothing here recomputes a score, a confidence, or a threshold
 * comparison the mastery engine has not already made, and nothing here writes evidence or a
 * snapshot. It only turns those already-authoritative numbers into a selection preference, which
 * is a different question from mastery status: {@code MASTERY_CONFIRMATION} below is not a claim
 * that the loop should stop, only that this skill is not where the next question is most needed.
 *
 * <p>Priority is ascending need: {@code 0} is the skill selection should visit first. Six
 * outcomes, checked in this order once evidence exists:
 *
 * <ol>
 *   <li>No snapshot at all -- {@link SelectionReason#UNSEEN_ITEM} at FOUNDATIONAL. The diagnostic
 *       baseline case.
 *   <li>Evidence confidence below its threshold -- {@link SelectionReason#LOW_CONFIDENCE}, held at
 *       the band already evidenced. More evidence is needed before this skill's status can be
 *       trusted at all, so escalating past it would be trusting a number that is not trustworthy
 *       yet.
 *   <li>Confidence is fine but the mastery score is not -- {@link SelectionReason#WEAK_SKILL},
 *       held. A remediation-flavoured pick: more practice at the level already reached, not a
 *       harder one.
 *   <li>Score and confidence both clear their thresholds but required-objective coverage does not
 *       -- {@link SelectionReason#OBJECTIVE_COVERAGE_GAP}, held.
 *   <li>Everything clears and the evidenced band is not yet ADVANCED --
 *       {@link SelectionReason#DIFFICULTY_PROGRESSION}, exactly one band up. Never two: the next
 *       band is the immediate successor of the one already evidenced, so a learner cannot be
 *       walked past a band nothing yet says they have earned.
 *   <li>Everything clears at ADVANCED already -- {@link SelectionReason#MASTERY_CONFIRMATION}, held
 *       at ADVANCED, lowest priority.
 * </ol>
 */
public record SkillMasterySignal(
    AssessmentDifficulty targetDifficulty, SelectionReason reason, int priority) {

  private static final int PRIORITY_UNSEEN = 0;
  private static final int PRIORITY_LOW_CONFIDENCE = 1;
  private static final int PRIORITY_WEAK_SKILL = 2;
  private static final int PRIORITY_OBJECTIVE_GAP = 3;
  private static final int PRIORITY_PROGRESSION = 4;
  private static final int PRIORITY_MASTERY_CONFIRMATION = 5;

  /** No mastery snapshot exists yet for this skill: the diagnostic baseline case. */
  public static SkillMasterySignal noEvidence() {
    return new SkillMasterySignal(
        AssessmentDifficulty.FOUNDATIONAL, SelectionReason.UNSEEN_ITEM, PRIORITY_UNSEEN);
  }

  /**
   * Derives the signal from a learner's latest snapshot and the skill's configured thresholds.
   * Reads only; never mutates or recomputes either input.
   */
  public static SkillMasterySignal from(MasterySnapshot snapshot, SkillMasteryConfig config) {
    AssessmentDifficulty evidencedBand = highestEvidencedBand(snapshot.coveredDifficultyBands());
    boolean confidenceMet =
        compare(snapshot.evidenceConfidence(), config.confidenceThreshold()) >= 0;
    boolean masteryMet = compare(snapshot.masteryScore(), config.masteryThreshold()) >= 0;
    boolean objectivesCovered = compare(snapshot.objectiveCoverage(), BigDecimal.ONE) >= 0;

    if (!confidenceMet) {
      return new SkillMasterySignal(
          evidencedBand, SelectionReason.LOW_CONFIDENCE, PRIORITY_LOW_CONFIDENCE);
    }
    if (!masteryMet) {
      return new SkillMasterySignal(evidencedBand, SelectionReason.WEAK_SKILL, PRIORITY_WEAK_SKILL);
    }
    if (!objectivesCovered) {
      return new SkillMasterySignal(
          evidencedBand, SelectionReason.OBJECTIVE_COVERAGE_GAP, PRIORITY_OBJECTIVE_GAP);
    }
    if (evidencedBand != AssessmentDifficulty.ADVANCED) {
      return new SkillMasterySignal(
          nextBand(evidencedBand), SelectionReason.DIFFICULTY_PROGRESSION, PRIORITY_PROGRESSION);
    }
    return new SkillMasterySignal(
        AssessmentDifficulty.ADVANCED, SelectionReason.MASTERY_CONFIRMATION,
        PRIORITY_MASTERY_CONFIRMATION);
  }

  /** The highest band with any covered evidence, or FOUNDATIONAL if the set is empty. */
  private static AssessmentDifficulty highestEvidencedBand(Set<MasteryDifficultyBand> covered) {
    AssessmentDifficulty highest = AssessmentDifficulty.FOUNDATIONAL;
    for (AssessmentDifficulty candidate : AssessmentDifficulty.values()) {
      if (covered.contains(candidate.band())) {
        highest = candidate;
      }
    }
    return highest;
  }

  private static AssessmentDifficulty nextBand(AssessmentDifficulty current) {
    AssessmentDifficulty[] all = AssessmentDifficulty.values();
    return all[current.ordinal() + 1];
  }

  private static int compare(BigDecimal value, BigDecimal threshold) {
    return value == null ? -1 : value.compareTo(threshold);
  }
}
