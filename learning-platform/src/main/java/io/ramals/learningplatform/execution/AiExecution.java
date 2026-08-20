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
    /**
     * The orchestrated agent run behind this record (Observability HLD 9).
     *
     * <p>Null for executions recorded before V024 and for failures where no run
     * completed. Not defaulted to anything: the column exists to identify a run, and a
     * placeholder would be indistinguishable from a real value.
     */
    String agentRunId,
    /** Which prompt produced the proposal (M1-ADR-011). Null on the same terms. */
    String promptTemplateId,
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
