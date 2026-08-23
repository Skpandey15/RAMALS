package io.ramals.learningplatform.execution;

/**
 * Reads and closes the durable state of one AI execution, for callers recovering from a crash.
 *
 * <p>Dispatch is at-most-once: an execution commissions in its own transaction before the provider
 * is called, precisely so a repeat cannot reach the model. The cost of that guarantee is that a
 * worker which dies mid-flight cannot ask again what happened -- it has to read what was recorded.
 *
 * <p>Deliberately a narrow read plus one closing write. Nothing here may dispatch, and nothing here
 * may make a commissioned request dispatchable again.
 */
public interface AiExecutionRecoveryPort {

  /** What the ledger durably knows about one request identity. */
  enum ExecutionState {
    /** No commission was ever recorded. The request has not been dispatched. */
    ABSENT,
    /**
     * Commissioned, with no terminal record. The provider may or may not have been called, and
     * there is no way to find out: this is the one genuinely indeterminate state.
     */
    COMMISSIONED,
    /** The call was made and failed. The error code is durable. */
    FAILED,
    /**
     * The call succeeded. Note that the proposal itself is not retained -- only its digest -- so a
     * success recorded without its gate decision cannot be re-gated from this row.
     */
    SUCCEEDED
  }

  /** One request identity's durable execution state, with the error code when there is one. */
  record RecordedExecution(ExecutionState state, String errorCode) {

    public static RecordedExecution absent() {
      return new RecordedExecution(ExecutionState.ABSENT, null);
    }
  }

  /** Reads the state of a request identity without altering its dispatchability. */
  RecordedExecution findExecutionState(String requestId);

  /**
   * Closes a commissioned execution whose worker never returned.
   *
   * <p>Records a terminal failure so the ledger does not keep an unresolved commission forever. It
   * does not make the request dispatchable again, and it does not claim to know what the provider
   * did: the error code says only that the attempt was abandoned.
   *
   * @return false when the request already had a terminal record, so nothing was changed
   */
  boolean closeAbandonedExecution(String requestId, String errorCode);
}
