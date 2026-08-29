package io.ramals.learningplatform.execution.contractb;

/**
 * The AI plane's Contract B surface, as the platform sees it.
 *
 * <p>Three operations, and no durable state behind any of them. M2-ADR-017 §1 keeps RAMALS-AI
 * stateless: it submits, reports status and returns results, and every durable fact it produces is
 * written on this side. That is why this port has no "list my executions" — the platform already
 * knows which executions exist, and asking the AI plane would be asking the component that
 * deliberately remembers nothing.
 *
 * <p>An implementation must never retry a submission on its own. A retry after an acknowledgement
 * that was sent but not received is how one logical request becomes two provider executions, and
 * the ambiguity belongs to the caller holding the durable row.
 */
public interface DurableExecutionPort {

  /**
   * Submits once.
   *
   * <p>An implementation must classify its own failures, because the caller cannot. Only these two
   * types carry a diagnosis; anything else that escapes is, by definition, a failure nobody
   * anticipated, and the caller is obliged to assume the worst about it.
   *
   * @throws DurableExecutionRefusedException when the far side deliberately refused and created
   *     nothing. The <strong>only</strong> failure that permits a definite {@code FAILED}
   * @throws DurableSubmissionAmbiguousException when the outcome cannot be established — a timeout,
   *     a dropped connection, an unreadable response. Never conflated with a refusal
   */
  DurableSubmissionAck submit(DurableSubmissionCommand command);

  /**
   * Finds every provider execution carrying {@code customId} within a creation-time window.
   *
   * <p>The lost-acknowledgement recovery path (M2-ADR-020). <strong>Read-only</strong>: an
   * implementation must never create an execution while searching for one — that would produce the
   * duplicate the search exists to detect.
   *
   * <p>The window is the caller's because the caller holds the durable {@code submitted_at} that
   * says when the lost call happened. Correlation must be proven from batch results, never taken
   * from list metadata, which carries no correlation key at all.
   *
   * @param from window start, ISO-8601
   * @param to window end, ISO-8601
   */
  DurableExecutionSearch search(String customId, String from, String to,
      int maxInspections, java.util.Collection<String> excludeIds);

  /**
   * Searches with the ADR's own bounds and nothing already ruled out.
   *
   * <p>For callers that hold no memo — chiefly tests. Production goes through the overload above,
   * because a pass that does not spend a shared budget is the unbounded behaviour M2-ADR-020 §3.2
   * exists to prevent.
   */
  default DurableExecutionSearch search(String customId, String from, String to) {
    return search(customId, from, to, 50, java.util.List.of());
  }

  /** Reads authoritative status for an execution this process may not have started. */
  DurableStatusSnapshot status(String providerExecutionId);

  /** Retrieves one record, correlated by {@code customId} and never by position. */
  DurableResultRecord result(String providerExecutionId, String customId);
}
