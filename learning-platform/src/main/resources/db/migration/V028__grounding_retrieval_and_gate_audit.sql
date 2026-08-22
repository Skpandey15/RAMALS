-- M2-T06/T07: immutable evidence for deterministic grounding retrieval and proposal gating.
-- Context payloads remain transient; the bounded source identity set and every gate outcome are
-- retained so an execution can be reconstructed without persisting prompts or learner PII.

CREATE TABLE ledger.grounding_retrieval_record (
  context_id VARCHAR(64) PRIMARY KEY,
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  retrieval_policy_version VARCHAR(64) NOT NULL,
  as_of TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  source_refs JSONB NOT NULL,
  source_count INTEGER NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_grounding_retrieval_window CHECK (expires_at > as_of),
  CONSTRAINT ck_grounding_retrieval_count CHECK (source_count BETWEEN 1 AND 64),
  CONSTRAINT ck_grounding_retrieval_refs CHECK (
    jsonb_typeof(source_refs) = 'array' AND jsonb_array_length(source_refs) = source_count),
  CONSTRAINT ck_grounding_retrieval_policy CHECK (
    length(btrim(retrieval_policy_version)) BETWEEN 1 AND 64)
);

CREATE INDEX idx_grounding_retrieval_learner
  ON ledger.grounding_retrieval_record (learner_id, recorded_at DESC);

CREATE TABLE ledger.proposal_gate_decision (
  id UUID PRIMARY KEY,
  proposal_id VARCHAR(64) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  agent_run_id VARCHAR(64) NOT NULL,
  context_id VARCHAR(64) NOT NULL
    REFERENCES ledger.grounding_retrieval_record(context_id) ON DELETE RESTRICT,
  proposal_type VARCHAR(32) NOT NULL,
  accepted BOOLEAN NOT NULL,
  reason_codes JSONB NOT NULL,
  referenced_evidence_ids JSONB NOT NULL,
  policy_version VARCHAR(64) NOT NULL,
  decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (proposal_id, policy_version),
  CONSTRAINT ck_proposal_gate_type CHECK (
    proposal_type IN ('DIAGNOSTIC', 'ASSESSMENT_EVALUATION')),
  CONSTRAINT ck_proposal_gate_reasons CHECK (
    jsonb_typeof(reason_codes) = 'array' AND jsonb_array_length(reason_codes) > 0),
  CONSTRAINT ck_proposal_gate_evidence CHECK (jsonb_typeof(referenced_evidence_ids) = 'array')
);

CREATE INDEX idx_proposal_gate_request
  ON ledger.proposal_gate_decision (request_id, agent_run_id, decided_at DESC);

CREATE FUNCTION ledger.reject_grounding_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION '% is append-only; % is not permitted', TG_TABLE_NAME, TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_grounding_retrieval_append_only
BEFORE UPDATE OR DELETE ON ledger.grounding_retrieval_record
FOR EACH ROW EXECUTE FUNCTION ledger.reject_grounding_audit_mutation();

CREATE TRIGGER trg_proposal_gate_decision_append_only
BEFORE UPDATE OR DELETE ON ledger.proposal_gate_decision
FOR EACH ROW EXECUTE FUNCTION ledger.reject_grounding_audit_mutation();

COMMENT ON TABLE ledger.grounding_retrieval_record IS
  'M2-T06 immutable identity set selected by an authorized, versioned retrieval policy';
COMMENT ON TABLE ledger.proposal_gate_decision IS
  'M2-T07 immutable deterministic acceptance or rejection with stable reason codes';
