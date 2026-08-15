package io.ramals.learningplatform.mastery;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * EvidenceConfidenceCalculatorV1 — deterministic, versioned sufficiency of the evidence behind a
 * mastery estimate, kept separate from the estimate itself.
 *
 * <pre>
 *   confidence        = 0.40*volume + 0.35*objectiveCoverage + 0.15*recency + 0.10*consistency
 *   volumeSufficiency = min(uniqueScoredItems / requiredEvidenceCount, 1)
 *   objectiveCoverage = requiredObjectives == 0 ? 1 : coveredRequiredObjectives / requiredObjectives
 *   recency           = max(0, 1 - latestEvidenceAgeDays / 180)
 *   consistency       = 1 - min(populationStdDev(normalizedScores) / 0.50, 1)
 * </pre>
 *
 * All arithmetic is BigDecimal at a fixed canonical scale and rounding, so the same inputs always
 * yield the identical confidence.
 */
@Component
public class EvidenceConfidenceCalculator {

  public static final String ALGORITHM_VERSION = "EVIDENCE_CONFIDENCE_V1";
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
    int n = scores.size();
    BigDecimal count = BigDecimal.valueOf(n);
    BigDecimal mean = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(count, MATH_CONTEXT);
    BigDecimal varianceSum = BigDecimal.ZERO;
    for (BigDecimal score : scores) {
      BigDecimal deviation = score.subtract(mean);
      varianceSum = varianceSum.add(deviation.multiply(deviation));
    }
    BigDecimal variance = varianceSum.divide(count, MATH_CONTEXT);
    return variance.sqrt(MATH_CONTEXT);
  }

  private BigDecimal scaled(BigDecimal value) {
    return value.setScale(SCALE, ROUNDING);
  }
}
