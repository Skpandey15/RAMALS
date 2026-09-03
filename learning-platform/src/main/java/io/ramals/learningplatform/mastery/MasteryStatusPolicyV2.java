package io.ramals.learningplatform.mastery;

import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * MasteryStatusPolicyV2 — progression-safe gating over difficulty bands that can actually be
 * observed.
 *
 * <p>The rule is V1's, unchanged and deliberately so: a provisional MASTERED, which the weighted
 * mastery model awards on score and evidence volume alone, is confirmed only when the evidence is
 * confident enough <em>and</em> the skill's required difficulty bands have been covered. Otherwise
 * it is held at DEVELOPING. Lower statuses are untouched — neither confidence nor coverage ever
 * raises a status, they only guard the claim of mastery.
 *
 * <p><b>What changed is that the second gate is now answerable.</b> V1 was called with an empty set
 * of covered bands by its only caller, because nothing recorded the band a learner had been
 * observed at; every seeded skill requires at least one band, so {@code containsAll} was false for
 * all of them and MASTERED was structurally unreachable. V046 records bands on the evidence row and
 * {@link MasteryService} now passes what the ledger actually shows.
 *
 * <p>Bands are typed rather than free strings. V1 compared {@code Set<String>} against
 * {@code Set<String>}, which is what allowed two different vocabularies — an item's FOUNDATIONAL
 * and a skill's EASY — to be compared and silently never match. A caller of this method cannot make
 * that mistake without first going through
 * {@link io.ramals.learningplatform.curriculum.AssessmentDifficulty}, which is where the two
 * vocabularies are related and where an unmapped value fails closed.
 */
@Component
public class MasteryStatusPolicyV2 {

  public static final String POLICY_VERSION = "MASTERY_STATUS_POLICY_V2";

  /**
   * Confirms or withholds a provisional MASTERED.
   *
   * @param provisionalStatus the weighted mastery model's verdict on score and volume alone
   * @param confidence the evidence confidence behind that score
   * @param confidenceThreshold the skill's configured confidence requirement
   * @param requiredDifficultyBands bands the skill requires; empty means the skill sets no
   *     band requirement, which is satisfied by anything
   * @param coveredDifficultyBands bands the learner's eligible evidence was actually measured at
   */
  public MasteryStatus refine(
      MasteryStatus provisionalStatus,
      BigDecimal confidence,
      BigDecimal confidenceThreshold,
      Set<MasteryDifficultyBand> requiredDifficultyBands,
      Set<MasteryDifficultyBand> coveredDifficultyBands) {
    if (provisionalStatus != MasteryStatus.MASTERED) {
      return provisionalStatus;
    }
    boolean confident = confidence.compareTo(confidenceThreshold) >= 0;
    boolean difficultyCovered = coveredDifficultyBands.containsAll(requiredDifficultyBands);
    if (confident && difficultyCovered) {
      return MasteryStatus.MASTERED;
    }
    return MasteryStatus.DEVELOPING;
  }
}
