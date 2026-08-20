package io.ramals.learningplatform.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable durable accounting record for one non-authoritative AI execution. */
public record AiExecution(
    UUID id,
    String requestId,
    String interactionId,
    String agentType,
    String contractVersion,
    String agentVersion,
    String promptVersion,
    String modelRoute,
    String modelId,
    String status,
    String errorCode,
    String requestDigest,
    String proposalDigest,
    Integer inputTokens,
    Integer cachedInputTokens,
    Integer outputTokens,
    BigDecimal estimatedCostUsd,
    Integer latencyMs,
    Instant startedAt,
    Instant completedAt) {}
