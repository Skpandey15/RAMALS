package io.ramals.learningplatform.execution.contractb;

/**
 * One correlated provider record.
 *
 * <p>{@code outcome} is required and {@code text} is optional, in that order deliberately: an
 * expired or cancelled record has no text, and making text mandatory would force an empty string
 * that loses the distinction between "returned nothing" and "never ran".
 *
 * <p>{@code text} is the only RESTRICTED field on this record. It exists in memory for as long as it
 * takes to validate and seal, and is never persisted, logged or echoed in that form — which is why
 * {@code toString} is overridden rather than inherited.
 */
public record DurableResultRecord(
    String providerExecutionId,
    String outcome,
    String customId,
    String text,
    int inputTokens,
    int outputTokens,
    String providerMessageId,
    String errorCode) {

  /** Whether the provider reports this record as a completed success. */
  public boolean succeeded() {
    return "succeeded".equalsIgnoreCase(outcome);
  }

  /** Identity and shape only. The record's generated form would print the model's output. */
  @Override
  public String toString() {
    return "DurableResultRecord[providerExecutionId=" + providerExecutionId
        + ", customId=" + customId + ", outcome=" + outcome
        + ", textChars=" + (text == null ? 0 : text.length()) + "]";
  }
}
