package io.ramals.learningplatform.evidence;

import io.ramals.learningplatform.curriculum.MasteryDifficultyBand;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What one observation actually measured: which learning objectives, and at which difficulty bands.
 *
 * <p>Breadth, not correctness. An item the learner answered wrongly still measured its objective
 * and its band -- the mastery score is what accounts for the wrong answer, and conflating the two
 * would mean a learner could raise their coverage by getting things right, which is precisely the
 * circularity the confidence model exists to avoid.
 *
 * <p>{@link #none()} is the honest value for evidence whose coverage is unknown, which is every row
 * written before V046 and any observation that has no items behind it. It is not a default that
 * quietly means "all"; it means the platform cannot claim this observation covered anything.
 */
public record EvidenceCoverage(
    List<UUID> objectiveIds,
    Set<MasteryDifficultyBand> difficultyBands) {

  private static final EvidenceCoverage NONE = new EvidenceCoverage(List.of(), Set.of());

  public EvidenceCoverage {
    objectiveIds = objectiveIds == null ? List.of() : List.copyOf(objectiveIds);
    difficultyBands = difficultyBands == null ? Set.of() : Set.copyOf(difficultyBands);
  }

  /** Coverage that claims nothing. */
  public static EvidenceCoverage none() {
    return NONE;
  }

  public boolean isEmpty() {
    return objectiveIds.isEmpty() && difficultyBands.isEmpty();
  }
}
