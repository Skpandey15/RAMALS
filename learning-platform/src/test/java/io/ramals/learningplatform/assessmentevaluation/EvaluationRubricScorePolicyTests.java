package io.ramals.learningplatform.assessmentevaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.DimensionResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvaluationRubricScorePolicyTests {

  @Test
  void computesTheRatioOfAllAcceptedDimensionPoints() {
    BigDecimal normalized =
        EvaluationRubricScorePolicy.normalizedScore(
            List.of(
                dimension("accuracy", "1", "4"),
                dimension("reasoning", "4", "4")));

    assertThat(normalized).isEqualByComparingTo("0.6250");
  }

  @Test
  void roundsHalfUpToTheLedgerScale() {
    assertThat(
            EvaluationRubricScorePolicy.normalizedScore(
                List.of(dimension("accuracy", "1", "3"))))
        .isEqualByComparingTo("0.3333");
  }

  @Test
  void rejectsEmptyDuplicateAndOutOfRangeDimensions() {
    assertThatThrownBy(() -> EvaluationRubricScorePolicy.normalizedScore(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EvaluationRubricScorePolicy.normalizedScore(
                    List.of(dimension("accuracy", "1", "2"), dimension("accuracy", "1", "2"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EvaluationRubricScorePolicy.normalizedScore(
                    List.of(dimension("accuracy", "3", "2"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DimensionResult dimension(String id, String score, String maximum) {
    return new DimensionResult(
        id,
        new BigDecimal(score),
        new BigDecimal(maximum),
        "approved feedback",
        Set.of("answer", "rubric"));
  }
}
