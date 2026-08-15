package io.ramals.learningplatform.recommendation;

/** The policy's decision: an action and a stable reason code explaining it. */
public record RecommendationDecision(RecommendedAction action, String reasonCode) {
}
