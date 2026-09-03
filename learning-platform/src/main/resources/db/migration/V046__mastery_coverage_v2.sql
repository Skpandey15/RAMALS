-- Objective and difficulty coverage: the metadata the mastery policy has always required and
-- never had.
--
-- The V1 engines ask two coverage questions before confirming MASTERED -- has the learner been
-- measured across the objectives the skill requires, and across the difficulty bands it requires --
-- and neither could be answered from what the platform stored. Objective coverage was passed as a
-- literal 0 and difficulty coverage as an empty set, not because anyone chose that, but because no
-- row anywhere recorded which objective a question assessed or at what band a learner had been
-- observed. The arithmetic was correct and the inputs did not exist, so MASTERED was unreachable:
-- objective coverage is 35% of the confidence blend, capping confidence at 0.65 against a 0.75
-- threshold, and the band gate could never be satisfied by an empty set.
--
-- This migration adds the missing facts. It changes no engine and no existing value: V1 stays
-- exactly as it was, and the snapshots already written under it stay reproducible, because nothing
-- here rewrites a row.

-- ---------------------------------------------------------------------------------------------
-- Which objective does a question assess?
-- ---------------------------------------------------------------------------------------------
--
-- A separate table rather than a column on the item. V005 makes the items of a published
-- assessment version immutable to INSERT, UPDATE and DELETE alike, and tagging the five existing
-- KAFKA items means writing a *different* value to each of them -- so unlike V017, which could
-- classify every row identically through an ADD COLUMN default, there is no DDL that does this.
-- The alternative would be suspending the immutability trigger, which is exactly the guarantee
-- that keeps pinned historical attempts interpretable, so it is not suspended here.
--
-- A join table is also the honest shape: an item may assess more than one objective, and the
-- curriculum already models objectives as first-class rows with their own identifiers.
CREATE TABLE core.assessment_item_objective (
  item_version_id UUID NOT NULL REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT,
  objective_id UUID NOT NULL REFERENCES core.learning_objective(id) ON DELETE RESTRICT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (item_version_id, objective_id)
);

COMMENT ON TABLE core.assessment_item_objective IS
  'Which learning objective(s) an assessment item assesses. The authoritative source of objective '
  'coverage; never inferred from item text, stem wording, or any learner-facing label.';

CREATE INDEX ix_assessment_item_objective_objective
  ON core.assessment_item_objective (objective_id, item_version_id);

-- The curated KAFKA v1 diagnostic, tagged against the objectives its five items already assess.
-- Each is the single required objective of the skill the item is bound to, so the tagging states a
-- fact the curriculum already asserted rather than inventing a new one.
INSERT INTO core.assessment_item_objective (item_version_id, objective_id) VALUES
  -- KAFKA_DIAG_BROKER -> BROKER_RESPONSIBILITY
  ('01900000-0000-7000-8000-000000000411', '01900000-0000-7000-8000-000000000301'),
  -- KAFKA_DIAG_TOPIC -> TOPIC_SEMANTICS
  ('01900000-0000-7000-8000-000000000412', '01900000-0000-7000-8000-000000000302'),
  -- KAFKA_DIAG_PARTITION -> PARTITION_ORDERING
  ('01900000-0000-7000-8000-000000000413', '01900000-0000-7000-8000-000000000303'),
  -- KAFKA_DIAG_ACKS -> ACK_DURABILITY
  ('01900000-0000-7000-8000-000000000414', '01900000-0000-7000-8000-000000000307'),
  -- KAFKA_DIAG_CONSUMER_GROUPS -> GROUP_ASSIGNMENT
  ('01900000-0000-7000-8000-000000000415', '01900000-0000-7000-8000-000000000309');

-- An item may only be tagged with an objective belonging to the skill it assesses. Without this an
-- item could be tagged against another skill's objective and would then credit coverage on a skill
-- it never measured -- the one way objective coverage could be made to lie.
CREATE FUNCTION core.validate_assessment_item_objective()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  item_skill UUID;
  objective_skill UUID;
BEGIN
  SELECT skill_id INTO item_skill
    FROM core.assessment_item_version WHERE id = NEW.item_version_id;
  SELECT sv.skill_id INTO objective_skill
    FROM core.learning_objective lo
    JOIN core.skill_version sv ON sv.id = lo.skill_version_id
   WHERE lo.id = NEW.objective_id;
  IF item_skill IS DISTINCT FROM objective_skill THEN
    RAISE EXCEPTION
      'objective % belongs to skill % but item % assesses skill %',
      NEW.objective_id, objective_skill, NEW.item_version_id, item_skill
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_assessment_item_objective_skill_match
BEFORE INSERT OR UPDATE ON core.assessment_item_objective
FOR EACH ROW EXECUTE FUNCTION core.validate_assessment_item_objective();

-- ---------------------------------------------------------------------------------------------
-- What did a piece of evidence actually cover?
-- ---------------------------------------------------------------------------------------------
--
-- Recorded on the evidence row at the moment it is produced, rather than re-derived later by
-- walking back to the responses. Evidence is the ledger the mastery engine reads, and a coverage
-- claim that has to be reconstructed through four joins is a claim that can change after the fact
-- if any of those joins does. It also has to work for evidence that has no responses to walk back
-- to, such as a rubric evaluation.
--
-- Additive and nullable, like V009: this is DDL, no row is written, and the append-only trigger is
-- untouched. Evidence that predates this migration therefore carries NULL, which the V2 engine
-- reads as "covered nothing" -- see the column comments. Nothing is backfilled, because the facts
-- needed to backfill honestly (which objective an untagged item assessed) do not exist.
ALTER TABLE ledger.evidence
  ADD COLUMN covered_objective_ids UUID[],
  ADD COLUMN covered_difficulty_bands VARCHAR(16)[];

ALTER TABLE ledger.evidence
  ADD CONSTRAINT ck_evidence_covered_bands_known CHECK (
    covered_difficulty_bands IS NULL
    OR covered_difficulty_bands <@ ARRAY['EASY', 'MEDIUM', 'HARD']::VARCHAR(16)[]
  );

COMMENT ON COLUMN ledger.evidence.covered_objective_ids IS
  'Learning objectives this observation measured. NULL for evidence recorded before V046, which '
  'the V2 policy treats as covering nothing -- coverage is never fabricated for legacy rows.';

COMMENT ON COLUMN ledger.evidence.covered_difficulty_bands IS
  'Mastery difficulty bands this observation measured at, mapped from item difficulty by '
  'AssessmentDifficulty. NULL for evidence recorded before V046 and treated as covering nothing.';

-- ---------------------------------------------------------------------------------------------
-- Why did a snapshot come out the way it did?
-- ---------------------------------------------------------------------------------------------
--
-- A snapshot already records the confidence blend and its threshold, which explains a withheld
-- MASTERED when confidence is the reason. It could not explain a withheld MASTERED when band
-- coverage was the reason, and it could not distinguish a snapshot computed with objective coverage
-- forced to zero from one computed with objective coverage measured. Both are recorded now, and the
-- status policy that made the decision is named.
ALTER TABLE ledger.mastery_snapshot
  ADD COLUMN status_policy_version VARCHAR(32),
  ADD COLUMN objective_coverage NUMERIC(5, 4),
  ADD COLUMN covered_difficulty_bands VARCHAR(16)[];

ALTER TABLE ledger.mastery_snapshot
  ADD CONSTRAINT ck_mastery_snapshot_objective_coverage CHECK (
    objective_coverage IS NULL OR (objective_coverage >= 0 AND objective_coverage <= 1)
  );

ALTER TABLE ledger.mastery_snapshot
  ADD CONSTRAINT ck_mastery_snapshot_covered_bands_known CHECK (
    covered_difficulty_bands IS NULL
    OR covered_difficulty_bands <@ ARRAY['EASY', 'MEDIUM', 'HARD']::VARCHAR(16)[]
  );

COMMENT ON COLUMN ledger.mastery_snapshot.status_policy_version IS
  'The status policy that decided this status. NULL for snapshots written before V046, all of '
  'which were decided by MASTERY_STATUS_POLICY_V1 with objective coverage forced to 0 and no '
  'covered difficulty bands -- the conditions under which MASTERED was unreachable.';
