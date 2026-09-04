-- H4b foundation: deterministic, versionable relationships between diagnostic concepts, so an
-- unexpected miss can raise a hypothesis about a *related* objective, not just be scored and
-- forgotten. See M2-ADR-024.
--
-- Only two of the four requested relationship semantics need a new table here.
-- SAME_OBJECTIVE_CONFIRMATION is read from core.assessment_item_objective (V046): any other
-- verified item tagged to the same objective as the trigger item. PREREQUISITE_VALIDATION is read
-- from core.skill_prerequisite (V003): the trigger skill's curriculum prerequisite(s) and their
-- required objective(s). Storing either again here would create a second, independently-editable
-- copy of a fact the curriculum graph already owns -- exactly the divergence risk H2's real
-- curriculum-version-vs-assessment-version bug (PR #248) came from.
--
-- ROOT_CAUSE_PROBE and CONTRADICTION_CHECK are the genuinely new kind: hand-authored, cross-objective
-- links the curriculum graph does not already assert. This table holds only those two.
--
-- Deliberately foundation-only. This migration adds no selection_policy_version, widens no
-- selection-reason vocabulary, and is not read by DiagnosticService or any selector -- nothing here
-- is consumed at runtime yet. See M2-ADR-024 §4.

CREATE TABLE core.diagnostic_probe_relationship (
  id UUID PRIMARY KEY,
  -- Both ends are learning_objective rows, which are already scoped to a curriculum_version via
  -- skill_version -- so a relationship is implicitly pinned to one curriculum version without
  -- needing its own curriculum_version_id column, the same way core.assessment_item_objective
  -- needs none.
  source_objective_id UUID NOT NULL REFERENCES core.learning_objective(id) ON DELETE RESTRICT,
  target_objective_id UUID NOT NULL REFERENCES core.learning_objective(id) ON DELETE RESTRICT,
  relationship_type VARCHAR(32) NOT NULL,
  -- DRAFT is freely editable for future authoring-tool support (M2-ADR-024 §5's "LLM proposes ->
  -- deterministic validation -> human/content governance -> published" pipeline); PUBLISHED is
  -- immutable, the same discipline core.assessment_version/core.curriculum_version already hold
  -- authored content to. Runtime resolution only ever reads PUBLISHED rows.
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  -- Required, not optional: an unexplained relationship is not auditable. Content governance for a
  -- hand-authored causal link is exactly the kind of decision M2-ADR-024 §5 requires a human or a
  -- deterministic check to be able to review, and a rationale is what makes that review possible.
  rationale TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  published_at TIMESTAMPTZ,
  UNIQUE (source_objective_id, target_objective_id, relationship_type),
  CONSTRAINT ck_diagnostic_probe_relationship_not_self CHECK (source_objective_id <> target_objective_id),
  CONSTRAINT ck_diagnostic_probe_relationship_type CHECK (
    relationship_type IN ('ROOT_CAUSE_PROBE', 'CONTRADICTION_CHECK')
  ),
  CONSTRAINT ck_diagnostic_probe_relationship_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
  CONSTRAINT ck_diagnostic_probe_relationship_rationale CHECK (length(btrim(rationale)) > 0),
  CONSTRAINT ck_diagnostic_probe_relationship_publication_time CHECK (
    (status = 'DRAFT' AND published_at IS NULL)
    OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
  )
);

COMMENT ON TABLE core.diagnostic_probe_relationship IS
  'Hand-authored ROOT_CAUSE_PROBE / CONTRADICTION_CHECK links between two learning objectives, for '
  'the H4b hypothesis-driven-probe foundation (M2-ADR-024). SAME_OBJECTIVE_CONFIRMATION and '
  'PREREQUISITE_VALIDATION are deliberately not stored here -- they are read from '
  'core.assessment_item_objective and core.skill_prerequisite respectively.';

CREATE INDEX idx_diagnostic_probe_relationship_source
  ON core.diagnostic_probe_relationship (source_objective_id, relationship_type, status);

-- Published content is immutable, the same guarantee core.assessment_version's own trigger gives
-- authored items: a relationship a runtime resolution once cited cannot be quietly rewritten under
-- it. Unlike core.assessment_version there is no RETIRED state yet -- nothing depends on retiring a
-- probe relationship today, and inventing that state ahead of a real need would be exactly the kind
-- of premature structure V052's own migration comment already argues against.
CREATE FUNCTION core.protect_published_probe_relationship()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status = 'PUBLISHED' THEN
      RAISE EXCEPTION 'published probe relationship % is immutable', OLD.id USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
  END IF;
  IF TG_OP = 'UPDATE' AND OLD.status = 'PUBLISHED' THEN
    RAISE EXCEPTION 'published probe relationship % is immutable', OLD.id USING ERRCODE = '55000';
  END IF;
  IF NEW.status = 'PUBLISHED' THEN
    NEW.published_at := COALESCE(NEW.published_at, CURRENT_TIMESTAMP);
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_probe_relationship_immutable
BEFORE INSERT OR UPDATE OR DELETE ON core.diagnostic_probe_relationship
FOR EACH ROW EXECUTE FUNCTION core.protect_published_probe_relationship();

-- ---------------------------------------------------------------------------------------------
-- Three published relationships, grounded in the real, already-seeded KAFKA v2 curriculum
-- (curriculum_version '...0004', H3) and its actual assessment content -- none invented to make a
-- test pass. Objective ids and item counts verified directly against a real migrated database
-- before authoring this migration.
-- ---------------------------------------------------------------------------------------------

INSERT INTO core.diagnostic_probe_relationship
  (id, source_objective_id, target_objective_id, relationship_type, status, rationale, published_at)
VALUES
  -- ACKS_DURABILITY_TRADEOFFS (d11, 2 real items: ...0624, ...0625) -> PRODUCER_IDEMPOTENCE
  -- (d12, 1 real item: ...0626, itself about retry/duplicate-detection via idempotence and
  -- sequence numbers). A miss on the durability-tradeoffs objective is worth checking against the
  -- narrower idempotence objective the same skill also covers. Resolves to a real, unseen
  -- candidate -- the CANDIDATES_AVAILABLE case.
  ('01900000-0000-7000-8000-000000000e01',
   '01900000-0000-7000-8000-000000000d11', '01900000-0000-7000-8000-000000000d12',
   'ROOT_CAUSE_PROBE', 'PUBLISHED',
   'A miss on producer-durability tradeoffs (min.insync.replicas interaction) is worth checking '
   'against the narrower idempotent-producer objective on the same skill: both are about what a '
   'producer can and cannot rely on being durable/deduplicated across a retry.',
   CURRENT_TIMESTAMP),

  -- PRODUCER_IDEMPOTENCE (d12, on KAFKA_PRODUCER_ACKS, 1 item) -> IDEMPOTENT_DELIVERY (the
  -- KAFKA_PRODUCER_IDEMPOTENCE skill's own, carried-forward objective -- zero items in the real
  -- bank; that skill has no assessment content at all). Deliberately kept as the
  -- RELATIONSHIP_DEFINED_BUT_NO_ITEMS integration case: a real, published, valid relationship whose
  -- target objective genuinely has nothing to serve, reported as an explicit, distinct outcome
  -- rather than silently falling back to something else.
  ('01900000-0000-7000-8000-000000000e02',
   '01900000-0000-7000-8000-000000000d12', '01900000-0000-7000-8000-000000000c08',
   'ROOT_CAUSE_PROBE', 'PUBLISHED',
   'A miss suggesting a producer-idempotence misunderstanding is worth checking against the '
   'dedicated KAFKA_PRODUCER_IDEMPOTENCE skill''s own objective -- deliberately kept published even '
   'though that skill has no assessment content yet, so probe resolution against real, incomplete '
   'content is exercised rather than only against a hand-picked happy path.',
   CURRENT_TIMESTAMP),

  -- ACKS_DURABILITY_TRADEOFFS (d11) -> ACKS_SEMANTICS (d10, 4 real items). If a learner who missed
  -- the durability-tradeoffs item in fact gets a basic acks-semantics item right, that is evidence
  -- against "does not understand acks at all" and narrows the hypothesis toward the more specific
  -- durability-interaction gap rather than a fundamentals gap.
  ('01900000-0000-7000-8000-000000000e03',
   '01900000-0000-7000-8000-000000000d11', '01900000-0000-7000-8000-000000000d10',
   'CONTRADICTION_CHECK', 'PUBLISHED',
   'A correct answer on basic acks semantics contradicts the hypothesis that a durability-tradeoffs '
   'miss reflects a fundamentals gap, narrowing it toward the more specific min.insync.replicas '
   'interaction instead.',
   CURRENT_TIMESTAMP);
