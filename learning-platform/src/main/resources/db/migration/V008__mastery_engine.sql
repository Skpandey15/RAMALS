-- Deterministic mastery. A small mutable aggregate row coordinates recomputation per
-- learner, skill, and curriculum version; each recompute advances the aggregate
-- version under a row lock and appends exactly one immutable snapshot for that
-- version. Snapshots live in the append-only ledger and are never rewritten, so the
-- full mastery history stays reproducible.

CREATE TABLE core.learner_skill_aggregate (
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  skill_id UUID NOT NULL REFERENCES core.skill(id) ON DELETE RESTRICT,
  curriculum_version_id UUID NOT NULL REFERENCES core.curriculum_version(id) ON DELETE RESTRICT,
  aggregate_version INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (learner_id, skill_id, curriculum_version_id),
  CONSTRAINT ck_learner_skill_aggregate_version CHECK (aggregate_version >= 0)
);

COMMENT ON TABLE core.learner_skill_aggregate IS
  'Mutable coordination row: monotonic aggregate_version serializes mastery recomputation';

CREATE TRIGGER trg_learner_skill_aggregate_touch_updated_at
BEFORE UPDATE ON core.learner_skill_aggregate
FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TABLE ledger.mastery_snapshot (
  id UUID PRIMARY KEY,
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  skill_id UUID NOT NULL REFERENCES core.skill(id) ON DELETE RESTRICT,
  curriculum_version_id UUID NOT NULL REFERENCES core.curriculum_version(id) ON DELETE RESTRICT,
  aggregate_version INTEGER NOT NULL,
  mastery_score NUMERIC(5, 4) NOT NULL,
  mastery_status VARCHAR(24) NOT NULL,
  threshold NUMERIC(5, 4) NOT NULL,
  evidence_count INTEGER NOT NULL,
  items_considered INTEGER NOT NULL,
  algorithm_version VARCHAR(32) NOT NULL,
  interaction_id VARCHAR(64) NOT NULL,
  calculated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (learner_id, skill_id, curriculum_version_id, aggregate_version),
  CONSTRAINT ck_mastery_snapshot_score CHECK (mastery_score >= 0 AND mastery_score <= 1),
  CONSTRAINT ck_mastery_snapshot_threshold CHECK (threshold >= 0 AND threshold <= 1),
  CONSTRAINT ck_mastery_snapshot_version CHECK (aggregate_version >= 1),
  CONSTRAINT ck_mastery_snapshot_counts CHECK (evidence_count >= 0 AND items_considered >= 0),
  CONSTRAINT ck_mastery_snapshot_interaction CHECK (length(btrim(interaction_id)) > 0),
  CONSTRAINT ck_mastery_snapshot_status CHECK (mastery_status IN (
    'INSUFFICIENT_EVIDENCE', 'NEEDS_RETEACH', 'NEEDS_PRACTICE', 'DEVELOPING', 'MASTERED'))
);

COMMENT ON TABLE ledger.mastery_snapshot IS
  'Append-only mastery computations; one canonical snapshot per aggregate version';

CREATE INDEX idx_mastery_snapshot_latest
  ON ledger.mastery_snapshot (learner_id, skill_id, curriculum_version_id, aggregate_version DESC);

CREATE FUNCTION ledger.reject_mastery_snapshot_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'ledger.mastery_snapshot is append-only; % is not permitted', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_mastery_snapshot_append_only
BEFORE UPDATE OR DELETE ON ledger.mastery_snapshot
FOR EACH ROW EXECUTE FUNCTION ledger.reject_mastery_snapshot_mutation();
