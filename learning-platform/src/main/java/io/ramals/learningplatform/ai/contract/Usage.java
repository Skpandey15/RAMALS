package io.ramals.learningplatform.ai.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Token and cost accounting for one proposal.
 *
 * <p>{@code latencyMs} is the sum of governed model-call durations, including bounded repair calls
 * and gateway retry/fallback handling. It is not full HTTP request or learner-interaction
 * end-to-end latency.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Usage(
    Integer inputTokens,
    Integer cachedInputTokens,
    Integer outputTokens,
    String estimatedCostUsd,
    Integer latencyMs) {
}
