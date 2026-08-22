-- M2-T02 / M2-ADR-002: durable agent work is committed atomically with its authoritative source
-- decision. T03 will claim and dispatch these rows; this migration establishes the durability and
-- idempotency boundary without putting an AI call or a broker inside the domain transaction.
CREATE TABLE core.agent_work_outbox (
  id UUID PRIMARY KEY,
  request_id VARCHAR(64) NOT NULL,
  interaction_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  agent_type VARCHAR(32) NOT NULL,
  capability VARCHAR(64) NOT NULL,
  source_decision_id UUID NOT NULL REFERENCES ledger.decision_record(id) ON DELETE RESTRICT,
  grounded_context_id VARCHAR(64) NOT NULL,
  payload_version INTEGER NOT NULL,
  payload JSONB NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL,
  lease_owner VARCHAR(128),
  lease_expires_at TIMESTAMPTZ,
  last_error_code VARCHAR(64),
  terminal_reason VARCHAR(256),
  created_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  CONSTRAINT uq_agent_work_outbox_request UNIQUE (request_id),
  CONSTRAINT uq_agent_work_outbox_source UNIQUE (source_decision_id, agent_type, capability),
  CONSTRAINT ck_agent_work_outbox_identity CHECK (
    length(btrim(request_id)) > 0
    AND length(btrim(interaction_id)) > 0
    AND length(btrim(trace_id)) > 0
    AND length(btrim(grounded_context_id)) > 0),
  CONSTRAINT ck_agent_work_outbox_agent CHECK (
    agent_type IN ('TUTOR', 'DIAGNOSTIC', 'ASSESSMENT', 'ADAPTATION')),
  CONSTRAINT ck_agent_work_outbox_status CHECK (
    status IN ('PENDING', 'CLAIMED', 'RETRY', 'COMPLETED', 'TERMINAL')),
  CONSTRAINT ck_agent_work_outbox_attempts CHECK (attempt_count >= 0),
  CONSTRAINT ck_agent_work_outbox_lease CHECK (
    (status = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    OR (status <> 'CLAIMED')),
  CONSTRAINT ck_agent_work_outbox_terminal CHECK (
    (status = 'TERMINAL' AND terminal_reason IS NOT NULL)
    OR (status <> 'TERMINAL' AND terminal_reason IS NULL)),
  CONSTRAINT ck_agent_work_outbox_completion CHECK (
    (status = 'COMPLETED' AND completed_at IS NOT NULL)
    OR (status <> 'COMPLETED' AND completed_at IS NULL)),
  CONSTRAINT ck_agent_work_outbox_payload CHECK (
    payload_version = 1
    AND payload->>'contractVersion' = '1.0'
    AND payload->>'workId' = id::text
    AND payload->>'requestId' = request_id
    AND payload->>'interactionId' = interaction_id
    AND payload->>'traceId' = trace_id
    AND payload->>'agentType' = agent_type
    AND payload->>'capability' = capability
    AND payload->>'sourceDecisionId' = source_decision_id::text
    AND payload->>'groundedContextId' = grounded_context_id)
);

COMMENT ON TABLE core.agent_work_outbox IS
  'M2 transactional outbox: immutable agent work committed with its authoritative source decision; '
  'delivery state is advanced asynchronously by the Spring-owned dispatcher';

CREATE INDEX ix_agent_work_outbox_dispatch
  ON core.agent_work_outbox (next_attempt_at, created_at, id)
  WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX ix_agent_work_outbox_interaction
  ON core.agent_work_outbox (interaction_id, created_at);

-- Delivery state changes in T03, but the logical work and its source/provenance must never be
-- rewritten to make a retry look like different work.
CREATE FUNCTION core.reject_agent_work_identity_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id
      OR NEW.request_id IS DISTINCT FROM OLD.request_id
      OR NEW.interaction_id IS DISTINCT FROM OLD.interaction_id
      OR NEW.trace_id IS DISTINCT FROM OLD.trace_id
      OR NEW.agent_type IS DISTINCT FROM OLD.agent_type
      OR NEW.capability IS DISTINCT FROM OLD.capability
      OR NEW.source_decision_id IS DISTINCT FROM OLD.source_decision_id
      OR NEW.grounded_context_id IS DISTINCT FROM OLD.grounded_context_id
      OR NEW.payload_version IS DISTINCT FROM OLD.payload_version
      OR NEW.payload IS DISTINCT FROM OLD.payload
      OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'agent work identity and payload are immutable' USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_agent_work_identity_immutable
BEFORE UPDATE ON core.agent_work_outbox
FOR EACH ROW EXECUTE FUNCTION core.reject_agent_work_identity_mutation();

GRANT SELECT, INSERT, UPDATE ON TABLE core.agent_work_outbox TO ramals_core_runtime;
