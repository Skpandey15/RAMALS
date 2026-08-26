package io.ramals.learningplatform.execution;

/**
 * Reads and closes the durable state of one AI execution, for callers recovering from a crash.
 *
 * <p>Dispatch is at-most-once: a diagnostic commission with no dispatch owner is recoverable, but
 * once ownership is acquired or provider invocation starts, a repeat cannot reach the model. A
 * worker which dies in that later window has to read what was recorded.
 *
 * <p>Deliberately a narrow read plus one closing write. Nothing here may dispatch, and nothing here
 * may make an owned or in-flight request dispatchable again.
 */
public interface AiExecutionRecoveryPort {

  /** What the ledger durably knows about one request identity. */
  enum ExecutionState {
    /** No commission was ever recorded. The request has not been dispatched. */
    ABSENT,
    /**
     * Commissioned with no dispatch owner. The provider has definitely not been called and a
     * replacement may compete for ownership under the same request identity.
     */
    COMMISSIONED,
    /** Dispatch ownership was acquired, but invocation has not yet been marked as started. */
    DISPATCH_OWNED,
    /** Provider invocation was durably marked as started; never blindly redispatch this state. */
    IN_FLIGHT,
    /** A pre-ownership-model commission whose provider state cannot be reconstructed safely. */
    LEGACY_INDETERMINATE,
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
   * Closes an owned or in-flight execution whose worker never returned.
   *
   * <p>Records a terminal failure so the ledger does not keep an unresolved commission forever. It
   * An ownerless diagnostic commission is deliberately not closable: it remains recoverable. This
   * does not make an owned request dispatchable again, and it does not claim to know what the
   * provider did: the error code says only that the attempt was abandoned.
   *
   * @return false when the request already had a terminal record, so nothing was changed
   */
  boolean closeAbandonedExecution(String requestId, String errorCode);
}
