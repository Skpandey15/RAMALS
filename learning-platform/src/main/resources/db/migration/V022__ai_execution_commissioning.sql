-- M1-T13A: claim AI executions before provider dispatch using append-only lifecycle events.
ALTER TABLE core.ai_execution
  ADD CONSTRAINT ck_ai_execution_cached_input_tokens
    CHECK (cached_input_tokens IS NULL OR cached_input_tokens >= 0),
  ADD CONSTRAINT ck_ai_execution_output_tokens
    CHECK (output_tokens IS NULL OR output_tokens >= 0),
  ADD CONSTRAINT ck_ai_execution_latency
    CHECK (latency_ms IS NULL OR latency_ms >= 0),
  ADD CONSTRAINT ck_ai_execution_cost
    CHECK (estimated_cost_usd IS NULL OR estimated_cost_usd >= 0),
  ADD CONSTRAINT ck_ai_execution_completed_after_started
    CHECK (completed_at >= started_at);

CREATE TABLE core.ai_execution_event (
  id UUID PRIMARY KEY,
  request_id VARCHAR(64) NOT NULL,
  interaction_id VARCHAR(64) NOT NULL,
  agent_type VARCHAR(32) NOT NULL,
  contract_version VARCHAR(32) NOT NULL,
  event_type VARCHAR(16) NOT NULL,
  error_code VARCHAR(64),
  request_digest CHAR(64) NOT NULL,
  proposal_digest CHAR(64),
  occurred_at TIMESTAMPTZ NOT NULL,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  CONSTRAINT uq_ai_execution_event_type UNIQUE (request_id, event_type),
  CONSTRAINT ck_ai_execution_event_agent
    CHECK (agent_type IN ('TUTOR', 'DIAGNOSTIC', 'ASSESSMENT', 'ADAPTATION')),
  CONSTRAINT ck_ai_execution_event_type
    CHECK (event_type IN ('STARTED', 'SUCCEEDED', 'FAILED')),
  CONSTRAINT ck_ai_execution_event_ids
    CHECK (length(btrim(request_id)) > 0 AND length(btrim(interaction_id)) > 0),
  CONSTRAINT ck_ai_execution_event_digest
    CHECK (request_digest ~ '^[0-9a-f]{64}$'
      AND (proposal_digest IS NULL OR proposal_digest ~ '^[0-9a-f]{64}$')),
  CONSTRAINT ck_ai_execution_event_error
    CHECK (event_type IN ('STARTED', 'SUCCEEDED')
      OR (error_code IS NOT NULL AND length(btrim(error_code)) > 0)),
  CONSTRAINT ck_ai_execution_event_times
    CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
);

-- Preserve the commissioning gate for V021 rows that predate this migration. These historical
-- records must not become dispatchable merely because V022 introduced the event stream.
INSERT INTO core.ai_execution_event
  (id, request_id, interaction_id, agent_type, contract_version, event_type,
   error_code, request_digest, proposal_digest, occurred_at, started_at, completed_at)
SELECT id, request_id, interaction_id, agent_type, contract_version, 'STARTED',
       NULL, request_digest, NULL, started_at, started_at, completed_at
  FROM core.ai_execution;

INSERT INTO core.ai_execution_event
  (id, request_id, interaction_id, agent_type, contract_version, event_type,
   error_code, request_digest, proposal_digest, occurred_at, started_at, completed_at)
SELECT md5(request_id || ':terminal')::uuid, request_id, interaction_id, agent_type, contract_version, status,
       error_code, request_digest, proposal_digest, completed_at, started_at, completed_at
  FROM core.ai_execution;

CREATE INDEX ix_ai_execution_event_interaction
  ON core.ai_execution_event(interaction_id, occurred_at);

CREATE OR REPLACE FUNCTION core.reject_ai_execution_event_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'ai execution events are immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_ai_execution_event_immutable
BEFORE UPDATE OR DELETE ON core.ai_execution_event
FOR EACH ROW EXECUTE FUNCTION core.reject_ai_execution_event_mutation();

GRANT SELECT, INSERT ON TABLE core.ai_execution_event TO ramals_core_runtime;
