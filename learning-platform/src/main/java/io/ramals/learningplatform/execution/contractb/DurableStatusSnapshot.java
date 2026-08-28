package io.ramals.learningplatform.execution.contractb;

/**
 * What the provider says about an execution now.
 *
 * @param nativeStatus the provider's own unnormalized string, kept because the transition ledger is
 *     forensic evidence rather than a dashboard, and a normalized status is a lossy summary of it
 */
public record DurableStatusSnapshot(
    String providerExecutionId,
    String state,
    String nativeStatus,
    boolean resultsAvailable,
    Integer retryAfterMillis) {}
