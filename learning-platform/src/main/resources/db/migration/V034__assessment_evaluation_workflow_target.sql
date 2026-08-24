-- M2-T14 activation prerequisite 1: bind accepted evaluation decisions to the immutable,
-- Spring-owned facts that a controlled workflow is allowed to apply.
--
-- The columns are nullable for expand/contract compatibility with V031 rows already present. The
-- previous V033 image also omits these columns when it writes a decision, so a CHECK constraint
-- requiring new metadata on every accepted row would make image rollback unsafe. The trigger below
-- therefore permits an all-null legacy row, while enforcing completeness and parentage whenever the
-- new application supplies target or score metadata. The application boundary refuses to trigger a
-- workflow from a legacy accepted row that has no frozen target.

ALTER TABLE ledger.assessment_evaluation_decision
  ADD COLUMN learner_id UUID REFERENCES core.learner(id) ON DELETE RESTRICT,
  ADD COLUMN skill_id UUID REFERENCES core.skill(id) ON DELETE RESTRICT,
  ADD COLUMN curriculum_version_id UUID REFERENCES core.curriculum_version(id) ON DELETE RESTRICT,
  ADD COLUMN attempt_id UUID REFERENCES core.assessment_attempt(id) ON DELETE RESTRICT,
  ADD COLUMN assessment_version_id UUID REFERENCES core.assessment_version(id) ON DELETE RESTRICT,
  ADD COLUMN normalized_score NUMERIC(5, 4),
  ADD COLUMN score_policy_version VARCHAR(64);

CREATE INDEX idx_assessment_evaluation_target
  ON ledger.assessment_evaluation_decision (learner_id, skill_id, curriculum_version_id, decided_at DESC)
  WHERE outcome = 'ACCEPTED';

CREATE OR REPLACE FUNCTION ledger.validate_assessment_evaluation_target()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.learner_id IS NULL
     AND NEW.skill_id IS NULL
     AND NEW.curriculum_version_id IS NULL
     AND NEW.attempt_id IS NULL
     AND NEW.assessment_version_id IS NULL
     AND NEW.normalized_score IS NULL
     AND NEW.score_policy_version IS NULL THEN
    RETURN NEW;
  END IF;

  IF NEW.learner_id IS NULL
     OR NEW.skill_id IS NULL
     OR NEW.curriculum_version_id IS NULL
     OR NEW.attempt_id IS NULL
     OR NEW.assessment_version_id IS NULL
     OR (NEW.outcome = 'ACCEPTED'
         AND (NEW.normalized_score IS NULL
              OR NEW.score_policy_version IS NULL
              OR NEW.score_policy_version <> 'EVALUATION_SCORE_POLICY_V1'))
     OR (NEW.outcome <> 'ACCEPTED'
         AND (NEW.normalized_score IS NOT NULL
              OR NEW.score_policy_version IS NOT NULL))
     OR (NEW.normalized_score IS NOT NULL
         AND (NEW.normalized_score < 0 OR NEW.normalized_score > 1))
     OR (NEW.score_policy_version IS NOT NULL
         AND length(btrim(NEW.score_policy_version)) NOT BETWEEN 1 AND 64) THEN
    RAISE EXCEPTION 'assessment evaluation target metadata is incomplete or invalid'
      USING ERRCODE = '23514';
  END IF;

  IF NOT EXISTS (
    SELECT 1
      FROM ledger.grounding_retrieval_record grounding
      JOIN core.assessment_attempt attempt
        ON attempt.id = NEW.attempt_id
       AND attempt.learner_id = NEW.learner_id
       AND attempt.assessment_version_id = NEW.assessment_version_id
      JOIN core.assessment_version assessment_version
        ON assessment_version.id = NEW.assessment_version_id
       AND assessment_version.curriculum_version_id = NEW.curriculum_version_id
      JOIN core.assessment_item_version assessment_item
        ON assessment_item.assessment_version_id = NEW.assessment_version_id
       AND assessment_item.skill_id = NEW.skill_id
      JOIN core.skill_version skill_version
        ON skill_version.skill_id = NEW.skill_id
       AND skill_version.curriculum_version_id = NEW.curriculum_version_id
     WHERE grounding.context_id = NEW.context_id
       AND grounding.learner_id = NEW.learner_id
  ) THEN
    RAISE EXCEPTION 'assessment evaluation target does not match authoritative facts'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_assessment_evaluation_target
BEFORE INSERT ON ledger.assessment_evaluation_decision
FOR EACH ROW EXECUTE FUNCTION ledger.validate_assessment_evaluation_target();

COMMENT ON COLUMN ledger.assessment_evaluation_decision.learner_id IS
  'Spring-owned learner target captured when the accepted evaluation decision is made';
COMMENT ON COLUMN ledger.assessment_evaluation_decision.skill_id IS
  'Spring-owned skill target captured when the accepted evaluation decision is made';
COMMENT ON COLUMN ledger.assessment_evaluation_decision.curriculum_version_id IS
  'Version-pinned curriculum target captured when the accepted evaluation decision is made';
COMMENT ON COLUMN ledger.assessment_evaluation_decision.attempt_id IS
  'Version-pinned assessment attempt target captured when the accepted evaluation decision is made';
COMMENT ON COLUMN ledger.assessment_evaluation_decision.assessment_version_id IS
  'Version-pinned assessment target captured when the accepted evaluation decision is made';
COMMENT ON COLUMN ledger.assessment_evaluation_decision.normalized_score IS
  'Frozen Spring-derived rubric score used by authoritative evaluation evidence';
COMMENT ON COLUMN ledger.assessment_evaluation_decision.score_policy_version IS
  'Version of the deterministic rubric-to-score policy used for normalized_score';
