package io.ramals.learningplatform.execution.contractb;

/**
 * A provider plane that can die at a chosen instant, wrapping a real {@link FakeDurableExecutionPort}.
 *
 * <p>The distinction the whole harness turns on is <strong>before</strong> versus
 * <strong>after</strong> the delegate call. Dying before a submit means no provider execution
 * exists; dying after it means one does and RAMALS never learned its name. Those are different
 * situations with different correct answers, and a harness that could not express both would be
 * unable to tell a safe re-submission from a duplicate.
 *
 * <p>The submission counter lives on the delegate, so a call that was made and then lost is still
 * counted. That is the point: "no duplicate provider submission" is a claim about how many times the
 * provider was actually contacted, not about how many times RAMALS believes it was.
 */
public final class CrashingDurableExecutionPort implements DurableExecutionPort {

  /** Where the process dies, relative to the provider call. */
  public enum When {
    NEVER,
    /** Before the call reaches the provider. Nothing was created. */
    BEFORE_SUBMIT,
    /** After the provider accepted, before the caller could record anything. */
    AFTER_SUBMIT,
    BEFORE_STATUS,
    AFTER_STATUS,
    BEFORE_RESULT,
    AFTER_RESULT
  }

  private final FakeDurableExecutionPort delegate;
  private When when = When.NEVER;

  public CrashingDurableExecutionPort(FakeDurableExecutionPort delegate) {
    this.delegate = delegate;
  }

  public CrashingDurableExecutionPort dieAt(When when) {
    this.when = when;
    return this;
  }

  /** Stops dying, so the same scripted provider can serve the recovery attempt. */
  public CrashingDurableExecutionPort survive() {
    this.when = When.NEVER;
    return this;
  }

  public FakeDurableExecutionPort delegate() {
    return delegate;
  }

  @Override
  public DurableSubmissionAck submit(DurableSubmissionCommand command) {
    crashIf(When.BEFORE_SUBMIT);
    DurableSubmissionAck ack = delegate.submit(command);
    // Deliberately after the delegate has recorded the call. The provider execution exists; the
    // acknowledgement is what is lost.
    crashIf(When.AFTER_SUBMIT);
    return ack;
  }

  @Override
  public DurableStatusSnapshot status(String providerExecutionId) {
    crashIf(When.BEFORE_STATUS);
    DurableStatusSnapshot status = delegate.status(providerExecutionId);
    crashIf(When.AFTER_STATUS);
    return status;
  }

  @Override
  public DurableResultRecord result(String providerExecutionId, String customId) {
    crashIf(When.BEFORE_RESULT);
    DurableResultRecord result = delegate.result(providerExecutionId, customId);
    crashIf(When.AFTER_RESULT);
    return result;
  }

  private void crashIf(When point) {
    if (when == point) {
      throw new SimulatedProcessDeath(point.name());
    }
  }
}
