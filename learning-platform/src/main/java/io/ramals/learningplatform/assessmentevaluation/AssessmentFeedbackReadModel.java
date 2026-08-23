package io.ramals.learningplatform.assessmentevaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Internal, already-authorized projection of the latest immutable evaluation decision. */
record AssessmentFeedbackReadModel(
    String outcome,
    String answerVersion,
    String rubricVersion,
    String feedback,
    List<RubricResult> rubricResults,
    Instant evaluatedAt) {

  AssessmentFeedbackReadModel {
    rubricResults = rubricResults == null ? List.of() : List.copyOf(rubricResults);
  }

  /** Bounded rubric content retained by the deterministic proposal gate. */
  record RubricResult(
      String dimensionId, BigDecimal score, BigDecimal maxScore, String feedback) {}
}
