-- Logical-question identity, independent of assessment_item_version.
--
-- core.assessment is versioned by core.assessment_version, and core.skill is versioned by
-- core.skill_version. core.assessment_item_version carries no equivalent: it is named as a version
-- of something that was never modelled. item_code is unique only within one assessment_version
-- (V005's UNIQUE (assessment_version_id, item_code)), so it is a scoping key, not an identity --
-- nothing stops two different questions sharing a code across versions, or the same question
-- changing code when it moves into a new version.
--
-- That gap becomes load-bearing the moment a no-repeat guarantee has to survive an editorial
-- version. A learner who saw KAFKA_DIAG_BROKER in the v1 diagnostic and is then served a better-
-- worded row of the same question under a new item_version_id in v2 has not received new evidence,
-- however the identifier changed underneath it. Recognising that requires an identity the version
-- rows point at rather than one inferred from their codes -- and inferring it from stem-text
-- similarity is explicitly the wrong tool: two different questions can share most of their wording,
-- and one editorial pass can change every word of the same question.
--
-- core.assessment_item_lineage is that identity. It resolves item_version_id -> logical_item_id and
-- nothing else: no stem, no skill, no difficulty duplicated from the version row it points at,
-- because the version row is still the only place those facts live. A logical item is established by
-- its first version and referenced, never re-derived, by every version after it.

CREATE TABLE core.assessment_item_lineage (
  -- The version this row describes. One row per item_version_id: a version is one concrete
  -- authoring of one logical question, and cannot be two questions at once.
  item_version_id UUID PRIMARY KEY
    REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT,
  -- The identity that survives an editorial revision. Not a foreign key to anything -- there is
  -- nothing else to reference. It is minted by the first version of a logical question and copied,
  -- verbatim, onto every later version that is a revision of the same question rather than a new
  -- one. That copying is an editorial decision made when a version is authored; this table records
  -- the decision, it does not compute it.
  logical_item_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE core.assessment_item_lineage IS
  'Resolves an item version to the logical question it is a version of. The no-repeat exclusion for '
  'a learner keys on logical_item_id, never on item_version_id or on item_code convention, so an '
  'editorial revision of a question a learner already saw is still recognised as already seen.';

COMMENT ON COLUMN core.assessment_item_lineage.logical_item_id IS
  'Stable across every editorial version of the same question. Minted once, by the first version; '
  'never inferred from item_code or from stem-text similarity.';

-- The direction selection needs: given a version in the eligible pool, which logical question is it
-- a version of. A learner's exposure history is small (well under a thousand rows even after years
-- of use), and the primary key above already serves that lookup directly -- this index is for the
-- other direction, finding every version that shares a logical identity, which content tooling and
-- a future retention policy both need and which the primary key does not serve.
CREATE INDEX ix_assessment_item_lineage_logical
  ON core.assessment_item_lineage (logical_item_id, item_version_id);

-- ---------------------------------------------------------------------------------------------
-- Backfill: the five v1 items, each its own logical identity.
-- ---------------------------------------------------------------------------------------------
--
-- An INSERT, not an UPDATE -- V005 makes the items of a published version immutable to INSERT,
-- UPDATE and DELETE alike, and that trigger governs core.assessment_item_version, not this table.
-- Adding a lineage row changes nothing about the item it describes.
--
-- Each of the five gets a distinct identity because that is what is true of them: five different
-- authored questions, none a revision of another. Assigned from a fresh UUID block (...-0501 through
-- ...-0505) so a logical identity is never mistakable for an item_version_id or any other id already
-- in use.
INSERT INTO core.assessment_item_lineage (item_version_id, logical_item_id) VALUES
  ('01900000-0000-7000-8000-000000000411', '01900000-0000-7000-8000-000000000501'), -- KAFKA_DIAG_BROKER
  ('01900000-0000-7000-8000-000000000412', '01900000-0000-7000-8000-000000000502'), -- KAFKA_DIAG_TOPIC
  ('01900000-0000-7000-8000-000000000413', '01900000-0000-7000-8000-000000000503'), -- KAFKA_DIAG_PARTITION
  ('01900000-0000-7000-8000-000000000414', '01900000-0000-7000-8000-000000000504'), -- KAFKA_DIAG_ACKS
  ('01900000-0000-7000-8000-000000000415', '01900000-0000-7000-8000-000000000505'); -- KAFKA_DIAG_CONSUMER_GROUPS

-- ---------------------------------------------------------------------------------------------
-- Every published item must have a logical identity.
-- ---------------------------------------------------------------------------------------------
--
-- Extending core.validate_assessment_publication again, the way V017 extended it for trust state.
-- Requiring lineage at authoring time (an INSERT trigger on assessment_item_version) would refuse
-- content mid-review, before anyone has decided whether a candidate is a new question or a revision
-- of one. Requiring it at publication is the same place V017 already requires VERIFIED_CONTENT: the
-- last moment before the item can reach a learner and become something the no-repeat guarantee has
-- to reason about.
CREATE OR REPLACE FUNCTION core.validate_assessment_publication()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  unverified_items INTEGER;
  unlineaged_items INTEGER;
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
    SELECT count(*) INTO unlineaged_items
      FROM core.assessment_item_version iv
      LEFT JOIN core.assessment_item_lineage lin ON lin.item_version_id = iv.id
     WHERE iv.assessment_version_id = NEW.id AND lin.item_version_id IS NULL;
    IF unlineaged_items > 0 THEN
      RAISE EXCEPTION
        'assessment version % has % item(s) with no logical identity in assessment_item_lineage',
        NEW.id, unlineaged_items
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

COMMENT ON COLUMN core.assessment_item_version.item_code IS
  'Unique within one assessment_version only (V005). Not a cross-version identity -- see '
  'core.assessment_item_lineage for the identity a no-repeat guarantee must key on.';
