-- Per-attempt diagnostic form selection.
--
-- Until now the form was the pool: every verified item of the pinned assessment version, shown in
-- the order its author gave them. That is only tenable while a pool is exactly one form long. As
-- the Kafka pool grows past the number of questions any one learner should sit, the platform has to
-- choose -- and a choice that is not written down is a measurement nobody can reproduce.
--
-- So the chosen form is persisted per attempt, item by item, with the reason each item earned its
-- slot. Three readers depend on that record: the learner's read path (which items to render, and in
-- which order), the submission path (which items an answer may reference at all), and, months
-- later, whoever has to explain why this learner's mastery came out where it did. Recomputing the
-- selection instead of storing it would answer none of the three, because the inputs it depends on
-- -- what the learner had recently seen, and a random draw -- are gone by the time anyone asks.

CREATE TABLE core.assessment_attempt_item (
  id UUID PRIMARY KEY,
  attempt_id UUID NOT NULL REFERENCES core.assessment_attempt(id) ON DELETE RESTRICT,
  item_version_id UUID NOT NULL REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT,
  -- The learner-facing position, which is a property of this form and not of the item. Two learners
  -- selecting the same item see it in different places, so it cannot live on the content row.
  presentation_order INTEGER NOT NULL,
  -- Why this item is here: it was the first item selected for its skill, the first for its
  -- difficulty band, or it filled a remaining slot. Recorded because "which items" without "why"
  -- cannot distinguish a form that met its coverage rule from one that happened to look like it.
  selection_reason VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- An item appears at most once in a form, and no two items share a position.
  UNIQUE (attempt_id, item_version_id),
  UNIQUE (attempt_id, presentation_order),
  CONSTRAINT ck_assessment_attempt_item_order CHECK (presentation_order > 0),
  CONSTRAINT ck_assessment_attempt_item_reason CHECK (
    selection_reason IN ('SKILL_COVERAGE', 'DIFFICULTY_COVERAGE', 'FILL')
  )
);

COMMENT ON TABLE core.assessment_attempt_item IS
  'Exactly which items were selected for one attempt, in presentation order, with the reason each '
  'was chosen. Written once at attempt creation and immutable thereafter.';

-- Names the algorithm that assembled the form, the way assessment scoring records its own version.
-- Nullable because attempts created before this migration were served the whole pool and were not
-- assembled by any policy; a value invented for them would be a false provenance claim.
--
-- The check is written as a column constraint of the ADD COLUMN rather than as a separate
-- ADD CONSTRAINT, because that is what makes it safe to roll an image back against. A rollback
-- restores the previous image and never the schema, so a constraint added to an existing table can
-- refuse a write that image still makes. This one cannot: it arrives with the column, and the
-- previous image has never heard of that column, so it writes NULL there -- which the check admits.
-- Attached to the column, that argument is structural rather than something a reader has to take on
-- trust from a comment.
ALTER TABLE core.assessment_attempt
  ADD COLUMN selection_policy VARCHAR(48)
    CONSTRAINT ck_assessment_attempt_selection_policy
    CHECK (selection_policy IS NULL OR length(btrim(selection_policy)) > 0);

COMMENT ON COLUMN core.assessment_attempt.selection_policy IS
  'Version of the form-selection policy that assembled this attempt. NULL for attempts that '
  'predate per-attempt selection and were served the entire item pool.';

-- The selection is written inside the attempt-creation transaction and never again. Making that
-- structural rather than conventional is the point of persisting it at all: a form that can be
-- quietly rewritten after the learner has answered is not a record of what they were asked.
CREATE FUNCTION core.protect_assessment_attempt_item()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  attempt_status VARCHAR(16);
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'selected assessment items are immutable' USING ERRCODE = '55000';
  END IF;
  SELECT status INTO attempt_status FROM core.assessment_attempt WHERE id = NEW.attempt_id;
  IF attempt_status IS DISTINCT FROM 'IN_PROGRESS' THEN
    RAISE EXCEPTION 'items may only be selected for an in-progress attempt' USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_assessment_attempt_item_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.assessment_attempt_item
FOR EACH ROW EXECUTE FUNCTION core.protect_assessment_attempt_item();

-- With a selected form, "an item of this assessment version" is no longer the same question as "an
-- item this learner was asked". Answering an item that was never presented would produce evidence
-- for a question nobody saw, and the response table is where that becomes permanent -- so the check
-- belongs here as well as in the submission service.
--
-- Guarded on the presence of a selection rather than applied unconditionally: attempts created
-- before V045 have no selected items and were legitimately served the whole pool. Refusing their
-- responses would rewrite history to fail.
CREATE OR REPLACE FUNCTION core.protect_assessment_response()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  attempt_status VARCHAR(16);
  selected_items INTEGER;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'assessment responses are immutable' USING ERRCODE = '55000';
  END IF;
  SELECT status INTO attempt_status FROM core.assessment_attempt WHERE id = NEW.attempt_id;
  IF attempt_status IS DISTINCT FROM 'IN_PROGRESS' THEN
    RAISE EXCEPTION 'responses may only be added to an in-progress attempt'
      USING ERRCODE = '55000';
  END IF;
  SELECT count(*) INTO selected_items
    FROM core.assessment_attempt_item WHERE attempt_id = NEW.attempt_id;
  IF selected_items > 0 AND NOT EXISTS (
    SELECT 1 FROM core.assessment_attempt_item
     WHERE attempt_id = NEW.attempt_id AND item_version_id = NEW.item_version_id
  ) THEN
    RAISE EXCEPTION 'item % was not selected for attempt %', NEW.item_version_id, NEW.attempt_id
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

-- Selection asks, for every candidate item, when this learner last saw it. The attempt side of that
-- join is already served by idx_assessment_attempt_learner and the attempt_id side by the unique
-- key above; this index is what keeps the item-first direction from scanning the whole table as
-- attempt history accumulates.
CREATE INDEX ix_assessment_attempt_item_recency
  ON core.assessment_attempt_item (item_version_id, attempt_id);
