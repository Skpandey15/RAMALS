-- M2-T15.2: separate a durable diagnostic commission from provider-dispatch ownership.
--
-- The lifecycle event remains append-only provenance. This mutable, narrowly scoped row is the
-- fencing record that decides whether a commissioned diagnostic request may make its first
-- provider call. It contains identifiers and timestamps only; prompts, context and model output
-- remain structurally impossible to store here.
CREATE TABLE core.ai_execution_dispatch (
  request_id VARCHAR(64) PRIMARY KEY,
  commission_event_id UUID NOT NULL UNIQUE
    REFERENCES core.ai_execution_event(id) ON DELETE CASCADE,
  state VARCHAR(32) NOT NULL,
  context_id VARCHAR(64),
  context_as_of TIMESTAMPTZ,
  owner_token UUID,
  fence BIGINT NOT NULL DEFAULT 0,
  commissioned_at TIMESTAMPTZ NOT NULL,
  ownership_acquired_at TIMESTAMPTZ,
  invocation_started_at TIMESTAMPTZ,
  CONSTRAINT ck_ai_execution_dispatch_state
    CHECK (state IN ('AVAILABLE', 'DISPATCH_OWNED', 'IN_FLIGHT', 'LEGACY_INDETERMINATE')),
  CONSTRAINT ck_ai_execution_dispatch_fence CHECK (fence >= 0),
  CONSTRAINT ck_ai_execution_dispatch_shape CHECK (
    (state = 'AVAILABLE'
      AND context_id IS NOT NULL AND context_as_of IS NOT NULL
      AND owner_token IS NULL AND fence = 0
      AND ownership_acquired_at IS NULL AND invocation_started_at IS NULL)
    OR
    (state = 'DISPATCH_OWNED'
      AND context_id IS NOT NULL AND context_as_of IS NOT NULL
      AND owner_token IS NOT NULL AND fence > 0
      AND ownership_acquired_at IS NOT NULL AND invocation_started_at IS NULL)
    OR
    (state = 'IN_FLIGHT'
      AND context_id IS NOT NULL AND context_as_of IS NOT NULL
      AND owner_token IS NOT NULL AND fence > 0
      AND ownership_acquired_at IS NOT NULL AND invocation_started_at IS NOT NULL)
    OR
    (state = 'LEGACY_INDETERMINATE'
      AND context_id IS NULL AND context_as_of IS NULL
      AND owner_token IS NULL AND fence = 0
      AND ownership_acquired_at IS NULL AND invocation_started_at IS NULL)
  )
);

-- A pre-V035 STARTED event does not say whether its provider call began. Never make such an event
-- dispatchable during migration. Only new diagnostic commissions can enter AVAILABLE.
INSERT INTO core.ai_execution_dispatch
  (request_id, commission_event_id, state, context_id, context_as_of, owner_token, fence,
   commissioned_at,
   ownership_acquired_at, invocation_started_at)
SELECT event.request_id, event.id, 'LEGACY_INDETERMINATE', NULL, NULL, NULL, 0,
       event.occurred_at, NULL, NULL
  FROM core.ai_execution_event event
 WHERE event.agent_type = 'DIAGNOSTIC'
   AND event.event_type = 'STARTED'
   AND NOT EXISTS (
     SELECT 1
       FROM core.ai_execution execution
      WHERE execution.request_id = event.request_id
   );

-- Runtime receives UPDATE because ownership and invocation-start are state transitions, but it
-- must not be able to reset an owned/in-flight request to AVAILABLE or replace its fence. The
-- repository CAS supplies the only two accepted transition shapes.
CREATE FUNCTION core.enforce_ai_execution_dispatch_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.request_id IS DISTINCT FROM NEW.request_id
      OR OLD.commission_event_id IS DISTINCT FROM NEW.commission_event_id
      OR OLD.context_id IS DISTINCT FROM NEW.context_id
      OR OLD.context_as_of IS DISTINCT FROM NEW.context_as_of
      OR OLD.commissioned_at IS DISTINCT FROM NEW.commissioned_at THEN
    RAISE EXCEPTION 'diagnostic dispatch identity is immutable' USING ERRCODE = '55000';
  END IF;

  IF OLD.state = 'AVAILABLE'
      AND NEW.state = 'DISPATCH_OWNED'
      AND OLD.owner_token IS NULL
      AND NEW.owner_token IS NOT NULL
      AND NEW.fence = OLD.fence + 1
      AND NEW.ownership_acquired_at IS NOT NULL
      AND NEW.invocation_started_at IS NULL THEN
    RETURN NEW;
  END IF;

  IF OLD.state = 'DISPATCH_OWNED'
      AND NEW.state = 'IN_FLIGHT'
      AND NEW.owner_token = OLD.owner_token
      AND NEW.fence = OLD.fence
      AND NEW.ownership_acquired_at = OLD.ownership_acquired_at
      AND NEW.invocation_started_at IS NOT NULL THEN
    RETURN NEW;
  END IF;

  RAISE EXCEPTION 'invalid diagnostic dispatch transition from % to %', OLD.state, NEW.state
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_ai_execution_dispatch_transition
BEFORE UPDATE ON core.ai_execution_dispatch
FOR EACH ROW EXECUTE FUNCTION core.enforce_ai_execution_dispatch_transition();

CREATE INDEX ix_ai_execution_dispatch_state
  ON core.ai_execution_dispatch(state, commissioned_at);

COMMENT ON TABLE core.ai_execution_dispatch IS
  'Mutable CAS/fencing state for the first provider dispatch of a durable DIAGNOSTIC commission. '
  'AVAILABLE is recoverable; DISPATCH_OWNED and IN_FLIGHT must never be blindly redispatched. '
  'Stores bounded identity and timing metadata only.';

COMMENT ON COLUMN core.ai_execution_dispatch.owner_token IS
  'Opaque ownership token acquired by exactly one provider dispatcher; unrelated to workflow-step '
  'execution tokens.';

COMMENT ON COLUMN core.ai_execution_dispatch.fence IS
  'Monotonic dispatch-ownership fence checked when marking provider invocation as started.';

COMMENT ON COLUMN core.ai_execution_dispatch.context_id IS
  'The immutable grounded-context identity in the commissioned request; used with context_as_of to '
  'reconstruct the same request after a pre-provider worker death.';

COMMENT ON FUNCTION core.enforce_ai_execution_dispatch_transition() IS
  'Permits only AVAILABLE -> DISPATCH_OWNED -> IN_FLIGHT while preserving request, context, owner '
  'and fencing identity. Prevents redispatch by state regression.';

GRANT SELECT, INSERT, UPDATE ON TABLE core.ai_execution_dispatch TO ramals_core_runtime;
REVOKE DELETE, TRUNCATE, REFERENCES, TRIGGER
  ON TABLE core.ai_execution_dispatch FROM ramals_core_runtime;
REVOKE ALL ON FUNCTION core.enforce_ai_execution_dispatch_transition() FROM PUBLIC;
