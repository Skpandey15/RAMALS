package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.recommendation.RecommendedAction;
import java.util.UUID;

/** One leased work item plus the immutable decision facts needed by the adaptation handler. */
public record ClaimedAgentWork(
    UUID id,
    String requestId,
    String interactionId,
    String traceId,
    String agentType,
    String capability,
    UUID sourceDecisionId,
    UUID skillId,
    RecommendedAction recommendedAction,
    String reasonCode,
    int attemptCount,
    String leaseOwner) {
}
