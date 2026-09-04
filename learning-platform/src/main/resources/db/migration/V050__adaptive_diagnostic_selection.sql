-- The adaptive selector: which policy assembles a version's form, and the richer vocabulary it
-- needs to explain why an item earned its slot.
--
-- V045's selector fills a target size from whatever the pool offers, with no notion of item type
-- and no notion of the learner's evidence. It is exactly right for the KAFKA v1 pool it was built
-- for -- five items, one per skill, nothing to be adaptive about -- and it stays exactly as it is:
-- frozen, still the default, still what every attempt gets unless a version says otherwise.
--
-- The KAFKA v2 pool V049 authored is a different shape: seven scoreable items per skill, a typed
-- packet target (5 SINGLE_CHOICE + 2 FILL_BLANK), and enough content to run more than one adaptive
-- cycle without repeating a question. Serving that pool through V045's selector is exactly the
-- hazard V049's own migration comment documents -- an arbitrary type mix, reliably longer than the
-- transitional packet -- so a different selector has to exist before v2 can ever be published.

-- ---------------------------------------------------------------------------------------------
-- Which selection policy governs a version
-- ---------------------------------------------------------------------------------------------
--
-- Declared on the version rather than inferred from its content shape: a selector chosen by
-- "does this pool have more than one item type" is a selector nobody wrote down choosing, and the
-- day a v1-shaped pool legitimately wants the v2 selector (or vice versa) that inference silently
-- breaks. NULL means exactly what it means for packet_policy below -- this version predates typed
-- selection and V045's selector, unchanged, still assembles its form.
--
-- Declaring this on the v2 row is metadata, not publication: it says which selector *would* run if
-- v2 were published, so the selector can be built and proved against real content while v2 stays
-- DRAFT. Publishing is still the separate, later decision V049 left to whichever PR is ready to
-- serve the pool correctly.
ALTER TABLE core.assessment_version
  ADD COLUMN selection_policy_version VARCHAR(48)
    CONSTRAINT ck_assessment_version_selection_policy
    CHECK (selection_policy_version IS NULL OR length(btrim(selection_policy_version)) > 0);

COMMENT ON COLUMN core.assessment_version.selection_policy_version IS
  'Which form-selection policy assembles an attempt against this version. NULL means the legacy '
  'DIAGNOSTIC_SELECTION_V1 selector (io.ramals...assessment.DiagnosticFormSelector), unchanged. '
  'Declaring a value here does not publish the version; it only names which selector would run.';

UPDATE core.assessment_version
   SET selection_policy_version = 'DIAGNOSTIC_SELECTION_V2'
 WHERE id = '01900000-0000-7000-8000-000000000403'; -- KAFKA v2, still DRAFT

-- ---------------------------------------------------------------------------------------------
-- A richer selection-reason vocabulary
-- ---------------------------------------------------------------------------------------------
--
-- V045's three reasons describe a selector that only ever asks "is this skill/difficulty covered
-- yet". The adaptive selector asks a question V045 never could -- what does this learner's own
-- evidence say about this skill -- and an audit trail that cannot distinguish "chosen because
-- nothing has been asked yet" from "chosen because the evidence says this learner is struggling"
-- from "chosen to confirm mastery already earned" has thrown away the fact a reviewer most needs.
--
-- Pure superset of the three V045 values: the DROP+ADD pair below only widens the membership
-- check, so the migration-compatibility checker accepts it as a rollback-safe widening on its own,
-- the same way V047 widened ck_assessment_item_type. RETENTION_CHECK is deliberately not among
-- these -- spaced-repetition reassessment is a future, separately versioned policy, and adding its
-- reason now would let ordinary bank exhaustion quietly borrow authority that was never granted to
-- it.
ALTER TABLE core.assessment_attempt_item DROP CONSTRAINT ck_assessment_attempt_item_reason;
ALTER TABLE core.assessment_attempt_item ADD CONSTRAINT ck_assessment_attempt_item_reason CHECK (
  selection_reason IN (
    'SKILL_COVERAGE', 'DIFFICULTY_COVERAGE', 'FILL',
    'UNSEEN_ITEM', 'LOW_CONFIDENCE', 'WEAK_SKILL', 'OBJECTIVE_COVERAGE_GAP',
    'DIFFICULTY_PROGRESSION', 'MASTERY_CONFIRMATION'
  )
);
