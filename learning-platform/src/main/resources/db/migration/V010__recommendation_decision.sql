-- Recommendation and decision provenance. Every consequential recommendation is
-- backed by an immutable decision record that captures the exact mastery snapshot,
-- the denormalized score/confidence/threshold inputs, all algorithm and policy
-- versions, and the interaction/trace ids, so the decision is reconstructable and
-- auditable on its own. The learning_recommendation table is a lean current-state
-- surface that points at its decision record; it is never overloaded as the audit.

CREATE TABLE ledger.decision_record (
  id UUID PRIMARY KEY,
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  skill_id UUID NOT NULL REFERENCES core.skill(id) ON DELETE RESTRICT,
  curriculum_version_id UUID NOT NULL REFERENCES core.curriculum_version(id) ON DELETE RESTRICT,
  decision_type VARCHAR(24) NOT NULL,
  recommended_action VARCHAR(24) NOT NULL,
  reason_code VARCHAR(48) NOT NULL,
  mastery_status VARCHAR(24) NOT NULL,
  policy_decision VARCHAR(24) NOT NULL,
  source_snapshot_id UUID NOT NULL REFERENCES ledger.mastery_snapshot(id) ON DELETE RESTRICT,
  aggregate_version INTEGER NOT NULL,
  mastery_score NUMERIC(5, 4) NOT NULL,
  evidence_confidence NUMERIC(5, 4) NOT NULL,
  mastery_threshold NUMERIC(5, 4) NOT NULL,
  confidence_threshold NUMERIC(5, 4) NOT NULL,
  evidence_count INTEGER NOT NULL,
  items_considered INTEGER NOT NULL,
  mastery_algorithm_version VARCHAR(32) NOT NULL,
  confidence_algorithm_version VARCHAR(32) NOT NULL,
  policy_version VARCHAR(32) NOT NULL,
  interaction_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64),
  decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (source_snapshot_id),
  CONSTRAINT ck_decision_type CHECK (decision_type IN ('RECOMMENDATION')),
  CONSTRAINT ck_decision_action
    CHECK (recommended_action IN ('COLLECT_EVIDENCE', 'RETEACH', 'PRACTICE', 'ADVANCE')),
  CONSTRAINT ck_decision_policy_decision
    CHECK (policy_decision IN ('COLLECT_EVIDENCE', 'RETEACH', 'PRACTICE', 'ADVANCE')),
  CONSTRAINT ck_decision_mastery_score CHECK (mastery_score >= 0 AND mastery_score <= 1),
  CONSTRAINT ck_decision_confidence CHECK (evidence_confidence >= 0 AND evidence_confidence <= 1),
  CONSTRAINT ck_decision_thresholds CHECK (
    mastery_threshold >= 0 AND mastery_threshold <= 1
    AND confidence_threshold >= 0 AND confidence_threshold <= 1),
  CONSTRAINT ck_decision_counts CHECK (evidence_count >= 0 AND items_considered >= 0),
  CONSTRAINT ck_decision_interaction CHECK (length(btrim(interaction_id)) > 0)
);

COMMENT ON TABLE ledger.decision_record IS
  'Append-only provenance of consequential recommendations; reconstructable from its own columns';

CREATE INDEX idx_decision_record_learner
  ON ledger.decision_record (learner_id, skill_id, curriculum_version_id, decided_at DESC);
CREATE INDEX idx_decision_record_interaction ON ledger.decision_record (interaction_id);

CREATE FUNCTION ledger.reject_decision_record_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'ledger.decision_record is append-only; % is not permitted', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_decision_record_append_only
BEFORE UPDATE OR DELETE ON ledger.decision_record
FOR EACH ROW EXECUTE FUNCTION ledger.reject_decision_record_mutation();

CREATE TABLE core.learning_recommendation (
  id UUID PRIMARY KEY,
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  skill_id UUID NOT NULL REFERENCES core.skill(id) ON DELETE RESTRICT,
  curriculum_version_id UUID NOT NULL REFERENCES core.curriculum_version(id) ON DELETE RESTRICT,
  recommended_action VARCHAR(24) NOT NULL,
  reason_code VARCHAR(48) NOT NULL,
  mastery_status VARCHAR(24) NOT NULL,
  decision_record_id UUID NOT NULL REFERENCES ledger.decision_record(id) ON DELETE RESTRICT,
  source_snapshot_id UUID NOT NULL REFERENCES ledger.mastery_snapshot(id) ON DELETE RESTRICT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (decision_record_id),
  CONSTRAINT ck_recommendation_action
    CHECK (recommended_action IN ('COLLECT_EVIDENCE', 'RETEACH', 'PRACTICE', 'ADVANCE'))
);

COMMENT ON TABLE core.learning_recommendation IS
  'Current recommendation surface; audit provenance lives in ledger.decision_record';

CREATE INDEX idx_learning_recommendation_current
  ON core.learning_recommendation (learner_id, skill_id, curriculum_version_id, created_at DESC);
