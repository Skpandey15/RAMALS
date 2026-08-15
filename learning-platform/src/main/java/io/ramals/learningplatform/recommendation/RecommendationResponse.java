package io.ramals.learningplatform.recommendation;

import java.time.Instant;
import java.util.List;

/** Learner-facing current recommendations. Provenance ids let support trace each decision. */
public record RecommendationResponse(List<Item> recommendations) {

  public record Item(
      String skillCode,
      String recommendedAction,
      String reasonCode,
      String masteryStatus,
      String decisionRecordId,
      Instant createdAt) {
  }

  static RecommendationResponse from(List<LearningRecommendation> recommendations) {
    return new RecommendationResponse(recommendations.stream()
        .map(recommendation -> new Item(
            recommendation.skillCode(),
            recommendation.recommendedAction().name(),
            recommendation.reasonCode(),
            recommendation.masteryStatus(),
            recommendation.decisionRecordId().toString(),
            recommendation.createdAt()))
        .toList());
  }
}
