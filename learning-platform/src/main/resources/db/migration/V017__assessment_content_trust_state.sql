-- Trust state for assessment content (M1-ADR-006).
--
-- M1-T10 introduces AI-generated candidate items. An item is not like a tutor explanation: it
-- persists, many learners see it, and answers to it become evidence that the deterministic engines
-- convert into mastery. A bad item is wrong in a way nothing downstream can detect -- every MVP-0
-- control works exactly as designed and produces a wrong answer, because the measurement was wrong.
--
-- So content carries a trust state, and the schema is arranged so that the dangerous state is the
-- one you have to work to reach.

-- Content that predates this column is the hand-authored MVP-0 curriculum: written, reviewed and
-- published by people before any generator existed. It is classified by the ADD COLUMN default and
-- never by an UPDATE, because V005 makes items of a published assessment version immutable to
-- INSERT, UPDATE and DELETE alike. That trigger is not an obstacle to work around here -- it is the
-- reason pinned historical attempts stay interpretable -- and classifying rows through DDL means no
-- row is ever written and the immutability guarantee is never suspended, not even for one statement.
ALTER TABLE core.assessment_item_version
  ADD COLUMN trust_state VARCHAR(24) NOT NULL DEFAULT 'VERIFIED_CONTENT',
  -- Which pipeline stage refused it. "Rejected" alone tells an author nothing about what to fix and
  -- an operator nothing about whether the generator or the curriculum is drifting.
  ADD COLUMN rejected_at_stage VARCHAR(32),
  ADD COLUMN rejected_reason VARCHAR(256),
  -- Who approved it, and when. Named columns rather than a flag: "approved" with nobody attached is
  -- not an approval, it is a value somebody set.
  ADD COLUMN verified_by VARCHAR(255) DEFAULT 'mvp0-curriculum-authoring',
  ADD COLUMN verified_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;

-- Having classified the existing rows, the defaults flip for everything written from now on. New
-- content is UNVERIFIED on purpose: a generator that forgets to set a trust state produces content
-- that cannot reach a learner, which is the correct direction for that mistake to fail.
ALTER TABLE core.assessment_item_version
  ALTER COLUMN trust_state SET DEFAULT 'UNVERIFIED',
  ALTER COLUMN verified_by DROP DEFAULT,
  ALTER COLUMN verified_at DROP DEFAULT;

ALTER TABLE core.assessment_item_version
  ADD CONSTRAINT ck_assessment_item_trust_state
  CHECK (trust_state IN ('UNVERIFIED', 'VERIFIED_CONTENT', 'REJECTED'));

-- Verified content must name a human and a moment. This is M1-ADR-006's approval requirement made
-- structural: there is no way to write VERIFIED_CONTENT without saying who approved it, so no code
-- path -- present or future, deliberate or accidental -- can promote content anonymously.
ALTER TABLE core.assessment_item_version
  ADD CONSTRAINT ck_assessment_item_verified_has_reviewer
  CHECK (
    trust_state <> 'VERIFIED_CONTENT'
    OR (verified_by IS NOT NULL AND length(btrim(verified_by)) > 0 AND verified_at IS NOT NULL)
  );

-- Rejected content must say which stage rejected it, for the same reason.
ALTER TABLE core.assessment_item_version
  ADD CONSTRAINT ck_assessment_item_rejected_has_stage
  CHECK (
    trust_state <> 'REJECTED'
    OR (rejected_at_stage IS NOT NULL AND length(btrim(rejected_at_stage)) > 0)
  );

-- The stages are the pipeline in M1-ADR-006, in order. A closed set because an open text column
-- would let the first unfamiliar stage arrive spelled three ways and make the counters meaningless.
ALTER TABLE core.assessment_item_version
  ADD CONSTRAINT ck_assessment_item_rejection_stage_known
  CHECK (
    rejected_at_stage IS NULL
    OR rejected_at_stage IN ('STRUCTURAL', 'DETERMINISTIC_POLICY', 'QUALITY_SAFETY', 'HUMAN_REVIEW')
  );

-- Publishing is the other place unverified content could reach a learner, and it would do so
-- quietly. The selection filter below would simply omit unverified items, so a version published
-- with half its items unverified serves a shorter diagnostic rather than failing -- a measurement
-- silently taken over less evidence than it claims. V005 already refuses to publish a version with
-- no items; this extends the same gate to items that exist but nobody approved.
CREATE OR REPLACE FUNCTION core.validate_assessment_publication()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  unverified_items INTEGER;
BEGIN
  IF OLD.status = 'DRAFT' AND NEW.status = 'PUBLISHED' THEN
    IF NOT EXISTS (
      SELECT 1 FROM core.assessment_item_version WHERE assessment_version_id = NEW.id
    ) THEN
      RAISE EXCEPTION 'assessment version % has no items', NEW.id USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO unverified_items
      FROM core.assessment_item_version
     WHERE assessment_version_id = NEW.id AND trust_state <> 'VERIFIED_CONTENT';
    IF unverified_items > 0 THEN
      RAISE EXCEPTION
        'assessment version % has % item(s) that are not VERIFIED_CONTENT', NEW.id, unverified_items
        USING ERRCODE = '23514';
    END IF;
    NEW.published_at := COALESCE(NEW.published_at, CURRENT_TIMESTAMP);
  ELSIF OLD.status IN ('PUBLISHED', 'RETIRED') THEN
    IF NOT (OLD.status = 'PUBLISHED' AND NEW.status = 'RETIRED'
        AND NEW.assessment_id = OLD.assessment_id
        AND NEW.curriculum_version_id = OLD.curriculum_version_id
        AND NEW.version_code = OLD.version_code
        AND NEW.published_at = OLD.published_at
        AND NEW.created_at = OLD.created_at) THEN
      RAISE EXCEPTION 'published assessment metadata is immutable' USING ERRCODE = '55000';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

-- Learner-facing selection filters on this column on every attempt, so it is worth an index rather
-- than a sequential scan that grows with the candidate queue.
CREATE INDEX ix_assessment_item_version_trust
  ON core.assessment_item_version (assessment_version_id, trust_state);

COMMENT ON COLUMN core.assessment_item_version.trust_state IS
  'M1-ADR-006. UNVERIFIED on creation; VERIFIED_CONTENT only after the staged pipeline and the '
  'human approval the policy requires; REJECTED records which stage refused it. Only '
  'VERIFIED_CONTENT may be served in a scored context.';

COMMENT ON COLUMN core.assessment_item_version.verified_by IS
  'Subject of the human who approved this content. Required by constraint for VERIFIED_CONTENT.';
