-- Contract B durable recoverable AI execution (M2-ADR-017, M2-ADR-018, M2-ADR-019).
--
-- Four dedicated tables and two purge mechanisms. Nothing here touches core.ai_execution,
-- core.ai_execution_event or core.ai_execution_dispatch: Contract A's qualification (S1-S4) covers
-- those tables as they stand, and M2-ADR-017 §3/§4 keeps their structural-redaction guarantee
-- intact by adding a separate table rather than widening theirs. core.ai_execution remains the
-- terminal record for both contracts, correlated on request_id.
--
-- This migration creates schema and mechanism. It activates nothing: no route is bound to
-- Contract B, and every table below is empty until a Contract B execution path is switched on.

-- ------------------------------------------------------------------------------------------------
-- 1. The external execution handle
-- ------------------------------------------------------------------------------------------------
--
-- Identifiers, states, fences, counters and timestamps. No free-text column, so the V023 property
-- holds here too: a prompt, a context package or model output cannot be stored in this table even
-- by a caller that tried.
--
-- custom_id and provider_execution_id are held unencrypted on purpose (M2-ADR-018 §6): the
-- reconciliation sweep matches on them, and encrypting them would make the one guarantee Contract B
-- actually claims -- detectable duplicate provider execution -- unenforceable.

CREATE TABLE core.ai_provider_execution (
  request_id            VARCHAR(64) PRIMARY KEY,
  provider              VARCHAR(32)  NOT NULL,
  model                 VARCHAR(128) NOT NULL,
  model_route           VARCHAR(64),
  idempotency_key       VARCHAR(128) NOT NULL,
  custom_id             VARCHAR(128) NOT NULL,
  provider_execution_id VARCHAR(128),
  submit_fence          BIGINT       NOT NULL DEFAULT 0,
  state                 VARCHAR(24)  NOT NULL,
  input_tokens          INTEGER,
  output_tokens         INTEGER,
  estimated_cost_usd    NUMERIC(18, 8),
  admitted_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  submitted_at          TIMESTAMPTZ,
  terminal_at           TIMESTAMPTZ,
  CONSTRAINT uq_ai_provider_execution_idempotency UNIQUE (idempotency_key),
  CONSTRAINT ck_ai_provider_execution_state CHECK (state IN (
    'ADMITTED', 'SUBMITTED', 'RUNNING', 'RECONCILING',
    'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN_TERMINAL')),
  CONSTRAINT ck_ai_provider_execution_fence CHECK (submit_fence >= 0),
  CONSTRAINT ck_ai_provider_execution_ids CHECK (
    length(btrim(request_id)) > 0 AND length(btrim(custom_id)) > 0
    AND length(btrim(idempotency_key)) > 0),
  CONSTRAINT ck_ai_provider_execution_usage CHECK (
    (input_tokens IS NULL OR input_tokens >= 0)
    AND (output_tokens IS NULL OR output_tokens >= 0)),
  -- A terminal state must say when it became terminal, because the sweep's eligibility test and
  -- the cost evidence both read it. A terminal row with a null timestamp would be invisible to one
  -- and unattributable in the other.
  CONSTRAINT ck_ai_provider_execution_terminal CHECK (
    (state IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN_TERMINAL')) = (terminal_at IS NOT NULL)),
  -- A submitted execution has been handed to the provider; before that there is nothing to
  -- reconcile against and no handle to record.
  CONSTRAINT ck_ai_provider_execution_submitted CHECK (
    state = 'ADMITTED' OR (submitted_at IS NOT NULL AND submit_fence > 0))
);

-- Partial and unique: two RAMALS requests must never claim the same provider execution. That is how
-- a duplicate provider execution becomes detectable rather than merely unlikely.
CREATE UNIQUE INDEX uq_ai_provider_execution_provider_id
  ON core.ai_provider_execution(provider_execution_id)
  WHERE provider_execution_id IS NOT NULL;

CREATE INDEX ix_ai_provider_execution_state
  ON core.ai_provider_execution(state, admitted_at);

COMMENT ON TABLE core.ai_provider_execution IS
  'Contract B external execution handle (M2-ADR-017 §4). Identifiers, states, fences, usage and '
  'timestamps only -- never prompts, context or model output, enforced by the absence of any '
  'free-text column. Correlates to core.ai_execution on request_id.';
COMMENT ON COLUMN core.ai_provider_execution.custom_id IS
  'Provider-side correlation key. Individual batch results are correlated by this value and never '
  'by position (M2-ADR-016 §3). Held unencrypted because reconciliation matches on it.';

-- ------------------------------------------------------------------------------------------------
-- 2. The result -- the only table in this schema permitted to contain model output
-- ------------------------------------------------------------------------------------------------
--
-- RESTRICTED -- LEARNER-DERIVED MODEL OUTPUT (M2-ADR-018 §1). Ciphertext only. The application
-- seals with AES-256-GCM before the value reaches this column and the database never holds the key
-- (M2-ADR-018 §7), which is why pgcrypto was rejected: it would put the key in the SQL statement
-- and from there into query logs.

CREATE TABLE core.ai_execution_result (
  request_id            VARCHAR(64) PRIMARY KEY
                          REFERENCES core.ai_provider_execution(request_id) ON DELETE CASCADE,
  provider_execution_id VARCHAR(128) NOT NULL,
  normalized_result     BYTEA        NOT NULL,
  encryption_key_id     VARCHAR(64)  NOT NULL,
  result_digest         CHAR(64)     NOT NULL,
  result_schema         VARCHAR(64)  NOT NULL,
  stored_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  purge_after           TIMESTAMPTZ  NOT NULL,
  CONSTRAINT ck_ai_execution_result_digest CHECK (result_digest ~ '^[0-9a-f]{64}$'),
  -- The stored document is validated against a named committed schema before encryption. The
  -- column records which one, so a row cannot claim to be a normalized proposal without saying
  -- against what -- and a future contract version is a new value here rather than a silent change
  -- of meaning.
  CONSTRAINT ck_ai_execution_result_schema CHECK (result_schema IN ('diagnostic-proposal.v1')),
  -- Fail closed on plaintext, in the schema rather than in a review comment.
  --
  -- The envelope of M2-ADR-018 §7 begins version(1) | key_id_len(1), so byte 0 is 1 and byte 1 is a
  -- non-zero key-id length. A JSON document starts with '{' (0x7B) or whitespace and fails both.
  -- This does not prove the payload is *encrypted* -- nothing in SQL can -- but it does make the
  -- specific accident this design most fears, writing the plaintext proposal into the ciphertext
  -- column, impossible to commit rather than merely unlikely.
  CONSTRAINT ck_ai_execution_result_envelope CHECK (
    length(normalized_result) >= 31
    AND get_byte(normalized_result, 0) = 1
    AND get_byte(normalized_result, 1) BETWEEN 1 AND 64
    AND length(normalized_result) > 2 + get_byte(normalized_result, 1) + 12),
  -- The 30-day ceiling is structural. A caller cannot write a row that outlives it, so the sweep
  -- bounds exposure and this bounds the sweep's input.
  CONSTRAINT ck_ai_execution_result_ceiling CHECK (
    purge_after > stored_at AND purge_after <= stored_at + INTERVAL '30 days')
);

CREATE INDEX ix_ai_execution_result_purge ON core.ai_execution_result(stored_at);

-- UPDATE rejected, DELETE permitted -- the inverse of V021/V022, and deliberately (M2-ADR-018 §9).
-- Copying their DELETE-rejecting trigger here would make delete-on-adoption unimplementable, and
-- delete-on-adoption is the retention policy rather than an exception to it. A result is immutable
-- while it exists and is meant to stop existing quickly.
CREATE FUNCTION core.reject_ai_execution_result_update()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'contract B results are immutable once written' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_ai_execution_result_immutable
BEFORE UPDATE ON core.ai_execution_result
FOR EACH ROW EXECUTE FUNCTION core.reject_ai_execution_result_update();

COMMENT ON TABLE core.ai_execution_result IS
  'RESTRICTED -- LEARNER-DERIVED MODEL OUTPUT (M2-ADR-018 §1). The only table in this schema that '
  'may contain model output, and it holds only AES-256-GCM ciphertext sealed by the application '
  'with the request identity as AAD. Retention: deleted in the adoption transaction, with a '
  '30-day hard ceiling. Reporting, analytics, evaluation and the AI plane hold no grant here.';
COMMENT ON COLUMN core.ai_execution_result.normalized_result IS
  'Envelope: version(1) | key_id_len(1) | key_id | nonce(12) | ciphertext+tag. Never plaintext -- '
  'a check constraint rejects anything that is not shaped like the envelope.';

-- ------------------------------------------------------------------------------------------------
-- 3. The transition ledger -- what survives a purge
-- ------------------------------------------------------------------------------------------------
--
-- Append-only in the strong sense: no UPDATE and no DELETE, ever. M2-ADR-019 §2 makes this the
-- durable evidence that a purge happened, because the absence of a result row is not
-- self-describing. A ledger that could be swept alongside the results it records would leave an
-- auditor unable to distinguish a purged execution from one that never produced a result.

CREATE TABLE core.ai_execution_transition (
  id          BIGSERIAL    PRIMARY KEY,
  request_id  VARCHAR(64)  NOT NULL,
  from_state  VARCHAR(32),
  to_state    VARCHAR(32)  NOT NULL,
  actor       VARCHAR(32)  NOT NULL,
  fence       BIGINT,
  reason      VARCHAR(64),
  occurred_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ai_execution_transition_ids CHECK (
    length(btrim(request_id)) > 0 AND length(btrim(to_state)) > 0
    AND length(btrim(actor)) > 0),
  CONSTRAINT ck_ai_execution_transition_fence CHECK (fence IS NULL OR fence >= 0)
);

CREATE INDEX ix_ai_execution_transition_request
  ON core.ai_execution_transition(request_id, occurred_at);

CREATE FUNCTION core.reject_ai_execution_transition_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'contract B transition evidence is append-only' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_ai_execution_transition_append_only
BEFORE UPDATE OR DELETE ON core.ai_execution_transition
FOR EACH ROW EXECUTE FUNCTION core.reject_ai_execution_transition_mutation();

COMMENT ON TABLE core.ai_execution_transition IS
  'Append-only Contract B transition evidence (M2-ADR-017 §4, M2-ADR-019 §2). Records the purge '
  'path that removed a result, which is the durable evidence a deleted row cannot provide. Never '
  'purged: bounded metadata, and the only thing that explains an absent result.';

-- ------------------------------------------------------------------------------------------------
-- 4. Reconciliation work
-- ------------------------------------------------------------------------------------------------

CREATE TABLE core.ai_reconciliation_work (
  request_id       VARCHAR(64) PRIMARY KEY
                     REFERENCES core.ai_provider_execution(request_id) ON DELETE CASCADE,
  lease_owner      UUID,
  lease_expires_at TIMESTAMPTZ,
  fence            BIGINT      NOT NULL DEFAULT 0,
  attempts         INTEGER     NOT NULL DEFAULT 0,
  next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ai_reconciliation_work_fence CHECK (fence >= 0),
  CONSTRAINT ck_ai_reconciliation_work_attempts CHECK (attempts >= 0),
  -- A lease is an owner and an expiry together. Half a lease is either a worker that can never be
  -- evicted or an expiry nobody holds, and V035's fencing discipline exists to prevent both.
  CONSTRAINT ck_ai_reconciliation_work_lease CHECK (
    (lease_owner IS NULL) = (lease_expires_at IS NULL))
);

CREATE INDEX ix_ai_reconciliation_work_due
  ON core.ai_reconciliation_work(next_attempt_at)
  WHERE lease_owner IS NULL;

COMMENT ON TABLE core.ai_reconciliation_work IS
  'Contract B reconciliation queue (M2-ADR-017 §4): lease, fence, attempt count and scheduling. '
  'Owned by the platform runtime; the AI plane is stateless and has no access.';

-- ------------------------------------------------------------------------------------------------
-- 5. Purge mechanism 1 -- delete on adoption
-- ------------------------------------------------------------------------------------------------
--
-- Targeted: by primary key, one row, no window. It removes exactly the result whose outcome the
-- caller is adopting and cannot be asked to remove anything else (M2-ADR-019 §3).
--
-- A function rather than a bare DELETE in the repository, for one reason: the ledger entry must not
-- be separable from the deletion. Two statements in a service method can drift -- an early return,
-- a refactor, an exception between them -- and the result would be a row that vanished with nothing
-- recording which path removed it. Here the delete and its evidence are one statement.
--
-- It takes no transaction of its own. Called inside the adoption transaction, it commits and rolls
-- back with the gate decision, which is what makes the exposure exactly the transaction boundary.

CREATE FUNCTION core.adopt_ai_execution_result(p_request_id VARCHAR)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
  removed INTEGER;
BEGIN
  DELETE FROM core.ai_execution_result WHERE request_id = p_request_id;
  GET DIAGNOSTICS removed = ROW_COUNT;

  -- Evidence only when a row was actually removed. A second adoption of the same request is a
  -- no-op that reports zero rather than an error (M2-ADR-019 §2), and must not manufacture a
  -- second claim that a purge happened.
  IF removed > 0 THEN
    INSERT INTO core.ai_execution_transition
      (request_id, from_state, to_state, actor, reason)
    VALUES
      (p_request_id, 'RESULT_AVAILABLE', 'PURGED_ON_ADOPTION', 'ADOPTER', 'ADOPTED');
  END IF;

  RETURN removed;
END;
$$;

COMMENT ON FUNCTION core.adopt_ai_execution_result(VARCHAR) IS
  'Deletes one Contract B result by request identity and records the purge in the transition '
  'ledger, as one statement. Called inside the adoption transaction so the result disappears with '
  'the gate decision that made it redundant (M2-ADR-018 §9). Idempotent: a repeat returns 0.';

-- ------------------------------------------------------------------------------------------------
-- 6. Purge mechanism 2 -- the ceiling sweep
-- ------------------------------------------------------------------------------------------------
--
-- Deliberately not the same mechanism as the adoption delete (M2-ADR-019 §3). It takes a window and
-- NO row identifier, so it cannot be turned into a targeted delete; it rejects a window below the
-- floor, the way V023 rejects retention_days < 1, so it cannot be turned into "delete everything";
-- and it rejects a window above the ceiling, so it cannot be turned into "keep them longer".
--
-- SECURITY INVOKER by default, as V023 established: the caller's own privileges apply, so this
-- cannot become a way to delete rows a role could not otherwise delete.
--
-- The terminal-state test is required as well as the age test, and is the correction M2-ADR-019 §4
-- makes to M2-ADR-018 §9. Age alone bounds exposure; the terminal test bounds damage. A result
-- belonging to an execution still RUNNING or RECONCILING is the artifact a recovery worker is about
-- to adopt, and deleting it would turn a recoverable execution into an unexplained one.
--
-- Bounded batches, so the sweep cannot hold a long transaction across the table and block an
-- adoption while it runs.

CREATE FUNCTION core.purge_expired_ai_execution_results(
  retention_days INTEGER DEFAULT 30,
  batch_limit    INTEGER DEFAULT 500)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
  purged INTEGER;
BEGIN
  IF retention_days IS NULL OR retention_days < 1 THEN
    RAISE EXCEPTION 'retention_days must be at least 1, got %', retention_days
      USING ERRCODE = '22023';
  END IF;

  IF retention_days > 30 THEN
    -- The ceiling is a maximum, not a default. A larger window would keep RESTRICTED content past
    -- the retention this classification was approved under, and past the 29 days the provider
    -- itself retains a batch result -- so it would preserve content RAMALS can no longer even
    -- reconcile against its source.
    RAISE EXCEPTION 'retention_days must not exceed the 30 day ceiling, got %', retention_days
      USING ERRCODE = '22023';
  END IF;

  IF batch_limit IS NULL OR batch_limit < 1 THEN
    RAISE EXCEPTION 'batch_limit must be at least 1, got %', batch_limit
      USING ERRCODE = '22023';
  END IF;

  WITH eligible AS (
    SELECT result.request_id
      FROM core.ai_execution_result result
      JOIN core.ai_provider_execution execution
        ON execution.request_id = result.request_id
     WHERE result.stored_at < CURRENT_TIMESTAMP - make_interval(days => retention_days)
       AND execution.state IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN_TERMINAL')
     ORDER BY result.stored_at
     LIMIT batch_limit
  ), removed AS (
    DELETE FROM core.ai_execution_result
     WHERE request_id IN (SELECT request_id FROM eligible)
    RETURNING request_id
  )
  INSERT INTO core.ai_execution_transition
    (request_id, from_state, to_state, actor, reason)
  SELECT request_id, 'RESULT_AVAILABLE', 'PURGED_ON_CEILING', 'PURGE_SWEEP', 'CEILING_REACHED'
    FROM removed;
  GET DIAGNOSTICS purged = ROW_COUNT;

  RETURN purged;
END;
$$;

COMMENT ON FUNCTION core.purge_expired_ai_execution_results(INTEGER, INTEGER) IS
  'Removes Contract B results that are both beyond the retention window and belong to a terminal '
  'execution, in bounded batches, recording each removal in the transition ledger. Operator- or '
  'job-invoked; no ordinary code path calls it (M2-ADR-019 §3). Rejects a window below 1 day or '
  'above the 30 day ceiling.';

-- ------------------------------------------------------------------------------------------------
-- 7. Access control -- exactly the matrix of M2-ADR-018 §3
-- ------------------------------------------------------------------------------------------------
--
-- The revokes below are not decoration. V002 sets ALTER DEFAULT PRIVILEGES granting SELECT, INSERT,
-- UPDATE and DELETE on every future core table to ramals_core_runtime, so all four privileges
-- arrive on these tables automatically at CREATE. The matrix is therefore produced by taking
-- privileges away, and a migration that only wrote GRANTs would silently ship UPDATE on the result
-- table while appearing to grant exactly the right thing.

REVOKE ALL ON TABLE
  core.ai_provider_execution, core.ai_execution_result,
  core.ai_execution_transition, core.ai_reconciliation_work
  FROM PUBLIC;

-- The result table: SELECT, INSERT, DELETE. Never UPDATE (M2-ADR-018 §3) -- a result that can be
-- rewritten is not evidence of what the provider returned, and the lifecycle is write, read once,
-- delete.
REVOKE ALL ON TABLE core.ai_execution_result FROM ramals_core_runtime;
GRANT SELECT, INSERT, DELETE ON TABLE core.ai_execution_result TO ramals_core_runtime;

-- The ledger is append-only for the runtime as well as by trigger. Two independent controls
-- because they fail differently: a dropped trigger leaves the grant, a widened grant leaves the
-- trigger.
REVOKE ALL ON TABLE core.ai_execution_transition FROM ramals_core_runtime;
GRANT SELECT, INSERT ON TABLE core.ai_execution_transition TO ramals_core_runtime;
GRANT USAGE, SELECT ON SEQUENCE core.ai_execution_transition_id_seq TO ramals_core_runtime;

-- The handle and the work queue are mutable state: states advance, leases are taken and released.
-- Neither is deleted on an ordinary path -- a handle outlives its result and is the record that the
-- execution occurred (M2-ADR-019 §1).
REVOKE ALL ON TABLE core.ai_provider_execution FROM ramals_core_runtime;
GRANT SELECT, INSERT, UPDATE ON TABLE core.ai_provider_execution TO ramals_core_runtime;
REVOKE ALL ON TABLE core.ai_reconciliation_work FROM ramals_core_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE core.ai_reconciliation_work TO ramals_core_runtime;

REVOKE ALL ON FUNCTION core.adopt_ai_execution_result(VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION core.purge_expired_ai_execution_results(INTEGER, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION core.adopt_ai_execution_result(VARCHAR) TO ramals_core_runtime;
GRANT EXECUTE ON FUNCTION core.purge_expired_ai_execution_results(INTEGER, INTEGER)
  TO ramals_core_runtime;

-- The AI plane gets nothing, stated rather than left to omission.
--
-- Restated for the same reason V023 restates it: the AI plane has no PostgreSQL driver at all
-- (M2-ADR-012, asserted by tests/unit/test_no_database_access.py), so this is belt and braces --
-- but Contract B is precisely the feature whose design document put durable state on the AI side,
-- and the wrong fix for that misreading is a grant here.
--
-- Guarded exactly as V015 and V023 are: ramals_core_migration deliberately lacks CREATEROLE, so a
-- database without the role provisioned is a legitimate configuration rather than an error.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_ai_runtime') THEN
    RAISE NOTICE 'ramals_ai_runtime is not provisioned here; skipping contract B boundary revokes';
    RETURN;
  END IF;

  EXECUTE 'REVOKE ALL ON TABLE core.ai_provider_execution, core.ai_execution_result, '
          'core.ai_execution_transition, core.ai_reconciliation_work FROM ramals_ai_runtime';
  EXECUTE 'REVOKE ALL ON SEQUENCE core.ai_execution_transition_id_seq FROM ramals_ai_runtime';
  EXECUTE 'REVOKE ALL ON FUNCTION core.adopt_ai_execution_result(VARCHAR) FROM ramals_ai_runtime';
  EXECUTE 'REVOKE ALL ON FUNCTION '
          'core.purge_expired_ai_execution_results(INTEGER, INTEGER) FROM ramals_ai_runtime';
END
$$;

-- Reporting, analytics and evaluation are prohibited by M2-ADR-018 §3 and hold no grant here. No
-- such role exists in this schema today, so there is nothing to revoke and a REVOKE naming one
-- would fail. The prohibition is therefore enforced as an invariant rather than a statement:
-- V037's contract test asserts that no role other than ramals_core_migration and
-- ramals_core_runtime holds any privilege on core.ai_execution_result. That form survives a role
-- being added later, which a revoke written today would not.
