package io.ramals.learningplatform.execution.contractb;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The durable Contract B execution record, and the only place its state changes.
 *
 * <p>Every transition is a <strong>compare-and-set</strong> that names the state it expects to
 * replace, following `V035`'s dispatch ledger. Two workers racing on one execution is the normal
 * case after a process death, not an exotic one: the replacement starts while the original may
 * still be alive. A CAS makes the loser's update affect zero rows and say so, where a blind
 * {@code UPDATE ... SET state = ?} would let both believe they own it.
 *
 * <p>The write of the provider execution identity is the load-bearing one. It happens in
 * {@link #recordSubmission} and nowhere else, immediately after the provider acknowledges, because
 * the window between "the provider has accepted this" and "RAMALS knows the identity" is the only
 * window in which an execution can become unrecoverable. `V037`'s unique index on
 * {@code provider_execution_id} means two requests can never claim the same one — a duplicate
 * provider execution stays detectable rather than merely unlikely (M2-ADR-016 §3).
 */
@Repository
public class ProviderExecutionRepository {

  private final JdbcTemplate jdbc;

  public ProviderExecutionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Admits a durable execution. Idempotent on the idempotency key.
   *
   * <p>Returns false when the key is already admitted rather than throwing. A caller retrying an
   * admission it is unsure about must not be punished for asking twice — the durable row is the
   * answer, and it already exists.
   */
  public boolean admit(String requestId, String idempotencyKey, String customId,
      String provider, String model, String modelRoute) {
    try {
      jdbc.update("""
          INSERT INTO core.ai_provider_execution
            (request_id, provider, model, model_route, idempotency_key, custom_id,
             state, submit_fence, admitted_at)
          VALUES (?, ?, ?, ?, ?, ?, 'ADMITTED', 0, CURRENT_TIMESTAMP)
          """, requestId, provider, model, modelRoute, idempotencyKey, customId);
      return true;
    } catch (DuplicateKeyException alreadyAdmitted) {
      return false;
    }
  }

  /**
   * Claims the exclusive right to submit, and records the attempt <strong>before</strong> it is
   * made.
   *
   * <p>Write-ahead, and that ordering is the point rather than an implementation detail. The row
   * moves to {@code SUBMITTED} with {@code submitted_at} stamped and {@code provider_execution_id}
   * still null, which is the durable statement <em>"this request has been handed over and we do not
   * yet know what came back"</em>. Recording it after the call instead would mean a process dying
   * mid-call leaves the row in {@code ADMITTED} — indistinguishable from one never sent, and a later
   * worker would see something freshly submittable and duplicate live work at the provider.
   *
   * <p>So the two facts the schema carries are deliberately separate: {@code submitted_at} says a
   * call was made, {@code provider_execution_id} says it was acknowledged. Their combination is what
   * a recovery worker reads, and "sent, unacknowledged" is the state that becomes
   * {@code UNKNOWN_TERMINAL}.
   *
   * <p>The fence advances in the same statement that takes ownership, so a worker that pauses here
   * and wakes up later holds a fence the row no longer carries and every subsequent CAS of its
   * fails. That is what makes "exactly one provider submission per owned execution" true rather
   * than intended.
   *
   * @return the fence this caller owns, or empty when the row is not admitted or already claimed
   */
  public Optional<Long> claimForSubmission(String requestId) {
    List<Long> fences = jdbc.queryForList("""
        UPDATE core.ai_provider_execution
           SET state = 'SUBMITTED',
               submitted_at = CURRENT_TIMESTAMP,
               submit_fence = submit_fence + 1
         WHERE request_id = ?
           AND state = 'ADMITTED'
        RETURNING submit_fence
        """, Long.class, requestId);
    return fences.isEmpty() ? Optional.empty() : Optional.of(fences.get(0));
  }

  /**
   * Records the provider's acknowledgement: the execution identity.
   *
   * <p>Fenced on the claim, and refused once an identity is already present. A worker whose fence
   * has been superseded cannot write its execution identity over one another worker recorded, which
   * is the write that would make two provider executions look like one.
   */
  public boolean recordSubmission(String requestId, long fence, String providerExecutionId) {
    return jdbc.update("""
        UPDATE core.ai_provider_execution
           SET provider_execution_id = ?
         WHERE request_id = ?
           AND submit_fence = ?
           AND state = 'SUBMITTED'
           AND provider_execution_id IS NULL
        """, providerExecutionId, requestId, fence) == 1;
  }

  /** Moves between two non-terminal states, refusing when the row is not in the expected one. */
  public boolean transition(String requestId, DurableExecutionState from, DurableExecutionState to) {
    if (to.terminal()) {
      throw new IllegalArgumentException("a terminal state must be reached through finish()");
    }
    return jdbc.update("""
        UPDATE core.ai_provider_execution
           SET state = ?
         WHERE request_id = ? AND state = ?
        """, to.name(), requestId, from.name()) == 1;
  }

  /**
   * Reaches a terminal state, stamping {@code terminal_at} and any usage the provider reported.
   *
   * <p>Guarded on the row being non-terminal rather than on one specific predecessor. Terminality is
   * reached from several places — a poll, a reconciliation, a lost acknowledgement — and the
   * invariant worth enforcing is that it happens once. `V037` requires {@code terminal_at} to be
   * present exactly when the state is terminal, so the two are written together or not at all.
   */
  public boolean finish(String requestId, DurableExecutionState terminal,
      Integer inputTokens, Integer outputTokens) {
    if (!terminal.terminal()) {
      throw new IllegalArgumentException("finish() requires a terminal state, got " + terminal);
    }
    return jdbc.update("""
        UPDATE core.ai_provider_execution
           SET state = ?,
               terminal_at = CURRENT_TIMESTAMP,
               input_tokens = COALESCE(?, input_tokens),
               output_tokens = COALESCE(?, output_tokens)
         WHERE request_id = ?
           AND state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN_TERMINAL')
        """, terminal.name(), inputTokens, outputTokens, requestId) == 1;
  }

  public Optional<ProviderExecution> find(String requestId) {
    try {
      return Optional.ofNullable(jdbc.queryForObject("""
          SELECT request_id, provider, model, idempotency_key, custom_id, provider_execution_id,
                 submit_fence, state
            FROM core.ai_provider_execution
           WHERE request_id = ?
          """, ProviderExecutionRepository::map, requestId));
    } catch (EmptyResultDataAccessException absent) {
      return Optional.empty();
    }
  }

  /**
   * Non-terminal executions a worker should drive, oldest first.
   *
   * <p>Only those carrying a provider execution identity. An admitted row without one has nothing to
   * reconcile against, and a sweep that picked it up could only decide to submit — which is the one
   * decision a recovery worker must never take on its own.
   */
  public List<ProviderExecution> reconcilable(int limit) {
    return jdbc.query("""
        SELECT request_id, provider, model, idempotency_key, custom_id, provider_execution_id,
               submit_fence, state
          FROM core.ai_provider_execution
         WHERE state IN ('SUBMITTED', 'RUNNING', 'RECONCILING')
           AND provider_execution_id IS NOT NULL
         ORDER BY admitted_at
         LIMIT ?
        """, ProviderExecutionRepository::map, limit);
  }

  /**
   * Executions that are recoverable but have no work item, so nothing would ever drive them.
   *
   * <p>The work queue is an index onto the durable rows, not the record of them. A process that dies
   * between recording a provider identity and enqueueing its work item leaves an execution that is
   * perfectly recoverable and completely invisible — kill point 4. Reconciling the two is what makes
   * the durable row authoritative rather than the queue.
   */
  public List<ProviderExecution> reconcilableWithoutWork(int limit) {
    return jdbc.query("""
        SELECT execution.request_id, execution.provider, execution.model,
               execution.idempotency_key, execution.custom_id, execution.provider_execution_id,
               execution.submit_fence, execution.state
          FROM core.ai_provider_execution execution
          LEFT JOIN core.ai_reconciliation_work work ON work.request_id = execution.request_id
         WHERE execution.state IN ('SUBMITTED', 'RUNNING', 'RECONCILING')
           AND execution.provider_execution_id IS NOT NULL
           AND work.request_id IS NULL
         ORDER BY execution.admitted_at
         LIMIT ?
        """, ProviderExecutionRepository::map, limit);
  }

  /**
   * Submissions that were sent and never acknowledged, past the point where one could still be in
   * flight.
   *
   * <p>Kill points 2 and 3: {@code SUBMITTED} with no provider identity. There is nothing to poll,
   * so these can never leave that state on their own, and without this query they would sit
   * indefinitely looking like live work. Their expected outcome is {@code UNKNOWN_TERMINAL}; this
   * finds them so something can record it.
   *
   * <p>The age threshold is load-bearing rather than defensive. A submission in progress right now
   * looks exactly like one whose acknowledgement was lost, so the window must comfortably exceed one
   * provider round trip — resolving too early would terminate an execution that is about to succeed.
   */
  public List<String> sentWithoutAcknowledgement(long staleMillis, int limit) {
    return jdbc.queryForList("""
        SELECT request_id
          FROM core.ai_provider_execution
         WHERE state = 'SUBMITTED'
           AND provider_execution_id IS NULL
           AND submitted_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond')
         ORDER BY submitted_at
         LIMIT ?
        """, String.class, staleMillis, limit);
  }

  /** Enqueues reconciliation work. Idempotent: a row already queued is left as it stands. */
  public void enqueueReconciliation(String requestId) {
    jdbc.update("""
        INSERT INTO core.ai_reconciliation_work (request_id, next_attempt_at)
        VALUES (?, CURRENT_TIMESTAMP)
        ON CONFLICT (request_id) DO NOTHING
        """, requestId);
  }

  /** Records an attempt and pushes the next one out. Returns the new attempt count. */
  public int recordReconciliationAttempt(String requestId, long backoffMillis) {
    List<Integer> attempts = jdbc.queryForList("""
        UPDATE core.ai_reconciliation_work
           SET attempts = attempts + 1,
               next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
               lease_owner = NULL,
               lease_expires_at = NULL
         WHERE request_id = ?
        RETURNING attempts
        """, Integer.class, backoffMillis, requestId);
    return attempts.isEmpty() ? 0 : attempts.get(0);
  }

  /** Removes the work item once its execution is terminal. */
  public void clearReconciliation(String requestId) {
    jdbc.update("DELETE FROM core.ai_reconciliation_work WHERE request_id = ?", requestId);
  }

  /**
   * Takes a lease on one due work item, so two workers do not drive the same execution.
   *
   * <p>{@code FOR UPDATE SKIP LOCKED} rather than a status flag, matching the outbox in `V025`: the
   * lock is held for the length of the claim statement only, and a worker that dies holding a lease
   * is recovered by its expiry rather than by anyone noticing.
   */
  public List<String> leaseDue(UUID owner, long leaseMillis, int limit) {
    return jdbc.queryForList("""
        WITH due AS (
          SELECT request_id
            FROM core.ai_reconciliation_work
           WHERE next_attempt_at <= CURRENT_TIMESTAMP
             AND (lease_owner IS NULL OR lease_expires_at <= CURRENT_TIMESTAMP)
           ORDER BY next_attempt_at
           FOR UPDATE SKIP LOCKED
           LIMIT ?
        )
        UPDATE core.ai_reconciliation_work work
           SET lease_owner = ?,
               lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond')
          FROM due
         WHERE work.request_id = due.request_id
        RETURNING work.request_id
        """, String.class, limit, owner, leaseMillis);
  }

  private static ProviderExecution map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new ProviderExecution(
        rs.getString("request_id"),
        rs.getString("provider"),
        rs.getString("model"),
        rs.getString("idempotency_key"),
        rs.getString("custom_id"),
        rs.getString("provider_execution_id"),
        rs.getLong("submit_fence"),
        DurableExecutionState.of(rs.getString("state")));
  }
}
