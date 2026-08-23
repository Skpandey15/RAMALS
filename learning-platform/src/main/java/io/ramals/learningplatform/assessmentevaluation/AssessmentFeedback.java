package io.ramals.learningplatform.assessmentevaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Learner-safe feedback result; approved content is absent for every non-evaluated state. */
public record AssessmentFeedback(
    AssessmentFeedbackStatus status, ApprovedFeedback approvedFeedback) {

  public record ApprovedFeedback(
      String answerVersion,
      String rubricVersion,
      String feedback,
      List<RubricResult> rubricResults,
      String nextLearningRationale,
      Instant evaluatedAt) {

    public ApprovedFeedback {
      rubricResults = List.copyOf(rubricResults);
    }
  }

  public record RubricResult(
      String dimensionId, BigDecimal score, BigDecimal maxScore, String feedback) {}
}
