-- Misconception relationship graph foundation (M2-ADR-033 step 1): authored, non-authoritative
-- edges among published misconceptions, and from a published misconception to the curriculum
-- prerequisite skill it is commonly rooted in. This is the "cross-node causal relationship"
-- M2-ADR-026 §8 explicitly deferred, brought forward now against the misconception graph in
-- docs/architecture/target-intelligence-loop.md.
--
-- FOUNDATION / REPRESENTATION ONLY (M2-ADR-033 §6). This migration:
--   * adds two additive tables and nothing else;
--   * is not read by DiagnosticService, DiagnosticSubmissionService, DIAGNOSTIC_SELECTION_V1-V5
--     (or a future V6), ProbeRelationshipResolver, core.diagnostic_probe_relationship (V054),
--     core.diagnostic_probe_provenance (V055), or core.misconception_evidence_observation (V058);
--   * introduces no calculator, no band, no snapshot table, no confidence policy-version
--     identifier -- (learner_id, misconception_id) confidence remains exactly G3
--     (DIAGNOSTIC_CONFIDENCE_V1, M2-ADR-028), and an edge never carries or implies a probability
--     that a specific learner holds a misconception (M2-ADR-033 §4);
--   * touches none of core.diagnostic_node, core.misconception, core.assessment_item_option_
--     misconception, core.skill, core.skill_prerequisite, core.learning_objective, and every
--     migration V001-V060 keeps its own content untouched.
--
-- A misconception stays orthogonal to the LearningObjective -> Concept -> Sub-concept tree
-- (M2-ADR-026 §1/§3): edges live in their own tables keyed by FK to core.misconception(id) (and,
-- for the prerequisite link, core.skill(id)); no edge inserts a row into core.skill /
-- core.learning_objective / core.diagnostic_node, and no edge makes a misconception a parent or
-- child of a node. There is no misconceptionLevel / graphHierarchyLevel column here.

-- =========================================================================================
-- core.misconception_relationship: an authored MISCONCEPTION_RELATED edge between two published
-- misconceptions, carrying a relationship type from a small closed set (M2-ADR-033 §1):
--   * CO_OCCURS_WITH, CONTRASTS_WITH -- symmetric by meaning: stored once under the canonical
--     ordering misconception_a_id < misconception_b_id, so "A RELATED B" and "B RELATED A"
--     cannot both exist as separate logical edges.
--   * SPECIALISES -- inherently directed ("misconception_a_id is a more specific case of
--     misconception_b_id"); keeps its authored direction, exempt from the ordering rule, and is
--     acyclic (enforced below in PostgreSQL, and re-checked in application code).
-- GENERALISES is simply SPECIALISES read in the other direction and is never a stored value.
--
-- An edge is authored domain knowledge about misconceptions in the abstract. MISCONCEPTION_RELATED
-- does NOT mean "A causes B", "A is more probable than B", "A should be tested before B", or that
-- any learner holds either misconception.
-- =========================================================================================

CREATE TABLE core.misconception_relationship (
  id UUID PRIMARY KEY,
  misconception_a_id UUID NOT NULL REFERENCES core.misconception(id) ON DELETE RESTRICT,
  misconception_b_id UUID NOT NULL REFERENCES core.misconception(id) ON DELETE RESTRICT,
  relationship_type VARCHAR(24) NOT NULL,
  -- DRAFT is freely editable for content authoring; PUBLISHED is immutable, the same discipline
  -- core.diagnostic_probe_relationship (V054) and core.misconception (V057) already hold
  -- hand-authored diagnostic content to.
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  -- Required, not optional: an unexplained authored relationship is not auditable, exactly as
  -- core.diagnostic_probe_relationship.rationale (V054) already requires for a hand-authored link.
  rationale TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at TIMESTAMPTZ,
  -- Two different typed relationships between the same pair are allowed; a duplicate of the same
  -- (pair, type) is not. Combined with the canonical ordering below this is the database's final
  -- concurrency boundary against two simultaneous authored requests creating one logical edge.
  UNIQUE (misconception_a_id, misconception_b_id, relationship_type),
  CONSTRAINT ck_misconception_relationship_not_self
    CHECK (misconception_a_id <> misconception_b_id),
  CONSTRAINT ck_misconception_relationship_type CHECK (
    relationship_type IN ('CO_OCCURS_WITH', 'SPECIALISES', 'CONTRASTS_WITH')
  ),
  -- Canonical ordering for the symmetric types, so the pair is stored once. SPECIALISES is
  -- directed and keeps its authored a -> b direction, so it is exempt.
  CONSTRAINT ck_misconception_relationship_canonical_order CHECK (
    relationship_type = 'SPECIALISES' OR misconception_a_id < misconception_b_id
  ),
  CONSTRAINT ck_misconception_relationship_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
  CONSTRAINT ck_misconception_relationship_rationale CHECK (length(btrim(rationale)) > 0),
  CONSTRAINT ck_misconception_relationship_publication_time CHECK (
    (status = 'DRAFT' AND published_at IS NULL)
    OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
  )
);

COMMENT ON TABLE core.misconception_relationship IS
  'Hand-authored MISCONCEPTION_RELATED edges between two published misconceptions '
  '(CO_OCCURS_WITH / SPECIALISES / CONTRASTS_WITH), for the M2-ADR-033 misconception relationship '
  'graph foundation. Authored structural knowledge only: never a learner-scoped probability, never '
  'a causal claim, never read by DIAGNOSTIC_SELECTION or ProbeRelationshipResolver.';

CREATE INDEX idx_misconception_relationship_a
  ON core.misconception_relationship (misconception_a_id, relationship_type, status);
CREATE INDEX idx_misconception_relationship_b
  ON core.misconception_relationship (misconception_b_id, relationship_type, status);

-- Immutable once published, the published-endpoint-only rule, and SPECIALISES acyclicity -- three
-- facts no FK or CHECK on this row's own columns can express, since each requires looking at
-- another row. The same reasoning V055 and V057's own guard triggers already follow.
--
-- SPECIALISES acyclicity uses the identical WITH RECURSIVE shape core.reject_prerequisite_cycle
-- (V003) already uses for core.skill_prerequisite: an edge (a, b, SPECIALISES) is rejected if b
-- can already reach a by following SPECIALISES edges. All rows are considered (DRAFT and
-- PUBLISHED), so a cycle can never be authored in DRAFT and then published.
CREATE FUNCTION core.protect_misconception_relationship()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  endpoint_a_status VARCHAR(16);
  endpoint_b_status VARCHAR(16);
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status = 'PUBLISHED' THEN
      RAISE EXCEPTION 'published misconception relationship % is immutable', OLD.id
        USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.status = 'PUBLISHED' THEN
    RAISE EXCEPTION 'published misconception relationship % is immutable', OLD.id
      USING ERRCODE = '55000';
  END IF;

  IF NEW.status = 'PUBLISHED' THEN
    SELECT status INTO endpoint_a_status FROM core.misconception WHERE id = NEW.misconception_a_id;
    SELECT status INTO endpoint_b_status FROM core.misconception WHERE id = NEW.misconception_b_id;
    IF endpoint_a_status IS DISTINCT FROM 'PUBLISHED'
       OR endpoint_b_status IS DISTINCT FROM 'PUBLISHED' THEN
      RAISE EXCEPTION
        'a published misconception relationship may reference only published misconceptions '
        '(% is %, % is %)',
        NEW.misconception_a_id, endpoint_a_status, NEW.misconception_b_id, endpoint_b_status
        USING ERRCODE = '23514';
    END IF;
  END IF;

  IF NEW.relationship_type = 'SPECIALISES' THEN
    IF EXISTS (
      WITH RECURSIVE reachable(misconception_id) AS (
        SELECT NEW.misconception_b_id
        UNION
        SELECT edge.misconception_b_id
        FROM core.misconception_relationship edge
        JOIN reachable current_node
          ON edge.misconception_a_id = current_node.misconception_id
        WHERE edge.relationship_type = 'SPECIALISES'
      )
      SELECT 1 FROM reachable WHERE misconception_id = NEW.misconception_a_id
    ) THEN
      RAISE EXCEPTION 'SPECIALISES cycle detected: % already specialises (transitively) %',
        NEW.misconception_b_id, NEW.misconception_a_id USING ERRCODE = '23514';
    END IF;
  END IF;

  IF NEW.status = 'PUBLISHED' THEN
    NEW.published_at := COALESCE(NEW.published_at, CURRENT_TIMESTAMP);
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_misconception_relationship_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.misconception_relationship
FOR EACH ROW EXECUTE FUNCTION core.protect_misconception_relationship();

-- =========================================================================================
-- core.misconception_prerequisite_link: an authored MISCONCEPTION_PREREQUISITE_LINK edge from a
-- published misconception to a core.skill that is a curriculum prerequisite of the skill owning
-- the misconception's target node (M2-ADR-033 §1). It means "this misconception is commonly rooted
-- in that unsecured prerequisite skill." It is a kept-separate table because its far endpoint is a
-- curriculum entity, not a misconception (M2-ADR-033 §2); the link never targets a
-- core.learning_objective (core.skill_prerequisite has no objective endpoint).
--
-- It is an authored diagnostic hint. It does NOT prove a root cause, gate progression, alter
-- mastery, or trigger a diagnostic probe.
-- =========================================================================================

CREATE TABLE core.misconception_prerequisite_link (
  id UUID PRIMARY KEY,
  misconception_id UUID NOT NULL REFERENCES core.misconception(id) ON DELETE RESTRICT,
  -- A plain FK to core.skill(id); never to core.learning_objective. The publish-time trigger
  -- below asserts this skill is an actual core.skill_prerequisite of the misconception's owning
  -- skill for the misconception's curriculum version, so the edge is implicitly pinned to one
  -- curriculum version and one domain without needing its own curriculum_version_id column.
  prerequisite_skill_id UUID NOT NULL REFERENCES core.skill(id) ON DELETE RESTRICT,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  rationale TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at TIMESTAMPTZ,
  UNIQUE (misconception_id, prerequisite_skill_id),
  CONSTRAINT ck_misconception_prerequisite_link_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
  CONSTRAINT ck_misconception_prerequisite_link_rationale CHECK (length(btrim(rationale)) > 0),
  CONSTRAINT ck_misconception_prerequisite_link_publication_time CHECK (
    (status = 'DRAFT' AND published_at IS NULL)
    OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
  )
);

COMMENT ON TABLE core.misconception_prerequisite_link IS
  'Hand-authored MISCONCEPTION_PREREQUISITE_LINK edges from a published misconception to a '
  'core.skill that is a curriculum prerequisite of the skill owning the misconception target node '
  '(M2-ADR-033 §1). An authored diagnostic hint -- never a computed causal claim, never read as '
  'fact by any selector.';

CREATE INDEX idx_misconception_prerequisite_link_misconception
  ON core.misconception_prerequisite_link (misconception_id, status);
CREATE INDEX idx_misconception_prerequisite_link_skill
  ON core.misconception_prerequisite_link (prerequisite_skill_id, status);

-- Immutable once published; at publish time the misconception must itself be PUBLISHED and a
-- matching core.skill_prerequisite row must exist between the misconception's owning skill and the
-- referenced prerequisite skill for the misconception's curriculum version -- the EXISTS-shaped
-- validation V055 §8a and V057's core.protect_diagnostic_node already use for facts no plain CHECK
-- can express. The owning (skill_id, curriculum_version_id) is resolved through the misconception's
-- exclusive-arc target: a direct objective, a CONCEPT's objective, or a SUB_CONCEPT's parent
-- CONCEPT's objective.
CREATE FUNCTION core.protect_misconception_prerequisite_link()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  misconception_status VARCHAR(16);
  owning_skill_id UUID;
  owning_curriculum_version_id UUID;
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status = 'PUBLISHED' THEN
      RAISE EXCEPTION 'published misconception prerequisite link % is immutable', OLD.id
        USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.status = 'PUBLISHED' THEN
    RAISE EXCEPTION 'published misconception prerequisite link % is immutable', OLD.id
      USING ERRCODE = '55000';
  END IF;

  IF NEW.status = 'PUBLISHED' THEN
    SELECT status INTO misconception_status
      FROM core.misconception WHERE id = NEW.misconception_id;
    IF misconception_status IS DISTINCT FROM 'PUBLISHED' THEN
      RAISE EXCEPTION
        'a published misconception prerequisite link may reference only a published misconception '
        '(% is %)', NEW.misconception_id, misconception_status USING ERRCODE = '23514';
    END IF;

    SELECT sv.skill_id, sv.curriculum_version_id
      INTO owning_skill_id, owning_curriculum_version_id
      FROM core.misconception m
      LEFT JOIN core.diagnostic_node dn ON dn.id = m.target_diagnostic_node_id
      LEFT JOIN core.diagnostic_node dnp ON dnp.id = dn.parent_node_id
      JOIN core.learning_objective lo
        ON lo.id = COALESCE(m.target_objective_id, dn.objective_id, dnp.objective_id)
      JOIN core.skill_version sv ON sv.id = lo.skill_version_id
     WHERE m.id = NEW.misconception_id;

    IF owning_skill_id IS NULL THEN
      RAISE EXCEPTION
        'cannot resolve the owning skill for misconception % -- its target arc does not reach a '
        'learning objective', NEW.misconception_id USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
      SELECT 1 FROM core.skill_prerequisite sp
       WHERE sp.curriculum_version_id = owning_curriculum_version_id
         AND sp.skill_id = owning_skill_id
         AND sp.prerequisite_skill_id = NEW.prerequisite_skill_id
    ) THEN
      RAISE EXCEPTION
        'skill % is not a curriculum prerequisite of misconception %''s owning skill % for '
        'curriculum version %',
        NEW.prerequisite_skill_id, NEW.misconception_id, owning_skill_id,
        owning_curriculum_version_id USING ERRCODE = '23514';
    END IF;

    NEW.published_at := COALESCE(NEW.published_at, CURRENT_TIMESTAMP);
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_misconception_prerequisite_link_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.misconception_prerequisite_link
FOR EACH ROW EXECUTE FUNCTION core.protect_misconception_prerequisite_link();

-- =========================================================================================
-- Minimal grounded seed, on the real, seeded KAFKA_PRODUCER_ACKS v2 vertical slice V057 already
-- established. Not a broad authoring pass: exactly one new misconception, one MISCONCEPTION_RELATED
-- edge, and one MISCONCEPTION_PREREQUISITE_LINK.
--
--   * ...0f03 (V057): "acks=all alone guarantees full durability regardless of min.insync.replicas"
--     -- targets SUB_CONCEPT ...0f02 -> CONCEPT ...0f01 -> objective ...0d11 (ACK_DURABILITY) on
--     skill_version ...0b07 = skill ...0107 (KAFKA_PRODUCER_ACKS), curriculum version ...0004.
--   * ...0f04 (new): a contrasting durability misconception on the same objective ...0d11.
--   * ...0107 has a real core.skill_prerequisite on ...0101 (KAFKA_BROKER) for version ...0004
--     (V052 carried V003's edges forward), so ...0f03 -> ...0101 is a valid prerequisite link.
-- Canonical ordering for the symmetric CONTRASTS_WITH edge: ...0f03 < ...0f04, so a = ...0f03.
-- =========================================================================================

INSERT INTO core.misconception
  (id, name, description, target_objective_id, target_diagnostic_node_id, status, published_at)
VALUES
  ('01900000-0000-7000-8000-000000000f04',
   'acks=1 and acks=all give the same durability once the leader has responded',
   'Believes that once the partition leader acknowledges a produce request the record is equally '
   'durable under acks=1 and acks=all, overlooking that acks=all additionally waits for the '
   'in-sync replica set so a leader failure immediately after an acks=1 response can still lose '
   'the record.',
   '01900000-0000-7000-8000-000000000d11', NULL, 'PUBLISHED', CURRENT_TIMESTAMP);

INSERT INTO core.misconception_relationship
  (id, misconception_a_id, misconception_b_id, relationship_type, status, rationale, published_at)
VALUES
  ('01900000-0000-7000-8000-000000000f10',
   '01900000-0000-7000-8000-000000000f03', '01900000-0000-7000-8000-000000000f04',
   'CONTRASTS_WITH', 'PUBLISHED',
   'Both concern the durability guarantee acks provides on the KAFKA_PRODUCER_ACKS ACK_DURABILITY '
   'objective, but from opposite errors: ...0f03 over-trusts acks=all while ignoring '
   'min.insync.replicas; ...0f04 under-values acks=all by treating it as equivalent to acks=1. A '
   'probe that surfaces the min.insync.replicas / in-sync-replica-set distinction discriminates '
   'between the two evidence states.',
   CURRENT_TIMESTAMP);

INSERT INTO core.misconception_prerequisite_link
  (id, misconception_id, prerequisite_skill_id, status, rationale, published_at)
VALUES
  ('01900000-0000-7000-8000-000000000f20',
   '01900000-0000-7000-8000-000000000f03', '01900000-0000-7000-8000-000000000101',
   'PUBLISHED',
   'The acks / min.insync.replicas durability misconception is commonly rooted in an unsecured '
   'KAFKA_BROKER prerequisite: a learner who has not internalised how the broker keeps the in-sync '
   'replica set has no mental model for why min.insync.replicas changes what acks=all guarantees. '
   'KAFKA_BROKER (...0101) is a core.skill_prerequisite of KAFKA_PRODUCER_ACKS (...0107) for '
   'curriculum version ...0004.',
   CURRENT_TIMESTAMP);
