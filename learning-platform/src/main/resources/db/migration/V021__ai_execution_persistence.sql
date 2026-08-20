-- M1-T13 / M1-ADR-005: bounded, append-only AI execution provenance.
CREATE TABLE core.ai_execution (
  id UUID PRIMARY KEY,
  request_id VARCHAR(64) NOT NULL,
  interaction_id VARCHAR(64) NOT NULL,
  agent_type VARCHAR(32) NOT NULL,
  contract_version VARCHAR(32) NOT NULL,
  agent_version VARCHAR(64),
  prompt_version VARCHAR(64),
  model_route VARCHAR(64),
  model_id VARCHAR(128),
  status VARCHAR(16) NOT NULL,
  error_code VARCHAR(64),
  request_digest CHAR(64) NOT NULL,
  proposal_digest CHAR(64),
  input_tokens INTEGER,
  cached_input_tokens INTEGER,
  output_tokens INTEGER,
  estimated_cost_usd NUMERIC(18, 8),
  latency_ms INTEGER,
  started_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_ai_execution_request UNIQUE (request_id),
  CONSTRAINT ck_ai_execution_agent CHECK (agent_type IN ('TUTOR', 'DIAGNOSTIC', 'ASSESSMENT', 'ADAPTATION')),
  CONSTRAINT ck_ai_execution_status CHECK (status IN ('SUCCEEDED', 'FAILED')),
  CONSTRAINT ck_ai_execution_ids CHECK (length(btrim(request_id)) > 0 AND length(btrim(interaction_id)) > 0),
  CONSTRAINT ck_ai_execution_contract CHECK (length(btrim(contract_version)) > 0),
  CONSTRAINT ck_ai_execution_error CHECK (status = 'SUCCEEDED' OR (error_code IS NOT NULL AND length(btrim(error_code)) > 0)),
  CONSTRAINT ck_ai_execution_digest CHECK (request_digest ~ '^[0-9a-f]{64}$' AND (proposal_digest IS NULL OR proposal_digest ~ '^[0-9a-f]{64}$')),
  CONSTRAINT ck_ai_execution_usage CHECK (input_tokens IS NULL OR input_tokens >= 0)
);

CREATE INDEX ix_ai_execution_interaction ON core.ai_execution(interaction_id, started_at);
CREATE INDEX ix_ai_execution_agent_status ON core.ai_execution(agent_type, status, started_at);

-- Provenance and execution accounting are immutable after insertion.
CREATE OR REPLACE FUNCTION core.reject_ai_execution_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'ai execution records are immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_ai_execution_immutable
BEFORE UPDATE OR DELETE ON core.ai_execution
FOR EACH ROW EXECUTE FUNCTION core.reject_ai_execution_mutation();

GRANT SELECT, INSERT ON TABLE core.ai_execution TO ramals_core_runtime;
