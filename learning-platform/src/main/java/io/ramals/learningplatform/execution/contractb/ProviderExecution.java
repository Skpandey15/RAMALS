package io.ramals.learningplatform.execution.contractb;

/**
 * One durable Contract B execution, as the platform holds it.
 *
 * <p>Bounded identifiers and a state, and nothing a model produced. The record a recovery worker
 * needs is exactly this: which provider execution to ask about, which record inside it to correlate
 * on, and what RAMALS last believed.
 *
 * @param providerExecutionId null until the provider has acknowledged. Its absence is the whole
 *     reason {@code UNKNOWN_TERMINAL} exists: without it there is nothing to poll and nothing to
 *     reconcile, so an execution whose acknowledgement was lost cannot be recovered by any means
 *     this build implements
 */
public record ProviderExecution(
    String requestId,
    String provider,
    String model,
    String idempotencyKey,
    String customId,
    String providerExecutionId,
    long submitFence,
    DurableExecutionState state) {

  /** Whether the provider has acknowledged and RAMALS durably holds the identity it returned. */
  public boolean hasProviderIdentity() {
    return providerExecutionId != null && !providerExecutionId.isBlank();
  }
}
