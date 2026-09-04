package io.ramals.learningplatform.assessment;

import java.math.BigDecimal;
import java.util.List;

/** Diagnostic submission response. Reports per-skill scores; never carries the answer key. */
public record SubmissionResultResponse(
    String attemptId,
    String status,
    String scoringVersion,
    int itemsAnswered,
    List<SkillScoreView> skillScores) {

  public record SkillScoreView(
      String skillCode,
      int itemsAnswered,
      int itemsCorrect,
      BigDecimal observedScore,
      BigDecimal normalizedScore) {
  }

  static SubmissionResultResponse from(SubmissionResult result) {
    List<SkillScoreView> views = result.skillScores().stream()
        .map(score -> new SkillScoreView(
            score.skillCode(),
            score.itemsAnswered(),
            score.itemsCorrect(),
            score.observedScore(),
            score.normalizedScore()))
        .toList();
    return new SubmissionResultResponse(
        result.attempt().id().toString(),
        result.attempt().status(),
        DiagnosticScorerV2.SCORING_VERSION,
        result.itemsAnswered(),
        views);
  }
}
