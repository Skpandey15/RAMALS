package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.recommendation.RecommendedAction;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Atomic claim/lease and guarded state transitions for the PostgreSQL agent-work outbox. */
@Repository
public class AgentWorkOutboxRepository {

  private final JdbcTemplate jdbc;

  public AgentWorkOutboxRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  public List<ClaimedAgentWork> claim(String owner, int limit, long leaseMillis) {
    return jdbc.query("""
        WITH candidates AS (
          SELECT id
            FROM core.agent_work_outbox
           WHERE (status IN ('PENDING', 'RETRY') AND next_attempt_at <= CURRENT_TIMESTAMP)
              OR (status = 'CLAIMED' AND lease_expires_at <= CURRENT_TIMESTAMP)
           ORDER BY next_attempt_at, created_at, id
           FOR UPDATE SKIP LOCKED
           LIMIT ?
        ), claimed AS (
          UPDATE core.agent_work_outbox work
             SET status = 'CLAIMED', lease_owner = ?,
                 lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                 attempt_count = attempt_count + 1,
                 total_attempt_count = total_attempt_count + 1
            FROM candidates
           WHERE work.id = candidates.id
          RETURNING work.*
        )
        SELECT claimed.id, claimed.request_id, claimed.interaction_id, claimed.trace_id,
               claimed.agent_type, claimed.capability, claimed.source_decision_id,
               decision.learner_id, decision.skill_id, decision.recommended_action, decision.reason_code,
               claimed.attempt_count, claimed.lease_owner
          FROM claimed
          JOIN ledger.decision_record decision ON decision.id = claimed.source_decision_id
         ORDER BY claimed.created_at, claimed.id
        """, (result, row) -> new ClaimedAgentWork(
            result.getObject("id", java.util.UUID.class), result.getString("request_id"),
            result.getString("interaction_id"), result.getString("trace_id"),
            result.getString("agent_type"), result.getString("capability"),
            result.getObject("source_decision_id", java.util.UUID.class),
            result.getObject("learner_id", java.util.UUID.class),
            result.getObject("skill_id", java.util.UUID.class),
            RecommendedAction.valueOf(result.getString("recommended_action")),
            result.getString("reason_code"), result.getInt("attempt_count"),
            result.getString("lease_owner")), limit, owner, leaseMillis);
  }

  public void complete(ClaimedAgentWork work) {
    requireOne(jdbc.update("""
        UPDATE core.agent_work_outbox
           SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP,
               lease_owner = NULL, lease_expires_at = NULL, last_error_code = NULL
         WHERE id = ? AND status = 'CLAIMED' AND lease_owner = ?
        """, work.id(), work.leaseOwner()), "complete", work);
  }

  public void retry(ClaimedAgentWork work, String errorCode, long delayMillis) {
    requireOne(jdbc.update("""
        UPDATE core.agent_work_outbox
           SET status = 'RETRY', next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
               lease_owner = NULL, lease_expires_at = NULL, last_error_code = ?
         WHERE id = ? AND status = 'CLAIMED' AND lease_owner = ?
        """, delayMillis, bounded(errorCode, 64), work.id(), work.leaseOwner()), "retry", work);
  }

  public void terminal(ClaimedAgentWork work, String errorCode) {
    String reason = bounded(errorCode, 64);
    requireOne(jdbc.update("""
        UPDATE core.agent_work_outbox
           SET status = 'TERMINAL', lease_owner = NULL, lease_expires_at = NULL,
               last_error_code = ?, terminal_reason = ?
         WHERE id = ? AND status = 'CLAIMED' AND lease_owner = ?
        """, reason, reason, work.id(), work.leaseOwner()), "terminal", work);
  }

  /** Explicit operator action: starts a fresh bounded retry cycle without losing lifetime counts. */
  public void replayTerminal(UUID workId) {
    int changed = jdbc.update("""
        UPDATE core.agent_work_outbox
           SET status = 'RETRY', attempt_count = 0, replay_count = replay_count + 1,
               next_attempt_at = CURRENT_TIMESTAMP, last_error_code = NULL,
               terminal_reason = NULL, completed_at = NULL
         WHERE id = ? AND status = 'TERMINAL'
        """, workId);
    if (changed != 1) {
      throw new IllegalStateException("Agent work is not terminal or does not exist: " + workId);
    }
  }

  private static String bounded(String value, int max) {
    String safe = value == null || value.isBlank() ? "AGENT_WORK_FAILED" : value;
    return safe.length() <= max ? safe : safe.substring(0, max);
  }

  private static void requireOne(int changed, String transition, ClaimedAgentWork work) {
    if (changed != 1) {
      throw new IllegalStateException(
          "Agent work lease conflict during " + transition + ": " + work.id());
    }
  }
}
