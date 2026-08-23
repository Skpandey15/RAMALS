package io.ramals.learningplatform.orchestration;

/**
 * Groups workflow writes so they commit together, or not at all.
 *
 * <p>Exists because the crash window that matters is not "the effect failed" but "the effect
 * succeeded and the workflow never found out". A step that appends evidence and then records that it
 * did so in a second transaction has a gap between them, and a process that dies in that gap leaves
 * authoritative state the workflow cannot see and will not act on.
 *
 * <p>A named port rather than a {@code TransactionTemplate} parameter, for two reasons: the
 * orchestrator says what it means at the call site, and its tests do not need a transaction manager
 * to exercise a state machine.
 *
 * <p><strong>Never wrap a model or provider call in this.</strong> Every use holds a database
 * connection for its duration, and the remote calls in this workflow take seconds.
 */
@FunctionalInterface
public interface WorkflowUnitOfWork {

  /** Runs the writes inside one transaction. */
  void inOneTransaction(Runnable writes);
}
