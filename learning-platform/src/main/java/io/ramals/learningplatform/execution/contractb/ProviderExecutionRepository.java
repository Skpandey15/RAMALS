package io.ramals.learningplatform.execution.contractb;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
  public boolean admit(String requestId, String idempotencyKey,
      String provider, String model, String modelRoute) {
    return admit(requestId, idempotencyKey, provider, model, modelRoute, null, null);
  }

  /**
   * Admits an execution, recording the correlation of the request that asked for it.
   *
   * <p>Stored because reconciliation happens later, on a thread with no request behind it, and has
   * to restore this before it can call the AI plane at all (V040). Following V025's outbox: work
   * carries the correlation of its originating request so an asynchronous hand-off does not break
   * the trail back to the learner.
   */
  public boolean admit(String requestId, String idempotencyKey,
      String provider, String model, String modelRoute, String interactionId, String traceId) {
    try {
      // custom_id IS the idempotency key. Not a convention -- the Definition of Done says so:
      // "the custom_id submitted with the batch is the server-derived idempotency key for the
      // request". Taking it as a separate parameter let the two diverge, and when they did, the
      // adapter submitted one value while the platform correlated on the other: the result of a
      // finished batch became unreachable and its orphan unfindable. Found by the W2 real-provider
      // run; made structurally impossible here rather than documented as a rule.
      jdbc.update("""
          INSERT INTO core.ai_provider_execution
            (request_id, provider, model, model_route, idempotency_key, custom_id,
             state, submit_fence, admitted_at, interaction_id, trace_id)
          VALUES (?, ?, ?, ?, ?, ?, 'ADMITTED', 0, CURRENT_TIMESTAMP, ?, ?)
          """, requestId, provider, model, modelRoute, idempotencyKey, idempotencyKey,
          blankToNull(interactionId), blankToNull(traceId));
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

  /**
   * Writes a recovered provider identity onto an execution that lost its acknowledgement.
   *
   * <p>Fenced exactly as an ordinary submission is, and for the same reason: a worker that paused
   * during a long enumeration and woke up holding a stale fence must not write its recovered
   * identity over one another worker already adopted. {@code provider_execution_id IS NULL} is
   * required as well, so this can only ever fill a gap and never replace an identity.
   *
   * <p>The row stays {@code SUBMITTED}. Recovery restores what the lost acknowledgement would have
   * written and nothing more; ordinary reconciliation takes it from there, which is what keeps the
   * recovered path and the normal path the same path.
   *
   * @return true when this caller's identity was the one adopted
   */
  public boolean adoptRecoveredIdentity(String requestId, long fence, String providerExecutionId) {
    return jdbc.update("""
        UPDATE core.ai_provider_execution
           SET provider_execution_id = ?
         WHERE request_id = ?
           AND submit_fence = ?
           AND state = 'SUBMITTED'
           AND provider_execution_id IS NULL
        """, providerExecutionId, requestId, fence) == 1;
  }

  /**
   * Records one discovered provider execution, adopted or not.
   *
   * <p>{@code ON CONFLICT DO NOTHING} on {@code (request_id, provider_execution_id)}: a later sweep
   * that finds the same duplicate has learned nothing new, and a count of observations must mean
   * "how many executions exist" rather than "how many times we looked".
   *
   * @return true when this discovery was new
   */
  public boolean recordObservation(String requestId, DiscoveredExecution discovered,
      String discoveredBy) {
    return jdbc.update("""
        INSERT INTO core.ai_provider_execution_observation
          (request_id, provider_execution_id, custom_id, outcome, discovered_by,
           input_tokens, output_tokens, cached_input_tokens,
           provider_created_at, provider_ended_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
        ON CONFLICT (request_id, provider_execution_id) DO NOTHING
        """,
        requestId,
        discovered.providerExecutionId(),
        discovered.customId(),
        outcomeOf(discovered.outcome()),
        discoveredBy,
        discovered.inputTokens(),
        discovered.outputTokens(),
        discovered.cachedInputTokens(),
        discovered.providerCreatedAt(),
        discovered.providerEndedAt()) == 1;
  }

  /** Every provider execution ever observed for a request. The duplicate evidence, in one query. */
  public List<DiscoveredExecution> observations(String requestId) {
    return jdbc.query("""
        SELECT provider_execution_id, custom_id, outcome, input_tokens, output_tokens,
               cached_input_tokens, provider_created_at, provider_ended_at
          FROM core.ai_provider_execution_observation
         WHERE request_id = ?
         ORDER BY observed_at, id
        """, (rs, row) -> new DiscoveredExecution(
            rs.getString("provider_execution_id"),
            rs.getString("custom_id"),
            rs.getString("outcome"),
            (Integer) rs.getObject("input_tokens"),
            (Integer) rs.getObject("output_tokens"),
            (Integer) rs.getObject("cached_input_tokens"),
            String.valueOf(rs.getObject("provider_created_at")),
            String.valueOf(rs.getObject("provider_ended_at")),
            null), requestId);
  }

  /**
   * Maps a provider outcome onto the column's permitted set.
   *
   * <p>An unrecognised value becomes {@code unknown} rather than failing the insert. Losing the
   * observation entirely because its outcome string was unfamiliar would discard the evidence that a
   * duplicate exists — which is the one thing this table is for.
   */
  private static String outcomeOf(String outcome) {
    if (outcome == null) {
      return "unknown";
    }
    String normalized = outcome.trim().toLowerCase(java.util.Locale.ROOT);
    return switch (normalized) {
      case "succeeded", "errored", "canceled", "expired" -> normalized;
      case "cancelled" -> "canceled";
      default -> "unknown";
    };
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

  /**
   * When the submission was handed over, or null.
   *
   * <p>The search anchor. Written by the write-ahead claim <em>before</em> the provider is called,
   * which makes it the one timestamp that is definitely correct about when a lost call happened —
   * and why the enumeration window is derived from it rather than from a scan of recent batches,
   * whose width would depend on unrelated traffic.
   */
  public Instant submittedAt(String requestId) {
    return jdbc.queryForObject(
        "SELECT submitted_at FROM core.ai_provider_execution WHERE request_id = ?",
        (rs, row) -> {
          java.sql.Timestamp at = rs.getTimestamp("submitted_at");
          return at == null ? null : at.toInstant();
        }, requestId);
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

  /**
   * Records an attempt and backs the next one off exponentially, with jitter (M2-ADR-020 §7).
   *
   * <p>The delay is computed in SQL from the {@code attempts} already on the row, so one statement
   * both reads the count and acts on it — reading it first and updating after would let two workers
   * compute the same delay from the same stale count.
   *
   * <p><strong>Capped, and the cap is not decoration.</strong> Doubling from thirty seconds passes
   * twenty-six hours in about a dozen attempts, so an uncapped backoff would quietly stop retrying
   * long before the horizon that is supposed to end the search — the execution would sit
   * non-terminal, unexamined, until something else noticed. The cap keeps recovery meaningful for
   * the whole window.
   *
   * <p>The exponent is clamped before it is applied. {@code 2^attempts} overflows a bigint at
   * sixty-three attempts, and an execution retried that often is exactly the one that must not
   * suddenly get a negative delay.
   *
   * @param jitterMillis a caller-supplied random spread, so a fleet that backed off together does
   *     not return to the provider together
   */
  public int recordReconciliationAttempt(
      String requestId, long baseMillis, long maxMillis, long jitterMillis) {
    List<Integer> attempts = jdbc.queryForList("""
        UPDATE core.ai_reconciliation_work
           SET attempts = attempts + 1,
               next_attempt_at = CURRENT_TIMESTAMP + ((
                 LEAST(?::bigint, (?::bigint) * (2 ^ LEAST(attempts, 16))::bigint) + ?::bigint
               ) * INTERVAL '1 millisecond'),
               lease_owner = NULL,
               lease_expires_at = NULL
         WHERE request_id = ?
        RETURNING attempts
        """, Integer.class, maxMillis, baseMillis, jitterMillis, requestId);
    return attempts.isEmpty() ? 0 : attempts.get(0);
  }

  /**
   * Defers the next attempt by an explicit delay, without touching the attempt count.
   *
   * <p>For a provider-supplied {@code Retry-After}. The count is deliberately left alone: being told
   * to wait is not evidence that this execution is failing, and inflating its backoff for a
   * condition caused by unrelated traffic would penalise the wrong row.
   */
  public void deferReconciliation(String requestId, long delayMillis) {
    jdbc.update("""
        UPDATE core.ai_reconciliation_work
           SET next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
               lease_owner = NULL,
               lease_expires_at = NULL
         WHERE request_id = ?
        """, delayMillis, requestId);
  }

  /**
   * Batches already proven not to carry this request's key (M2-ADR-020 §3.1).
   *
   * <p>Handed to the next search so it does not open them again. This is the whole of the memo's
   * read path: coverage established once is coverage, and re-establishing it every attempt is what
   * made enumeration exceed the provider's request-rate limit.
   */
  public Set<String> excludedFromSearch(String requestId) {
    return new LinkedHashSet<>(jdbc.queryForList(
        "SELECT provider_execution_id FROM core.ai_enumeration_no_match WHERE request_id = ?",
        String.class, requestId));
  }

  /**
   * Records batches newly proven not to carry this request's key.
   *
   * <p>The caller must pass only batches that had <strong>ended</strong> and whose results streamed
   * to <strong>completion</strong>. That precondition is the entire safety argument for the memo: a
   * recorded negative counts as coverage forever, so memoising a candidate nobody actually read
   * would let a later search report {@code ZERO} — which is terminal — over a batch that might have
   * been the orphan.
   *
   * <p>{@code ON CONFLICT DO NOTHING} because two workers may prove the same negative concurrently,
   * and the second one is not news.
   *
   * @return how many rows were newly recorded
   */
  public int recordSearchExclusions(String requestId, String customId, Collection<String> ids) {
    int recorded = 0;
    for (String providerExecutionId : ids) {
      if (providerExecutionId == null || providerExecutionId.isBlank()) {
        continue;
      }
      recorded += jdbc.update("""
          INSERT INTO core.ai_enumeration_no_match (request_id, provider_execution_id, custom_id)
          VALUES (?, ?, ?)
          ON CONFLICT (request_id, provider_execution_id) DO NOTHING
          """, requestId, providerExecutionId, customId);
    }
    return recorded;
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

  /**
   * The correlation recorded when this execution was admitted, or null in both slots.
   *
   * <p>Null is a real answer, not a miss: an execution admitted before V040, or outside any request
   * scope, genuinely has no originating correlation to restore. The caller generates a fresh one
   * rather than fabricating provenance the row never had.
   */
  public String[] correlationOf(String requestId) {
    List<String[]> found = jdbc.query(
        "SELECT interaction_id, trace_id FROM core.ai_provider_execution WHERE request_id = ?",
        (rs, row) -> new String[] {rs.getString(1), rs.getString(2)}, requestId);
    return found.isEmpty() ? new String[] {null, null} : found.get(0);
  }

  /** Blank is not a value: an empty correlation is the defect V040 exists to prevent. */
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
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
