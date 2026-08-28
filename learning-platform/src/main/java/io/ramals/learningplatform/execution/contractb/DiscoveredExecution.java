package io.ramals.learningplatform.execution.contractb;

/**
 * One provider execution <strong>proven</strong> to carry a request's {@code custom_id}.
 *
 * <p>Proven, not inferred. Anthropic's batch list metadata carries no {@code custom_id}, so every
 * instance of this record describes an execution whose results were actually opened and read. A
 * correlation drawn from creation time instead would be a guess, and the thing it would guess wrong
 * is whose diagnosis this is.
 *
 * <p>Usage travels with the discovery because a duplicate whose tokens go unrecorded is invisible in
 * the bill however visible it is in a log — and accounting for every execution attributable to one
 * logical request is what the Definition of Done asks for.
 */
public record DiscoveredExecution(
    String providerExecutionId,
    String customId,
    String outcome,
    Integer inputTokens,
    Integer outputTokens,
    Integer cachedInputTokens,
    String providerCreatedAt,
    String providerEndedAt,
    String nativeStatus) {}
