package io.ramals.learningplatform.assessment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Versioned, deterministic diagnostic scorer. Correctness is exact-set matching of selected options
 * against the answer key. Per-skill scores apply the approved guessing-aware normalization for
 * chance-corrected mastery evidence:
 *
 * <pre>
 *   observed        = itemsCorrect / itemsAnswered
 *   guessProbability = mean(1 / optionCount) over the skill's answered items
 *   normalized      = max(0, (observed - guessProbability) / (1 - guessProbability))
 * </pre>
 *
 * The same inputs always produce the same output, so results are reproducible and auditable.
 */
@Component
public class DiagnosticScorer {

  public static final String SCORING_VERSION = "DIAGNOSTIC_SCORING_V1";
  private static final int SCALE = 4;

  public boolean isCorrect(List<String> selectedOptions, List<String> correctOptions) {
    return Set.copyOf(selectedOptions).equals(Set.copyOf(correctOptions));
  }

  /** Aggregates persisted responses into deterministic per-skill scores, ordered by skill code. */
  public List<SkillScore> aggregate(List<ScoredResponse> responses) {
    Map<String, List<ScoredResponse>> bySkill = new TreeMap<>();
    for (ScoredResponse response : responses) {
      bySkill.computeIfAbsent(response.skillCode(), key -> new ArrayList<>()).add(response);
    }

    List<SkillScore> scores = new ArrayList<>();
    for (Map.Entry<String, List<ScoredResponse>> entry : bySkill.entrySet()) {
      List<ScoredResponse> items = entry.getValue();
      int answered = items.size();
      int correct = (int) items.stream().filter(ScoredResponse::correct).count();
      BigDecimal observed = scaled(BigDecimal.valueOf(correct), answered);
      BigDecimal guessProbability = averageGuessProbability(items);
      BigDecimal normalized = normalize(observed, guessProbability);
      scores.add(new SkillScore(entry.getKey(), answered, correct, observed, normalized));
    }
    return scores;
  }

  private BigDecimal averageGuessProbability(List<ScoredResponse> items) {
    BigDecimal sum = BigDecimal.ZERO;
    for (ScoredResponse item : items) {
      int optionCount = Math.max(item.optionCount(), 2);
      sum = sum.add(BigDecimal.ONE.divide(BigDecimal.valueOf(optionCount), 10, RoundingMode.HALF_UP));
    }
    return scaled(sum, items.size());
  }

  private BigDecimal normalize(BigDecimal observed, BigDecimal guessProbability) {
    BigDecimal denominator = BigDecimal.ONE.subtract(guessProbability);
    if (denominator.signum() <= 0) {
      return observed;
    }
    BigDecimal corrected = observed.subtract(guessProbability)
        .divide(denominator, SCALE, RoundingMode.HALF_UP);
    return corrected.signum() < 0 ? BigDecimal.ZERO.setScale(SCALE) : corrected;
  }

  private BigDecimal scaled(BigDecimal numerator, int denominator) {
    if (denominator == 0) {
      return BigDecimal.ZERO.setScale(SCALE);
    }
    return numerator.divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
  }
}
