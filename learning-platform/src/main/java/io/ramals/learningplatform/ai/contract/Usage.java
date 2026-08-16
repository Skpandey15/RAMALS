package io.ramals.learningplatform.ai.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Token and cost accounting for one proposal. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Usage(
    Integer inputTokens,
    Integer cachedInputTokens,
    Integer outputTokens,
    String estimatedCostUsd,
    Integer latencyMs) {
}
