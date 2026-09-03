-- Assessment items become more than multiple choice.
--
-- A diagnostic built entirely from recognition questions produces exactly one kind of evidence, and
-- MASTERY_STATUS_POLICY_V2 asks for breadth it cannot supply. Fill-in-the-blank tests recall of a
-- precise term rather than recognition of it among four; short answer and use-case test whether a
-- learner can explain and apply. The schema has to admit all four before the bank can hold them.
--
-- Only two of the four can be scored deterministically today. SHORT_ANSWER and USE_CASE are
-- authorable here and are deliberately NOT reachable by a learner: the free-text evaluation path is
-- blocked behind M2-ADR-022, and content that cannot be marked must never enter a learner's form.
-- The application enforces that; this migration only makes the content representable.

-- ---------------------------------------------------------------------------------------------
-- Item types
-- ---------------------------------------------------------------------------------------------
--
-- A widening of a membership CHECK. The previous image writes only SINGLE_CHOICE, which the new
-- predicate still admits, and the compatibility checker proves that by comparing the value sets
-- rather than taking anyone's word for it.
ALTER TABLE core.assessment_item_version DROP CONSTRAINT ck_assessment_item_type;
ALTER TABLE core.assessment_item_version ADD CONSTRAINT ck_assessment_item_type CHECK (
  item_type IN ('SINGLE_CHOICE', 'FILL_BLANK', 'SHORT_ANSWER', 'USE_CASE')
);

-- ---------------------------------------------------------------------------------------------
-- Options belong to the types that have options
-- ---------------------------------------------------------------------------------------------
--
-- V005 required two or more options for every item, which is right for multiple choice and
-- impossible for the other three: there is nothing to choose from in "ordering is guaranteed within
-- a ______". The requirement becomes conditional on the type.
--
-- This cannot be proved safe by comparing value sets the way the type widening above can -- it needs
-- to reason about a predicate, which the checker deliberately does not attempt -- so it is declared,
-- and the declaration is narrow enough that it licences this statement and nothing else.
ALTER TABLE core.assessment_item_version DROP CONSTRAINT ck_assessment_item_options;
-- relaxes-constraint: ck_assessment_item_options, the previous image writes only SINGLE_CHOICE rows with two or more options and every one of those still satisfies this predicate; it widens only to admit types that have no options at all
ALTER TABLE core.assessment_item_version ADD CONSTRAINT ck_assessment_item_options CHECK (
  jsonb_typeof(options_jsonb) = 'array'
  AND (
    (item_type = 'SINGLE_CHOICE' AND jsonb_array_length(options_jsonb) >= 2)
    OR (item_type <> 'SINGLE_CHOICE' AND jsonb_array_length(options_jsonb) = 0)
  )
);

-- ---------------------------------------------------------------------------------------------
-- An answer key shaped like the question it answers
-- ---------------------------------------------------------------------------------------------
--
-- A FILL_BLANK whose key carries `correct` instead of `accepted` would be scored against option ids
-- that do not exist -- silently, and as a wrong answer -- so the shape is worth constraining rather
-- than leaving to convention.
--
-- SINGLE_CHOICE is deliberately left exactly as V005 had it: an object, with nothing said about its
-- contents. Requiring `correct` would be a tightening, and the previous image can write an answer
-- key without it -- rarely, but the gate refuses "rarely" and is right to. That requirement lives in
-- the application, which is where it already effectively lived. What this constraint adds applies
-- only to types the previous image cannot produce at all, so every row it can write still passes.
--
-- SHORT_ANSWER and USE_CASE carry a rubric. A rubric is not a scoring key: nothing in this release
-- reads it, and M2-ADR-022 governs what may ever be done with it.
ALTER TABLE core.assessment_item_version DROP CONSTRAINT ck_assessment_item_answer_key;
-- COALESCE, not a bare jsonb_typeof, in the CHECK below. On a missing key
-- `answer_key_jsonb -> 'accepted'` is NULL, jsonb_typeof(NULL) is NULL, and a CHECK that evaluates
-- to NULL is satisfied -- so the obvious spelling of this constraint admits exactly the malformed
-- rows it was written to refuse. Verified against PostgreSQL rather than reasoned about: the first
-- version of this accepted a FILL_BLANK carrying `correct` and a USE_CASE carrying no rubric at all.
-- relaxes-constraint: ck_assessment_item_answer_key, SINGLE_CHOICE keeps V005's requirement unchanged and the added shape rules bind only item types the previous image cannot write, so every row it can write still satisfies this
ALTER TABLE core.assessment_item_version ADD CONSTRAINT ck_assessment_item_answer_key CHECK (
  jsonb_typeof(answer_key_jsonb) = 'object'
  AND (
    item_type = 'SINGLE_CHOICE'
    OR (
      item_type = 'FILL_BLANK'
      AND COALESCE(jsonb_typeof(answer_key_jsonb -> 'accepted'), '') = 'array'
      AND COALESCE(jsonb_array_length(answer_key_jsonb -> 'accepted'), 0) >= 1
    )
    OR (
      item_type IN ('SHORT_ANSWER', 'USE_CASE')
      AND COALESCE(jsonb_typeof(answer_key_jsonb -> 'rubric'), '') = 'object'
    )
  )
);

COMMENT ON COLUMN core.assessment_item_version.item_type IS
  'SINGLE_CHOICE and FILL_BLANK are scored deterministically. SHORT_ANSWER and USE_CASE are '
  'authorable content only: they are not selectable into a learner form and not accepted for '
  'submission until the evaluation authority boundary in M2-ADR-022 is resolved.';

-- ---------------------------------------------------------------------------------------------
-- Which packet policy assembled an attempt
-- ---------------------------------------------------------------------------------------------
--
-- The composition rules for a form are about to change more than once: today's packet is whatever
-- coverage selects from scoreable types, the next is a typed quota, and the one after that adds the
-- free-text items. An attempt has to record which of those produced it, or a seven-item attempt
-- from this release reads later as an eleven-item attempt that lost four questions.
--
-- Nullable, and never backfilled: attempts created before this column existed were assembled by a
-- policy that had no name, and inventing one for them would be a false provenance claim.
ALTER TABLE core.assessment_attempt
  ADD COLUMN packet_policy VARCHAR(48)
    CONSTRAINT ck_assessment_attempt_packet_policy
    CHECK (packet_policy IS NULL OR length(btrim(packet_policy)) > 0);

COMMENT ON COLUMN core.assessment_attempt.packet_policy IS
  'Version of the packet composition policy that decided which item types and how many this '
  'attempt contains. NULL for attempts that predate typed packets.';
