-- Retention, redaction and the AI-plane boundary for AI execution provenance (M1-T13, M1-ADR-005).
--
-- No new columns and no data change. This migration records two decisions in the schema itself, so
-- they are discoverable by someone reading the database rather than only by someone who finds the
-- ADR.

-- 1. Redaction is structural, not procedural.
--
-- There is nothing to redact from these tables because there is nowhere to put it. Every column is
-- bounded metadata -- identifiers, versions, enumerated states, counters, timestamps and SHA-256
-- digests. No TEXT column exists, so a prompt, a learner's context, a model's output or a provider
-- credential cannot be stored here even by a caller that tried. A redaction routine would be the
-- weaker control: it runs after the fact, on data that was already written.

COMMENT ON TABLE core.ai_execution IS
  'Append-only AI execution accounting (M1-ADR-005). Observational and non-authoritative: it '
  'cannot create evidence, mastery, approval or content state. Stores bounded metadata and SHA-256 '
  'digests only -- never prompts, learner context, provider credentials or model output, which is '
  'enforced by the absence of any free-text column rather than by redaction. Retention: 400 days '
  'from completed_at. Correlate with ledger.decision_record on interaction_id.';

COMMENT ON TABLE core.ai_execution_event IS
  'Append-only lifecycle stream for core.ai_execution (M1-ADR-005, M1-T13A). Same redaction '
  'property and same 400-day retention from occurred_at.';

COMMENT ON COLUMN core.ai_execution.request_digest IS
  'SHA-256 of the request envelope. Detects a reused requestId carrying different content; it is '
  'not reversible and is not a copy of the request.';

COMMENT ON COLUMN core.ai_execution.interaction_id IS
  'The learner action this execution belongs to. Shared with ledger.decision_record, which is how a '
  'historical decision identifies the AI activity that accompanied it -- decision records are '
  'append-only and are written before the AI call, so a foreign key could never be filled in.';

-- 2. Retention is 400 days, and the mechanism is deliberately not scheduled here.
--
-- 400 days keeps a full year of release evidence plus a margin for an annual review, which is what
-- this data is for: reconstructing what an agent did, and at what cost, when a release is
-- questioned afterwards.
--
-- The purge is a function rather than a job because MVP-1 has no scheduler -- Temporal is deferred
-- by the MVP-0 scope freeze. Shipping a function an operator can run, and can be tested, is honest;
-- shipping a policy with no mechanism at all is a comment pretending to be a control, and a
-- half-built scheduler would be worse than either.
--
-- SECURITY INVOKER by default: the caller's own privileges apply, so this cannot become a way to
-- delete rows a role could not otherwise delete.
--
-- Retention and append-only enforcement collide, and the collision is real: V021 and V022 reject
-- every DELETE outright, so a retention policy could not have been executed at all. Discovered by
-- writing the purge and watching it fail with 55000.
--
-- They are reconciled by separating two different meanings of "immutable". History must not be
-- *rewritten* -- no UPDATE, ever, and no DELETE of a row still inside the window, so a bad
-- execution cannot be made to disappear the day after it happened. History may *expire* -- a row
-- past the retention floor can go. The floor lives in the trigger rather than in the caller, so
-- passing a shorter window to the purge does not shorten the policy; it just deletes less.

CREATE OR REPLACE FUNCTION core.reject_ai_execution_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    RAISE EXCEPTION 'ai execution records are immutable' USING ERRCODE = '55000';
  END IF;

  IF OLD.completed_at >= CURRENT_TIMESTAMP - make_interval(days => 400) THEN
    RAISE EXCEPTION 'ai execution records are immutable until they expire' USING ERRCODE = '55000';
  END IF;

  RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION core.reject_ai_execution_event_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' THEN
    RAISE EXCEPTION 'ai execution events are immutable' USING ERRCODE = '55000';
  END IF;

  IF OLD.occurred_at >= CURRENT_TIMESTAMP - make_interval(days => 400) THEN
    RAISE EXCEPTION 'ai execution events are immutable until they expire' USING ERRCODE = '55000';
  END IF;

  RETURN OLD;
END;
$$;

CREATE FUNCTION core.purge_expired_ai_executions(retention_days INTEGER DEFAULT 400)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
  purged INTEGER;
BEGIN
  IF retention_days IS NULL OR retention_days < 1 THEN
    -- A zero or negative window would delete everything, including today's executions. That is
    -- never what an operator means, and it is not recoverable from an append-only table.
    RAISE EXCEPTION 'retention_days must be at least 1, got %', retention_days
      USING ERRCODE = '22023';
  END IF;

  DELETE FROM core.ai_execution_event
   WHERE occurred_at < CURRENT_TIMESTAMP - make_interval(days => retention_days);

  DELETE FROM core.ai_execution
   WHERE completed_at < CURRENT_TIMESTAMP - make_interval(days => retention_days);
  GET DIAGNOSTICS purged = ROW_COUNT;

  RETURN purged;
END;
$$;

COMMENT ON FUNCTION core.purge_expired_ai_executions(INTEGER) IS
  'Deletes AI execution provenance older than the retention window (default 400 days). Run by an '
  'operator or by a scheduler once one exists; MVP-1 has none. Events are removed before executions '
  'so the stream never outlives the row it describes.';

-- 3. The AI plane gets nothing here.
--
-- The MVP-1 master plan line for M1-T13 reads "Grant ramals_ai_runtime required DML only". That was
-- written before M1-ADR-005, which decided that *Spring* owns this table and that the AI plane
-- reaches nothing by SQL. The AI plane has no PostgreSQL driver at all -- asserted by
-- tests/unit/test_no_database_access.py -- so a grant here would hand a credential to a process
-- with no way to use it, and would be its first.
--
-- V015 already revokes everything from ramals_ai_runtime and sets default privileges that cover
-- tables created later, so this is a restatement rather than a change. It is restated because the
-- apparent gap between the plan and the schema invites exactly the wrong fix.

REVOKE ALL ON FUNCTION core.purge_expired_ai_executions(INTEGER) FROM PUBLIC;

-- Guarded exactly as V015 is: the role is provisioned by the environment, not by a migration --
-- ramals_core_migration deliberately lacks CREATEROLE, because a migration able to mint roles is a
-- privilege-escalation path. A database without the role is a legitimate configuration, not an
-- error, so the revokes are skipped rather than failing the migration.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_ai_runtime') THEN
    RAISE NOTICE 'ramals_ai_runtime is not provisioned here; skipping AI execution boundary revokes';
    RETURN;
  END IF;

  EXECUTE 'REVOKE ALL ON TABLE core.ai_execution, core.ai_execution_event '
          'FROM ramals_ai_runtime';
  EXECUTE 'REVOKE ALL ON FUNCTION core.purge_expired_ai_executions(INTEGER) '
          'FROM ramals_ai_runtime';
END
$$;
