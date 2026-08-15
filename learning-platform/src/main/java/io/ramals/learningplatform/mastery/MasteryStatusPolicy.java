package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Progression-safe gating (versioned). A provisional MASTERED from the mastery score alone is only
 * confirmed when the evidence is confident enough and, where a skill configures required difficulty
 * bands, those bands are covered. Otherwise MASTERED is held back to DEVELOPING. Lower statuses are
 * unaffected: confidence never inflates mastery, it only guards the claim of mastery.
 */
@Component
public class MasteryStatusPolicy {

  public static final String POLICY_VERSION = "MASTERY_STATUS_POLICY_V1";

  public MasteryStatus refine(
      MasteryStatus provisionalStatus,
      BigDecimal confidence,
      BigDecimal confidenceThreshold,
      Set<String> requiredDifficultyBands,
      Set<String> coveredDifficultyBands) {
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
