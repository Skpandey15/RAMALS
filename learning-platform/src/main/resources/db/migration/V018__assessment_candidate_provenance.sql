-- S0-07: immutable Spring-owned intake for AI-generated assessment candidates.
--
-- This is not approval workflow state. A candidate remains UNVERIFIED until a later M1-T12
-- approval flow creates the authoritative assessment_item_version.

CREATE TABLE core.assessment_candidate_revision (
  candidate_id UUID NOT NULL,
  candidate_revision INTEGER NOT NULL,
  source_proposal_id VARCHAR(64) NOT NULL,
  assessment_version_id UUID NOT NULL REFERENCES core.assessment_version(id) ON DELETE RESTRICT,
  item_code VARCHAR(96) NOT NULL,
  skill_code VARCHAR(96) NOT NULL,
  objective_code VARCHAR(96),
  item_type VARCHAR(16) NOT NULL,
  difficulty VARCHAR(16) NOT NULL,
  candidate_payload_jsonb JSONB NOT NULL,
  proposal_digest CHAR(64) NOT NULL,
  trust_state VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
  contract_version VARCHAR(32) NOT NULL,
  agent_type VARCHAR(32) NOT NULL,
  agent_version VARCHAR(64) NOT NULL,
  model_route VARCHAR(64),
  model_id VARCHAR(128),
  model_id_unavailable_reason VARCHAR(255),
  prompt_version VARCHAR(64),
  interaction_id VARCHAR(64) NOT NULL,
  created_by VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  idempotency_actor VARCHAR(255) NOT NULL,
  idempotency_key VARCHAR(255) NOT NULL,
  idempotency_fingerprint CHAR(64) NOT NULL,
  PRIMARY KEY (candidate_id, candidate_revision),
  CONSTRAINT ck_candidate_revision_positive CHECK (candidate_revision > 0),
  CONSTRAINT ck_candidate_source_proposal CHECK (length(btrim(source_proposal_id)) > 0),
  CONSTRAINT ck_candidate_item_code CHECK (item_code ~ '^[A-Z][A-Z0-9_]*$'),
  CONSTRAINT ck_candidate_item_type CHECK (item_type IN ('SINGLE_CHOICE')),
  CONSTRAINT ck_candidate_difficulty CHECK (
    difficulty IN ('FOUNDATIONAL', 'INTERMEDIATE', 'ADVANCED')
  ),
  CONSTRAINT ck_candidate_payload_object CHECK (jsonb_typeof(candidate_payload_jsonb) = 'object'),
  CONSTRAINT ck_candidate_digest CHECK (proposal_digest ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_candidate_trust CHECK (trust_state = 'UNVERIFIED'),
  CONSTRAINT ck_candidate_contract CHECK (length(btrim(contract_version)) > 0),
  CONSTRAINT ck_candidate_agent CHECK (length(btrim(agent_type)) > 0),
  CONSTRAINT ck_candidate_agent_version CHECK (length(btrim(agent_version)) > 0),
  CONSTRAINT ck_candidate_model_provenance CHECK (
    model_id IS NOT NULL
    OR (model_id_unavailable_reason IS NOT NULL
        AND length(btrim(model_id_unavailable_reason)) > 0)
  ),
  CONSTRAINT ck_candidate_interaction CHECK (length(btrim(interaction_id)) > 0),
  CONSTRAINT ck_candidate_created_by CHECK (length(btrim(created_by)) > 0),
  CONSTRAINT ck_candidate_idempotency_actor CHECK (length(btrim(idempotency_actor)) > 0),
  CONSTRAINT ck_candidate_idempotency_key CHECK (length(btrim(idempotency_key)) > 0)
);

CREATE UNIQUE INDEX uq_candidate_source_revision
  ON core.assessment_candidate_revision (source_proposal_id, candidate_revision);

CREATE UNIQUE INDEX uq_candidate_intake_idempotency
  ON core.assessment_candidate_revision (idempotency_actor, idempotency_key);

CREATE INDEX idx_candidate_interaction
  ON core.assessment_candidate_revision (interaction_id);

CREATE INDEX idx_candidate_digest
  ON core.assessment_candidate_revision (proposal_digest);

CREATE FUNCTION core.reject_assessment_candidate_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'assessment candidate revisions are immutable; % is not permitted', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_assessment_candidate_revision_immutable
BEFORE UPDATE OR DELETE ON core.assessment_candidate_revision
FOR EACH ROW EXECUTE FUNCTION core.reject_assessment_candidate_mutation();

COMMENT ON TABLE core.assessment_candidate_revision IS
  'S0-07 immutable Spring-owned AI assessment candidate revisions; always UNVERIFIED until M1-T12';
COMMENT ON COLUMN core.assessment_candidate_revision.proposal_digest IS
  'SHA-256 of the canonical approval-relevant candidate payload, excluding tracing/runtime metadata';
COMMENT ON COLUMN core.assessment_candidate_revision.model_id_unavailable_reason IS
  'Why provider/model identity is null when the current AI contract does not expose it';
