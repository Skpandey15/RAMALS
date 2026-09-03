package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * EvidenceConfidenceCalculatorV2 — the same blend as V1, computed over inputs that are now
 * measurable.
 *
 * <pre>
 *   confidence        = 0.40*volume + 0.35*objectiveCoverage + 0.15*recency + 0.10*consistency
 *   volumeSufficiency = min(uniqueScoredItems / requiredEvidenceCount, 1)
 *   objectiveCoverage = requiredObjectives == 0 ? 1 : coveredRequiredObjectives / requiredObjectives
 *   recency           = max(0, 1 - latestEvidenceAgeDays / 180)
 *   consistency       = 1 - min(populationStdDev(normalizedScores) / 0.50, 1)
 * </pre>
 *
 * <p><b>Why a new identifier for an unchanged formula.</b> The defect V1 carried was never in the
 * arithmetic; it was that {@code coveredRequiredObjectives} was passed as a literal 0 by the only
 * caller, because no row recorded which objective a question assessed. Objective coverage is 35% of
 * the blend, so every V1 confidence value is capped at 0.65 — below the 0.75 threshold that
 * confirms mastery, which is why MASTERED was unreachable rather than merely hard.
 *
 * <p>Changing the weights to compensate would have been the wrong repair: it would have made the
 * model claim confidence it had not measured. What changed instead is that V046 records coverage,
 * so the input is real. But a persisted confidence of 0.6500 means something different depending on
 * which of those two worlds produced it, and nothing in the number itself says which. The version
 * identifier is that distinction, stamped on the snapshot — which is the whole reason the platform
 * versions its engines. V1 is left untouched so the snapshots already written under it stay
 * reproducible.
 *
 * <p>Difficulty-band coverage is deliberately absent from this blend. It gates mastery in
 * {@link MasteryStatusPolicyV2} as a requirement to satisfy, not as a quantity to average away:
 * a learner measured only at EASY has not partially demonstrated a skill requiring HARD.
 */
@Component
public class EvidenceConfidenceCalculatorV2 {

  public static final String ALGORITHM_VERSION = "EVIDENCE_CONFIDENCE_V2";
  static final int SCALE = 4;

  private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
  private static final MathContext MATH_CONTEXT = new MathContext(20, ROUNDING);
  private static final BigDecimal RECENCY_HORIZON_DAYS = new BigDecimal("180");
  private static final BigDecimal CONSISTENCY_SPREAD = new BigDecimal("0.50");
  private static final BigDecimal W_VOLUME = new BigDecimal("0.40");
  private static final BigDecimal W_OBJECTIVE = new BigDecimal("0.35");
  private static final BigDecimal W_RECENCY = new BigDecimal("0.15");
  private static final BigDecimal W_CONSISTENCY = new BigDecimal("0.10");

  public ConfidenceOutcome compute(ConfidenceInputs inputs) {
    if (inputs.normalizedScores().isEmpty()) {
      return new ConfidenceOutcome(scaled(BigDecimal.ZERO), scaled(BigDecimal.ZERO),
          scaled(BigDecimal.ZERO), scaled(BigDecimal.ZERO), scaled(BigDecimal.ZERO));
    }

    BigDecimal volume = ratioCapped(inputs.uniqueScoredItems(), inputs.requiredEvidenceCount());
    BigDecimal objectiveCoverage = inputs.requiredObjectives() == 0
        ? BigDecimal.ONE
        : ratioCapped(inputs.coveredRequiredObjectives(), inputs.requiredObjectives());
    BigDecimal recency = recency(inputs.latestEvidenceAgeDays());
    BigDecimal consistency = consistency(inputs.normalizedScores());

    BigDecimal confidence = W_VOLUME.multiply(volume)
        .add(W_OBJECTIVE.multiply(objectiveCoverage))
        .add(W_RECENCY.multiply(recency))
        .add(W_CONSISTENCY.multiply(consistency));

    return new ConfidenceOutcome(
        scaled(confidence), scaled(volume), scaled(objectiveCoverage),
        scaled(recency), scaled(consistency));
  }

  private BigDecimal ratioCapped(int numerator, int denominator) {
    if (denominator <= 0) {
      return BigDecimal.ONE;
    }
    BigDecimal ratio = BigDecimal.valueOf(numerator)
        .divide(BigDecimal.valueOf(denominator), MATH_CONTEXT);
    return ratio.min(BigDecimal.ONE).max(BigDecimal.ZERO);
  }

  private BigDecimal recency(long ageDays) {
    BigDecimal fraction = BigDecimal.valueOf(Math.max(ageDays, 0))
        .divide(RECENCY_HORIZON_DAYS, MATH_CONTEXT);
    return BigDecimal.ONE.subtract(fraction).max(BigDecimal.ZERO);
  }

  private BigDecimal consistency(List<BigDecimal> scores) {
    BigDecimal stdDev = populationStdDev(scores);
    BigDecimal normalized = stdDev.divide(CONSISTENCY_SPREAD, MATH_CONTEXT).min(BigDecimal.ONE);
    return BigDecimal.ONE.subtract(normalized);
  }

  private BigDecimal populationStdDev(List<BigDecimal> scores) {
    BigDecimal count = BigDecimal.valueOf(scores.size());
    BigDecimal sum = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal mean = sum.divide(count, MATH_CONTEXT);
    BigDecimal variance = scores.stream()
        .map(score -> score.subtract(mean).pow(2))
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(count, MATH_CONTEXT);
    return variance.sqrt(MATH_CONTEXT);
  }

  private BigDecimal scaled(BigDecimal value) {
    return value.setScale(SCALE, ROUNDING);
  }
}
