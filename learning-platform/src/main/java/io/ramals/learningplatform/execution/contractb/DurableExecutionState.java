package io.ramals.learningplatform.execution.contractb;

import java.util.EnumSet;
import java.util.Set;

/**
 * The Contract B execution lifecycle, exactly as `V037`'s check constraint permits.
 *
 * <pre>
 *   ADMITTED ─▶ SUBMITTED ─▶ RUNNING ⇄ RECONCILING ─▶ SUCCEEDED | FAILED | CANCELLED
 *      │                                                        | UNKNOWN_TERMINAL
 *      └────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p><strong>On the name {@code UNKNOWN_TERMINAL}.</strong> It is this contract's
 * {@code INDETERMINATE}: the execution has stopped and RAMALS cannot establish what the provider
 * did. Contract A spells the same idea {@code INDETERMINATE} on {@code core.ai_execution.status},
 * and a Contract B execution that ends here records that value there. Two names for one meaning is
 * a cost, and it is paid deliberately — the words sit on different tables with different check
 * constraints, and renaming a Contract A value to match would be a change to the schema its S1–S4
 * qualification covers, which nothing here is allowed to touch.
 *
 * <p>{@code ADMITTED} may go terminal directly, and that path is the honest one: a submission whose
 * acknowledgement was lost leaves a request that may or may not have reached the provider, and
 * without an execution identity there is nothing to poll. It becomes {@code UNKNOWN_TERMINAL}
 * rather than being retried.
 */
public enum DurableExecutionState {

  /** Durably admitted, not yet handed to the provider. The only state from which a submit may run. */
  ADMITTED,

  /** The provider acknowledged, and its execution identity is durably recorded. */
  SUBMITTED,

  /** The provider is working. Reached by polling, never assumed. */
  RUNNING,

  /** A worker is establishing the outcome of an execution it did not start. */
  RECONCILING,

  /** The provider returned a result, and it validated. */
  SUCCEEDED,

  /** The provider returned a definite failure, or the result would not validate. */
  FAILED,

  /** The execution was cancelled at the provider. */
  CANCELLED,

  /**
   * Terminal and unknown — Contract B's {@code INDETERMINATE}.
   *
   * <p>Reached when a submission's acknowledgement was lost, when the provider expired the
   * execution, or when reconciliation exhausted its effort without establishing an outcome. It is
   * never a step on the way to somewhere else: M2-ADR-017 §5 forbids reinterpreting an
   * indeterminate execution later, because the provider identity needed to look it up is precisely
   * what is missing.
   */
  UNKNOWN_TERMINAL;

  private static final Set<DurableExecutionState> TERMINAL =
      EnumSet.of(SUCCEEDED, FAILED, CANCELLED, UNKNOWN_TERMINAL);

  /** Whether no further transition is possible. Matches `V037`'s own terminal set exactly. */
  public boolean terminal() {
    return TERMINAL.contains(this);
  }

  /**
   * Whether a reconciliation worker should pick this execution up.
   *
   * <p>{@code ADMITTED} is deliberately excluded. An admitted execution has no provider identity to
   * ask about, so a worker that claimed one could only guess — and the guess that matters is
   * whether to submit, which is a decision the submitting path owns and takes exactly once.
   */
  public boolean reconcilable() {
    return this == SUBMITTED || this == RUNNING || this == RECONCILING;
  }

  /**
   * The Contract A vocabulary for this outcome, for {@code core.ai_execution.status}.
   *
   * <p>{@code core.ai_execution} is the terminal record for both contracts (M2-ADR-017 §4) and its
   * check constraint admits only {@code SUCCEEDED}, {@code FAILED} and {@code INDETERMINATE}. A
   * cancelled or unknown Contract B execution is {@code INDETERMINATE} there: in both cases RAMALS
   * holds no adopted outcome, which is the only distinction that record is making.
   */
  public String terminalStatus() {
    return switch (this) {
      case SUCCEEDED -> "SUCCEEDED";
      case FAILED -> "FAILED";
      case CANCELLED, UNKNOWN_TERMINAL -> "INDETERMINATE";
      default -> throw new IllegalStateException("not a terminal state: " + this);
    };
  }

  /** Parses a stored value, refusing anything this build does not know. */
  public static DurableExecutionState of(String value) {
    try {
      return valueOf(value);
    } catch (IllegalArgumentException | NullPointerException unknown) {
      // A state this build cannot reason about is not a state to guess at. Failing here keeps a
      // future value from being silently treated as non-terminal and re-driven.
      throw new IllegalStateException("unknown contract B execution state");
    }
  }
}
