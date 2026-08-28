package io.ramals.learningplatform.execution.contractb;

/**
 * The provider's acknowledgement.
 *
 * <p>{@code providerExecutionId} is the only field that must survive: it is what a replacement
 * worker asks about once the submitting process is gone. Everything else is provenance.
 */
public record DurableSubmissionAck(
    String providerExecutionId,
    String state,
    String customId,
    String createdAt,
    String expiresAt) {

  /**
   * Whether the acknowledgement carries the identity recovery depends on.
   *
   * <p>Checked rather than assumed. A 2xx response with no execution id is an acknowledgement in
   * form only — it leaves RAMALS unable to poll, which is the same position as never having heard
   * back, and it must be treated the same way.
   */
  public boolean usable() {
    return providerExecutionId != null && !providerExecutionId.isBlank();
  }
}
