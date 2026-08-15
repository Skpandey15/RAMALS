-- Progression retention. RetentionPolicyV1 does not decay scores in MVP-0; it records
-- when a skill was last evidenced and last mastered, and schedules a retention_due_at.
-- The schedule is maintained by a trigger that reacts to each appended mastery snapshot:
-- every snapshot refreshes last_evidence_at, and a MASTERED snapshot (re)sets
-- last_success_at and retention_due_at. Progression states are derived from the
-- immutable snapshots and this schedule, so nothing here rewrites mastery history.

CREATE TABLE core.skill_retention (
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  skill_id UUID NOT NULL REFERENCES core.skill(id) ON DELETE RESTRICT,
  curriculum_version_id UUID NOT NULL REFERENCES core.curriculum_version(id) ON DELETE RESTRICT,
  last_evidence_at TIMESTAMPTZ NOT NULL,
  last_success_at TIMESTAMPTZ,
  retention_due_at TIMESTAMPTZ,
  retention_policy_version VARCHAR(32) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (learner_id, skill_id, curriculum_version_id)
);

COMMENT ON TABLE core.skill_retention IS
  'RetentionPolicyV1 schedule maintained from mastery snapshots; no score decay in MVP-0';

CREATE INDEX idx_skill_retention_due
  ON core.skill_retention (learner_id, curriculum_version_id, retention_due_at);

CREATE TRIGGER trg_skill_retention_touch_updated_at
BEFORE UPDATE ON core.skill_retention
FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE FUNCTION core.maintain_skill_retention()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  INSERT INTO core.skill_retention (
    learner_id, skill_id, curriculum_version_id, last_evidence_at, last_success_at,
    retention_due_at, retention_policy_version)
  VALUES (
    NEW.learner_id, NEW.skill_id, NEW.curriculum_version_id, NEW.calculated_at,
    CASE WHEN NEW.mastery_status = 'MASTERED' THEN NEW.calculated_at END,
    CASE WHEN NEW.mastery_status = 'MASTERED' THEN NEW.calculated_at + INTERVAL '30 days' END,
    'RETENTION_POLICY_V1')
  ON CONFLICT (learner_id, skill_id, curriculum_version_id) DO UPDATE SET
    last_evidence_at = EXCLUDED.last_evidence_at,
    last_success_at = COALESCE(EXCLUDED.last_success_at, skill_retention.last_success_at),
    retention_due_at = COALESCE(EXCLUDED.retention_due_at, skill_retention.retention_due_at),
    retention_policy_version = EXCLUDED.retention_policy_version;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_mastery_snapshot_retention
AFTER INSERT ON ledger.mastery_snapshot
FOR EACH ROW EXECUTE FUNCTION core.maintain_skill_retention();
