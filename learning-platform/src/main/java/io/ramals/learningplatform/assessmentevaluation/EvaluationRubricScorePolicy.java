package io.ramals.learningplatform.assessmentevaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Versioned reduction of accepted rubric dimensions to the score used by evaluation evidence.
 *
 * <p>Each dimension contributes its score and maximum directly. The normalized evaluation score is
 * the ratio of the totals, rounded to the four decimal places supported by the ledger. This policy
 * is applied before the decision is persisted; a later workflow replay reads that frozen result and
 * never asks a caller to restate or recompute it.
 */
public final class EvaluationRubricScorePolicy {

  public static final String POLICY_VERSION = "EVALUATION_SCORE_POLICY_V1";

  private static final int SCALE = 4;
  private static final int MAX_DIMENSIONS = 32;

  private EvaluationRubricScorePolicy() {}

  public static BigDecimal normalizedScore(
      List<EvaluationProposalGate.DimensionResult> dimensions) {
    if (dimensions == null || dimensions.isEmpty() || dimensions.size() > MAX_DIMENSIONS) {
      throw new IllegalArgumentException("an accepted evaluation requires bounded rubric dimensions");
    }

    BigDecimal totalScore = BigDecimal.ZERO;
    BigDecimal totalMaximum = BigDecimal.ZERO;
    Set<String> dimensionIds = new HashSet<>();
    for (EvaluationProposalGate.DimensionResult dimension : dimensions) {
      if (dimension == null
          || dimension.dimensionId() == null
          || dimension.dimensionId().isBlank()
          || !dimensionIds.add(dimension.dimensionId())
          || dimension.score() == null
          || dimension.maxScore() == null
          || dimension.maxScore().signum() <= 0
          || dimension.score().signum() < 0
          || dimension.score().compareTo(dimension.maxScore()) > 0) {
        throw new IllegalArgumentException("accepted evaluation rubric dimensions are invalid");
      }
      totalScore = totalScore.add(dimension.score());
      totalMaximum = totalMaximum.add(dimension.maxScore());
    }

    return totalScore.divide(totalMaximum, SCALE, RoundingMode.HALF_UP).setScale(SCALE);
  }
}
