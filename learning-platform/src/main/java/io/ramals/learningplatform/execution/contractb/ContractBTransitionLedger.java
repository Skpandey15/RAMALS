package io.ramals.learningplatform.execution.contractb;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Appends Contract B transition evidence, and does nothing else.
 *
 * <p>`V037` makes {@code core.ai_execution_transition} append-only by trigger and by grant: no
 * UPDATE and no DELETE, ever. It is the record that survives a purge, and the only thing that
 * explains why a result row is absent — M2-ADR-019 §2, because the absence of a row is not
 * self-describing.
 *
 * <p>Deliberately <strong>not</strong> transactional here. A transition entry describes something
 * that happened, and a caller that rolled back should not silently erase the evidence that it
 * tried — except in the one place where atomicity is the point, adoption, where the delete and its
 * ledger entry are a single statement inside {@code core.adopt_ai_execution_result}.
 *
 * <p>Records identity, states, fence and a reason code. Never a payload: this table is read by
 * anyone auditing an execution, and the whole design keeps model output out of every table but one.
 */
@Repository
public class ContractBTransitionLedger {

  private final JdbcTemplate jdbc;

  public ContractBTransitionLedger(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Records one transition.
   *
   * @param from the state left, or null when the entry marks an event rather than a move
   * @param to the state reached, or null for the same reason
   * @param reason a bounded code, never free text from a provider or a model
   */
  public void record(String requestId, DurableExecutionState from, DurableExecutionState to,
      String actor, long fence, String reason) {
    jdbc.update("""
        INSERT INTO core.ai_execution_transition
          (request_id, from_state, to_state, actor, fence, reason)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        requestId,
        from == null ? null : from.name(),
        to == null ? "EVENT" : to.name(),
        actor,
        fence,
        truncated(reason));
  }

  /**
   * Bounded to the column, in code rather than by hoping.
   *
   * <p>{@code reason} is 64 characters in `V037`. A provider status string interpolated into a
   * reason code could exceed it, and an insert that failed here would lose the evidence of the very
   * transition it was describing — the one moment the ledger must not be missing.
   */
  private static String truncated(String reason) {
    if (reason == null) {
      return null;
    }
    return reason.length() <= 64 ? reason : reason.substring(0, 64);
  }
}
