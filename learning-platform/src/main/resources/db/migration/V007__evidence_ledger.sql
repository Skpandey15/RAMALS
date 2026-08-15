-- Immutable evidence ledger. Evidence is an append-only observation of a learner's
-- performance on a skill. Corrections never rewrite history: they append ADJUSTMENT
-- evidence that references the superseded row. Idempotency is enforced by a
-- source-lineage uniqueness key, so a retried write reuses the original evidence
-- rather than duplicating it.
--
-- Immutability is enforced at two layers: the runtime role holds only SELECT and
-- INSERT on ledger tables (from the V002 default privileges), and an append-only
-- trigger additionally rejects any UPDATE or DELETE regardless of role.

CREATE TABLE ledger.evidence (
  id UUID PRIMARY KEY,
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  skill_id UUID NOT NULL REFERENCES core.skill(id) ON DELETE RESTRICT,
  evidence_type VARCHAR(16) NOT NULL,
  source_type VARCHAR(24) NOT NULL,
  source_attempt_id UUID REFERENCES core.assessment_attempt(id) ON DELETE RESTRICT,
  source_assessment_version_id UUID REFERENCES core.assessment_version(id) ON DELETE RESTRICT,
  scoring_version VARCHAR(32),
  adjusts_evidence_id UUID REFERENCES ledger.evidence(id) ON DELETE RESTRICT,
  lineage_key TEXT NOT NULL,
  observed_score NUMERIC(5, 4) NOT NULL,
  normalized_score NUMERIC(5, 4) NOT NULL,
  items_answered INTEGER NOT NULL DEFAULT 0,
  items_correct INTEGER NOT NULL DEFAULT 0,
  interaction_id VARCHAR(64) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (lineage_key),
  CONSTRAINT ck_evidence_type
    CHECK (evidence_type IN ('DIAGNOSTIC', 'QUIZ', 'PRACTICE', 'SCENARIO', 'ADJUSTMENT')),
  CONSTRAINT ck_evidence_source_type CHECK (source_type IN ('ASSESSMENT_ATTEMPT', 'ADJUSTMENT')),
  CONSTRAINT ck_evidence_observed CHECK (observed_score >= 0 AND observed_score <= 1),
  CONSTRAINT ck_evidence_normalized CHECK (normalized_score >= 0 AND normalized_score <= 1),
  CONSTRAINT ck_evidence_item_counts
    CHECK (items_answered >= 0 AND items_correct >= 0 AND items_correct <= items_answered),
  CONSTRAINT ck_evidence_interaction_id CHECK (length(btrim(interaction_id)) > 0),
  CONSTRAINT ck_evidence_lineage_key CHECK (length(btrim(lineage_key)) > 0),
  CONSTRAINT ck_evidence_adjustment_link
    CHECK ((evidence_type = 'ADJUSTMENT') = (adjusts_evidence_id IS NOT NULL)),
  CONSTRAINT ck_evidence_attempt_source
    CHECK (source_type <> 'ASSESSMENT_ATTEMPT' OR source_attempt_id IS NOT NULL)
);

COMMENT ON TABLE ledger.evidence IS 'Append-only learner performance evidence; corrections append ADJUSTMENT rows';
COMMENT ON COLUMN ledger.evidence.lineage_key IS 'Source-lineage idempotency key; unique per logical observation';
COMMENT ON COLUMN ledger.evidence.interaction_id IS 'interactionId provenance of the request that produced this evidence';

CREATE INDEX idx_evidence_learner_skill
  ON ledger.evidence (learner_id, skill_id, occurred_at);
CREATE INDEX idx_evidence_source_attempt
  ON ledger.evidence (source_attempt_id);

CREATE FUNCTION ledger.reject_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'ledger.evidence is append-only; % is not permitted', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_evidence_append_only
BEFORE UPDATE OR DELETE ON ledger.evidence
FOR EACH ROW EXECUTE FUNCTION ledger.reject_evidence_mutation();
