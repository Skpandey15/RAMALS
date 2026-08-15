package io.ramals.learningplatform.mastery;

import io.ramals.learningplatform.evidence.Evidence;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * WeightedMasteryCalculatorV1 — a versioned, deterministic control model. All arithmetic uses
 * {@link BigDecimal} at a fixed canonical scale with a fixed rounding mode, so the same evidence
 * always yields the identical persisted score.
 *
 * <pre>
 *   effectiveWeight(e) = typeWeight(e.type) * itemWeight(e)
 *   rawMastery         = SUM(normalizedScore(e) * effectiveWeight(e)) / SUM(effectiveWeight(e))
 * </pre>
 *
 * Difficulty is deliberately not folded into the weighted average (it is captured as metadata and
 * governed by progression policy). Status applies the skill's own mastery threshold T with a volume
 * gate: until enough scored items exist the skill stays INSUFFICIENT_EVIDENCE regardless of score.
 */
@Component
public class WeightedMasteryCalculator {

  public static final String ALGORITHM_VERSION = "WEIGHTED_MASTERY_V1";
  static final int SCALE = 4;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
  private static final BigDecimal RETEACH_FACTOR = new BigDecimal("0.625");
  private static final BigDecimal PRACTICE_FACTOR = new BigDecimal("0.875");

  // Versioned evidence-type weights. Single-type evidence is unaffected (the weight cancels);
  // weights only differentiate mastery once multiple evidence types are mixed.
  private static final Map<String, BigDecimal> TYPE_WEIGHTS = Map.of(
      "DIAGNOSTIC", new BigDecimal("1.00"),
      "QUIZ", new BigDecimal("1.50"),
      "PRACTICE", new BigDecimal("2.00"),
      "SCENARIO", new BigDecimal("2.50"));

  public MasteryOutcome compute(
      List<Evidence> evidence, BigDecimal threshold, int requiredEvidenceCount) {
    BigDecimal weightedSum = BigDecimal.ZERO;
    BigDecimal weightTotal = BigDecimal.ZERO;
    int evidenceCount = 0;
    int itemsConsidered = 0;

    for (Evidence observation : evidence) {
      BigDecimal typeWeight = TYPE_WEIGHTS.get(observation.evidenceType());
      if (typeWeight == null) {
        continue; // non-observation evidence (e.g. ADJUSTMENT) does not weigh into raw mastery
      }
      BigDecimal itemWeight = BigDecimal.valueOf(Math.max(observation.itemsAnswered(), 1));
      BigDecimal effectiveWeight = typeWeight.multiply(itemWeight);
      weightedSum = weightedSum.add(observation.normalizedScore().multiply(effectiveWeight));
      weightTotal = weightTotal.add(effectiveWeight);
      evidenceCount += 1;
      itemsConsidered += observation.itemsAnswered();
    }

    BigDecimal masteryScore = weightTotal.signum() == 0
        ? zero()
        : weightedSum.divide(weightTotal, SCALE, ROUNDING);
    MasteryStatus status = status(masteryScore, threshold, itemsConsidered, requiredEvidenceCount);
    return new MasteryOutcome(
        masteryScore, status, threshold.setScale(SCALE, ROUNDING), evidenceCount, itemsConsidered);
  }

  private MasteryStatus status(
      BigDecimal masteryScore, BigDecimal threshold, int itemsConsidered, int requiredEvidenceCount) {
    if (itemsConsidered <= 0 || itemsConsidered < requiredEvidenceCount) {
      return MasteryStatus.INSUFFICIENT_EVIDENCE;
    }
    BigDecimal reteachBoundary = threshold.multiply(RETEACH_FACTOR);
    BigDecimal practiceBoundary = threshold.multiply(PRACTICE_FACTOR);
    if (masteryScore.compareTo(threshold) >= 0) {
      return MasteryStatus.MASTERED;
    }
    if (masteryScore.compareTo(practiceBoundary) >= 0) {
      return MasteryStatus.DEVELOPING;
    }
    if (masteryScore.compareTo(reteachBoundary) >= 0) {
      return MasteryStatus.NEEDS_PRACTICE;
    }
    return MasteryStatus.NEEDS_RETEACH;
  }

  private BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
  }
}
