package io.ramals.learningplatform.recommendation;

import io.ramals.learningplatform.mastery.MasterySnapshot;
import org.springframework.stereotype.Component;

/**
 * Deterministic next-best-action policy (versioned). It maps a confidence-gated mastery snapshot to
 * exactly one action with a stable reason code. A high mastery score that was held back from
 * MASTERED (low confidence or missing difficulty coverage) is treated as provisionally mastered and
 * routed to collect more evidence rather than advanced. Prerequisite policy is evaluated separately.
 */
@Component
public class RecommendationPolicy {

  public static final String POLICY_VERSION = "RECOMMENDATION_POLICY_V1";

  public RecommendationDecision decide(MasterySnapshot snapshot) {
    return switch (snapshot.status()) {
      case INSUFFICIENT_EVIDENCE ->
          new RecommendationDecision(RecommendedAction.COLLECT_EVIDENCE, "INSUFFICIENT_EVIDENCE");
      case NEEDS_RETEACH ->
          new RecommendationDecision(RecommendedAction.RETEACH, "BELOW_RETEACH_BOUNDARY");
      case NEEDS_PRACTICE ->
          new RecommendationDecision(RecommendedAction.PRACTICE, "BELOW_PRACTICE_BOUNDARY");
      case DEVELOPING -> snapshot.masteryScore().compareTo(snapshot.threshold()) >= 0
          ? new RecommendationDecision(
              RecommendedAction.COLLECT_EVIDENCE, "PROVISIONALLY_MASTERED_LOW_CONFIDENCE")
          : new RecommendationDecision(RecommendedAction.PRACTICE, "APPROACHING_THRESHOLD");
      case MASTERED ->
          new RecommendationDecision(RecommendedAction.ADVANCE, "MASTERED");
    };
  }
}
