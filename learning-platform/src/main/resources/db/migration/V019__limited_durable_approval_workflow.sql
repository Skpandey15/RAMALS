-- M1-T12 / M1-ADR-007: Spring-owned limited-durable human approval.
-- Approval state is deliberately separate from assessment_item_version.trust_state.

CREATE TABLE core.assessment_approval_request (
  id UUID PRIMARY KEY,
  candidate_id UUID NOT NULL,
  candidate_revision INTEGER NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'APPROVAL_REQUIRED',
  candidate_payload_jsonb JSONB NOT NULL,
  proposal_digest CHAR(64) NOT NULL,
  source_proposal_id VARCHAR(64) NOT NULL,
  contract_version VARCHAR(32) NOT NULL,
  agent_type VARCHAR(32) NOT NULL,
  agent_version VARCHAR(64) NOT NULL,
  model_route VARCHAR(64),
  model_id VARCHAR(128),
  prompt_version VARCHAR(64),
  policy_version VARCHAR(64) NOT NULL,
  engine_version VARCHAR(64) NOT NULL,
  interaction_id VARCHAR(64) NOT NULL,
  created_by VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMPTZ NOT NULL,
  reviewer_subject VARCHAR(255),
  reviewed_at TIMESTAMPTZ,
  review_reason VARCHAR(1024),
  authoritative_item_version_id UUID REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT,
  CONSTRAINT fk_approval_candidate FOREIGN KEY (candidate_id, candidate_revision)
    REFERENCES core.assessment_candidate_revision(candidate_id, candidate_revision)
    ON DELETE RESTRICT,
  CONSTRAINT ck_approval_target CHECK (target_type = 'ASSESSMENT_CANDIDATE'),
  CONSTRAINT ck_approval_state CHECK (
    state IN ('APPROVAL_REQUIRED', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'SUPERSEDED')
  ),
  CONSTRAINT ck_approval_payload CHECK (jsonb_typeof(candidate_payload_jsonb) = 'object'),
  CONSTRAINT ck_approval_digest CHECK (proposal_digest ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_approval_actor CHECK (length(btrim(created_by)) > 0),
  CONSTRAINT ck_approval_interaction CHECK (length(btrim(interaction_id)) > 0),
  CONSTRAINT ck_approval_versions CHECK (length(btrim(policy_version)) > 0
      AND length(btrim(engine_version)) > 0),
  CONSTRAINT ck_approval_terminal_metadata CHECK (
    state = 'APPROVAL_REQUIRED'
    OR (reviewer_subject IS NOT NULL AND length(btrim(reviewer_subject)) > 0 AND reviewed_at IS NOT NULL)
  ),
  CONSTRAINT ck_approval_approved_target CHECK (
    state <> 'APPROVED' OR authoritative_item_version_id IS NOT NULL
  )
);

CREATE UNIQUE INDEX uq_approval_candidate_revision
  ON core.assessment_approval_request(candidate_id, candidate_revision);
CREATE INDEX ix_approval_state_expiry
  ON core.assessment_approval_request(state, expires_at);

CREATE TABLE core.assessment_approval_command (
  actor_subject VARCHAR(255) NOT NULL,
  operation VARCHAR(16) NOT NULL,
  request_id UUID NOT NULL REFERENCES core.assessment_approval_request(id) ON DELETE RESTRICT,
  idempotency_key VARCHAR(255) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  result_state VARCHAR(24) NOT NULL,
  authoritative_item_version_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (actor_subject, operation, request_id, idempotency_key),
  CONSTRAINT ck_approval_command_operation CHECK (operation IN ('CREATE', 'APPROVE', 'REJECT', 'CANCEL')),
  CONSTRAINT ck_approval_command_actor CHECK (length(btrim(actor_subject)) > 0),
  CONSTRAINT ck_approval_command_key CHECK (length(btrim(idempotency_key)) > 0),
  CONSTRAINT ck_approval_command_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_approval_create_idempotency
  ON core.assessment_approval_command(actor_subject, operation, idempotency_key)
  WHERE operation = 'CREATE';

CREATE OR REPLACE FUNCTION core.reject_approval_provenance_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.candidate_id <> OLD.candidate_id
      OR NEW.candidate_revision <> OLD.candidate_revision
      OR NEW.candidate_payload_jsonb <> OLD.candidate_payload_jsonb
      OR NEW.proposal_digest <> OLD.proposal_digest
      OR NEW.source_proposal_id <> OLD.source_proposal_id
      OR NEW.contract_version <> OLD.contract_version
      OR NEW.agent_type <> OLD.agent_type
      OR NEW.agent_version <> OLD.agent_version
      OR NEW.model_route IS DISTINCT FROM OLD.model_route
      OR NEW.model_id IS DISTINCT FROM OLD.model_id
      OR NEW.prompt_version IS DISTINCT FROM OLD.prompt_version
      OR NEW.policy_version <> OLD.policy_version
      OR NEW.engine_version <> OLD.engine_version
      OR NEW.interaction_id <> OLD.interaction_id
      OR NEW.created_by <> OLD.created_by
      OR NEW.created_at <> OLD.created_at
      OR NEW.expires_at <> OLD.expires_at THEN
    RAISE EXCEPTION 'assessment approval provenance is immutable' USING ERRCODE = '55000';
  END IF;
  NEW.updated_at := CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_approval_provenance_immutable
BEFORE UPDATE ON core.assessment_approval_request
FOR EACH ROW EXECUTE FUNCTION core.reject_approval_provenance_mutation();

COMMENT ON TABLE core.assessment_approval_request IS
  'M1-T12 durable human approval state; reviewed candidate provenance is immutable.';
COMMENT ON TABLE core.assessment_approval_command IS
  'M1-T12 retry-safe consequential command results, scoped to actor, operation and request.';

-- V002 grants cover tables present at baseline only; every later Spring-owned table must
-- explicitly grant the runtime role its required DML privileges.
GRANT SELECT, INSERT, UPDATE, DELETE
  ON TABLE core.assessment_approval_request, core.assessment_approval_command
  TO ramals_core_runtime;
