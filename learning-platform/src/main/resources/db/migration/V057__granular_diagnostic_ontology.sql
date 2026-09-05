-- Granular diagnostic ontology foundation (M2-ADR-026): a content-driven, optional refinement of
-- LearningObjective (CONCEPT, and optionally one SUB_CONCEPT level under it), plus a separate,
-- orthogonal Misconception entity evidenced through a tagged wrong SINGLE_CHOICE option. Foundation
-- only -- see M2-ADR-026 §8: no wiring into DiagnosticService, DiagnosticSubmissionService,
-- DIAGNOSTIC_SELECTION_V2-V5, core.diagnostic_probe_provenance, or core.diagnostic_confidence_
-- observation. None of those, core.learning_objective, core.assessment_item_objective, or
-- core.diagnostic_probe_relationship (V054) is touched by this migration.
--
-- Concept/Sub-concept are never learning_objective rows (M2-ADR-026 §2/§9): they carry no coverage
-- requirement and no mastery threshold, and objectiveCoverage/mastery computation reads exactly what
-- it already reads (core.assessment_item_objective against core.learning_objective, unchanged).
-- They are also never a second, generic table shared with any future diagnostic subject type
-- (M2-ADR-026 §7): the exclusive-arc target on core.misconception below is a V1 decision, not a
-- claim that this shape is permanent.

-- -------------------------------------------------------------------------------------------
-- core.diagnostic_node: CONCEPT (belongs to exactly one LearningObjective) and, optionally,
-- SUB_CONCEPT (belongs to exactly one CONCEPT). No third level -- enforced below by the guard
-- trigger, since verifying a parent row's own node_type cannot be expressed as a plain CHECK on
-- this row's own columns alone.
-- -------------------------------------------------------------------------------------------

CREATE TABLE core.diagnostic_node (
  id UUID PRIMARY KEY,
  -- Exactly one of objective_id (CONCEPT) / parent_node_id (SUB_CONCEPT) is ever set -- the two
  -- shape CHECKs below both restate the same fact from each column's side, so neither can drift
  -- from the other without violating both.
  objective_id UUID REFERENCES core.learning_objective(id) ON DELETE RESTRICT,
  parent_node_id UUID REFERENCES core.diagnostic_node(id) ON DELETE RESTRICT,
  node_type VARCHAR(16) NOT NULL,
  name VARCHAR(160) NOT NULL,
  description TEXT NOT NULL,
  display_order INTEGER NOT NULL,
  -- DRAFT is freely editable; PUBLISHED is immutable -- the same authoring discipline
  -- core.diagnostic_probe_relationship (V054) already holds hand-authored diagnostic content to.
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at TIMESTAMPTZ,
  -- NULLs never collide under a plain UNIQUE constraint, so these two naturally enforce ordering
  -- only within whichever kind of parent a row actually has, with no partial/filtered index needed.
  UNIQUE (objective_id, display_order),
  UNIQUE (parent_node_id, display_order),
  CONSTRAINT ck_diagnostic_node_type CHECK (node_type IN ('CONCEPT', 'SUB_CONCEPT')),
  CONSTRAINT ck_diagnostic_node_objective_shape CHECK (
    (node_type = 'CONCEPT') = (objective_id IS NOT NULL)
  ),
  CONSTRAINT ck_diagnostic_node_parent_shape CHECK (
    (node_type = 'CONCEPT') = (parent_node_id IS NULL)
  ),
  CONSTRAINT ck_diagnostic_node_display_order CHECK (display_order > 0),
  CONSTRAINT ck_diagnostic_node_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
  CONSTRAINT ck_diagnostic_node_publication_time CHECK (
    (status = 'DRAFT' AND published_at IS NULL)
    OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
  )
);

COMMENT ON TABLE core.diagnostic_node IS
  'A content-driven, optional diagnostic refinement of one core.learning_objective: CONCEPT '
  '(objective_id set) or, one level under a CONCEPT, SUB_CONCEPT (parent_node_id set). Never itself '
  'a learning objective -- never read by objectiveCoverage or mastery computation.';

CREATE INDEX idx_diagnostic_node_objective ON core.diagnostic_node (objective_id) WHERE objective_id IS NOT NULL;
CREATE INDEX idx_diagnostic_node_parent ON core.diagnostic_node (parent_node_id) WHERE parent_node_id IS NOT NULL;

-- Immutable once published, and the one consistency fact no FK or CHECK above can express: a
-- SUB_CONCEPT's parent must itself be a CONCEPT (never another SUB_CONCEPT), which requires looking
-- up the parent row -- the same reasoning that already forced a trigger lookup in V055 for a fact no
-- plain CHECK on one row's own columns could prove.
CREATE FUNCTION core.protect_diagnostic_node()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  parent_type VARCHAR(16);
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status = 'PUBLISHED' THEN
      RAISE EXCEPTION 'published diagnostic node % is immutable', OLD.id USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.status = 'PUBLISHED' THEN
    RAISE EXCEPTION 'published diagnostic node % is immutable', OLD.id USING ERRCODE = '55000';
  END IF;

  IF NEW.parent_node_id IS NOT NULL THEN
    SELECT node_type INTO parent_type FROM core.diagnostic_node WHERE id = NEW.parent_node_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'parent_node_id % does not exist', NEW.parent_node_id USING ERRCODE = '23503';
    END IF;
    IF parent_type <> 'CONCEPT' THEN
      RAISE EXCEPTION 'a SUB_CONCEPT''s parent must be a CONCEPT (node % is %)',
        NEW.parent_node_id, parent_type USING ERRCODE = '23514';
    END IF;
  END IF;

  IF NEW.status = 'PUBLISHED' THEN
    NEW.published_at := COALESCE(NEW.published_at, CURRENT_TIMESTAMP);
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_diagnostic_node_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.diagnostic_node
FOR EACH ROW EXECUTE FUNCTION core.protect_diagnostic_node();

-- -------------------------------------------------------------------------------------------
-- core.misconception: a separate, orthogonal entity -- never a diagnostic_node itself. Targets
-- exactly one of a LearningObjective, a CONCEPT, or a SUB_CONCEPT (the latter two both being
-- core.diagnostic_node rows, distinguished by that row's own node_type -- no third target column
-- is needed for "concept vs. sub-concept").
-- -------------------------------------------------------------------------------------------

CREATE TABLE core.misconception (
  id UUID PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  description TEXT NOT NULL,
  target_objective_id UUID REFERENCES core.learning_objective(id) ON DELETE RESTRICT,
  target_diagnostic_node_id UUID REFERENCES core.diagnostic_node(id) ON DELETE RESTRICT,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at TIMESTAMPTZ,
  -- The exclusive arc: exactly one target, never both, never neither. num_nonnulls is the plain
  -- Postgres idiom for this shape -- no trigger needed, since both arms are this row's own columns.
  CONSTRAINT ck_misconception_target CHECK (
    num_nonnulls(target_objective_id, target_diagnostic_node_id) = 1
  ),
  CONSTRAINT ck_misconception_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
  CONSTRAINT ck_misconception_publication_time CHECK (
    (status = 'DRAFT' AND published_at IS NULL)
    OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
  )
);

COMMENT ON TABLE core.misconception IS
  'A named, specific, incorrect belief -- orthogonal to the diagnostic_node hierarchy, never a node '
  'itself. Targets exactly one LearningObjective or diagnostic_node (CONCEPT or SUB_CONCEPT). '
  'DRAFT/PUBLISHED, immutable once published.';

CREATE INDEX idx_misconception_target_objective
  ON core.misconception (target_objective_id) WHERE target_objective_id IS NOT NULL;
CREATE INDEX idx_misconception_target_node
  ON core.misconception (target_diagnostic_node_id) WHERE target_diagnostic_node_id IS NOT NULL;

CREATE FUNCTION core.protect_misconception()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status = 'PUBLISHED' THEN
      RAISE EXCEPTION 'published misconception % is immutable', OLD.id USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.status = 'PUBLISHED' THEN
    RAISE EXCEPTION 'published misconception % is immutable', OLD.id USING ERRCODE = '55000';
  END IF;
  IF NEW.status = 'PUBLISHED' THEN
    NEW.published_at := COALESCE(NEW.published_at, CURRENT_TIMESTAMP);
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_misconception_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.misconception
FOR EACH ROW EXECUTE FUNCTION core.protect_misconception();

-- -------------------------------------------------------------------------------------------
-- core.assessment_item_option_misconception: which specific wrong SINGLE_CHOICE option is evidence
-- of which misconception -- a pure external tag, the same "reference, do not duplicate" discipline
-- core.assessment_item_objective already holds for objective tagging. options_jsonb/answer_key_jsonb
-- on core.assessment_item_version are never modified.
-- -------------------------------------------------------------------------------------------

CREATE TABLE core.assessment_item_option_misconception (
  item_version_id UUID NOT NULL REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT,
  option_id VARCHAR(8) NOT NULL,
  misconception_id UUID NOT NULL REFERENCES core.misconception(id) ON DELETE RESTRICT,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at TIMESTAMPTZ,
  PRIMARY KEY (item_version_id, option_id, misconception_id),
  CONSTRAINT ck_assessment_item_option_misconception_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
  CONSTRAINT ck_assessment_item_option_misconception_publication_time CHECK (
    (status = 'DRAFT' AND published_at IS NULL)
    OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
  )
);

COMMENT ON TABLE core.assessment_item_option_misconception IS
  'A specific wrong SINGLE_CHOICE option, tagged as evidence of a specific misconception. An item '
  'with at least one PUBLISHED row here for misconception M is "misconception-evidence-eligible" '
  'for M -- a term deliberately distinct from H4b''s governed "probe" vocabulary '
  '(core.diagnostic_probe_relationship / core.diagnostic_probe_provenance): this table asserts no '
  'H4b probe relationship and is never read by ProbeRelationshipResolver.';

CREATE INDEX idx_assessment_item_option_misconception_misconception
  ON core.assessment_item_option_misconception (misconception_id, item_version_id);

-- Immutable once published, and the three consistency facts no FK or CHECK on this table's own
-- columns can express, since each requires inspecting a different row elsewhere: the item is
-- SINGLE_CHOICE; option_id genuinely exists among that item's own options_jsonb; option_id is not
-- one of that item's own correct answers (a misconception, by definition, is never what the correct
-- answer represents); and -- if this mapping is itself becoming PUBLISHED -- the misconception it
-- names is already PUBLISHED, never merely DRAFT.
--
-- Deliberately no composite foreign key against assessment_item_version(id, item_type): that table
-- already merged, and adding a new unique key to it now to support one would trip the same "breaks
-- rollback for the previous release's image" finding scripts/ci/check-migration-compatibility.py
-- caught for an analogous composite-FK attempt during the H5 hardening round -- the identical lesson
-- applies here, so this is a lookup in the guard trigger instead.
CREATE FUNCTION core.protect_assessment_item_option_misconception()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  item_row core.assessment_item_version%ROWTYPE;
  misconception_status VARCHAR(16);
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status = 'PUBLISHED' THEN
      RAISE EXCEPTION 'published misconception option mapping is immutable' USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.status = 'PUBLISHED' THEN
    RAISE EXCEPTION 'published misconception option mapping is immutable' USING ERRCODE = '55000';
  END IF;

  SELECT * INTO item_row FROM core.assessment_item_version WHERE id = NEW.item_version_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'item_version_id % does not exist', NEW.item_version_id USING ERRCODE = '23503';
  END IF;
  IF item_row.item_type <> 'SINGLE_CHOICE' THEN
    RAISE EXCEPTION 'misconception option mapping is only supported for SINGLE_CHOICE items (item % is %)',
      NEW.item_version_id, item_row.item_type USING ERRCODE = '23514';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM jsonb_array_elements(item_row.options_jsonb) opt
    WHERE opt ->> 'id' = NEW.option_id
  ) THEN
    RAISE EXCEPTION 'option % does not exist on item %', NEW.option_id, NEW.item_version_id
      USING ERRCODE = '23514';
  END IF;
  IF EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(item_row.answer_key_jsonb -> 'correct') AS correct_id
    WHERE correct_id = NEW.option_id
  ) THEN
    RAISE EXCEPTION 'option % is the correct answer for item % and cannot be tagged as a misconception option',
      NEW.option_id, NEW.item_version_id USING ERRCODE = '23514';
  END IF;

  IF NEW.status = 'PUBLISHED' THEN
    SELECT status INTO misconception_status FROM core.misconception WHERE id = NEW.misconception_id;
    IF misconception_status IS DISTINCT FROM 'PUBLISHED' THEN
      RAISE EXCEPTION 'misconception % must be PUBLISHED before this option mapping can be published',
        NEW.misconception_id USING ERRCODE = '23514';
    END IF;
    NEW.published_at := COALESCE(NEW.published_at, CURRENT_TIMESTAMP);
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_assessment_item_option_misconception_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.assessment_item_option_misconception
FOR EACH ROW EXECUTE FUNCTION core.protect_assessment_item_option_misconception();

-- -------------------------------------------------------------------------------------------
-- Minimal real Kafka vertical slice -- one objective decomposed one level (a CONCEPT), one further
-- SUB_CONCEPT under it, one misconception targeting the sub-concept, and one real wrong-option
-- mapping on a real, already-seeded v2 item. Not a broad re-tagging of the bank: every other
-- objective/item is untouched.
--
-- ACKS_DURABILITY_TRADEOFFS (d11, KAFKA_PRODUCER_ACKS, H3/V052) already has two real items
-- (...0624, ...0625). ACKS_MCQ_A1 (...0625) asks what durability gap min.insync.replicas=1 leaves
-- open despite acks=all; its own real wrong option "A" ("None; acks=all alone guarantees full
-- durability regardless of min.insync.replicas") is exactly the misconception below, verified
-- against V049's real seeded content, not invented to make a test pass.
-- -------------------------------------------------------------------------------------------

INSERT INTO core.diagnostic_node
  (id, objective_id, parent_node_id, node_type, name, description, display_order, status, published_at)
VALUES
  ('01900000-0000-7000-8000-000000000f01', '01900000-0000-7000-8000-000000000d11', NULL, 'CONCEPT',
   'min.insync.replicas interaction',
   'How acks and min.insync.replicas interact to determine the actual durability guarantee a producer receives.',
   1, 'PUBLISHED', CURRENT_TIMESTAMP),
  ('01900000-0000-7000-8000-000000000f02', NULL, '01900000-0000-7000-8000-000000000f01', 'SUB_CONCEPT',
   'single-ISR-ack durability gap',
   'The specific gap when min.insync.replicas=1: acks=all is satisfied by a single in-sync replica''s '
   'acknowledgment, which can be only the leader, leaving a window where a leader failure right after '
   'that ack can still lose the record.',
   1, 'PUBLISHED', CURRENT_TIMESTAMP);

INSERT INTO core.misconception
  (id, name, description, target_objective_id, target_diagnostic_node_id, status, published_at)
VALUES
  ('01900000-0000-7000-8000-000000000f03',
   'acks=all alone guarantees full durability regardless of min.insync.replicas',
   'Believes that setting acks=all is sufficient for full durability on its own, without regard to '
   'how min.insync.replicas is configured -- overlooking that with min.insync.replicas=1, acks=all '
   'can be satisfied by a single in-sync replica, which may be only the leader.',
   NULL, '01900000-0000-7000-8000-000000000f02', 'PUBLISHED', CURRENT_TIMESTAMP);

INSERT INTO core.assessment_item_option_misconception
  (item_version_id, option_id, misconception_id, status, published_at)
VALUES
  ('01900000-0000-7000-8000-000000000625', 'A', '01900000-0000-7000-8000-000000000f03',
   'PUBLISHED', CURRENT_TIMESTAMP);
