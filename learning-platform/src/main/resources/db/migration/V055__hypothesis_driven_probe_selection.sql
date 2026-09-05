-- DIAGNOSTIC_SELECTION_V5: runtime consumption of the H4b probe-relationship foundation (V054,
-- M2-ADR-024), governed by M2-ADR-025.
--
-- V054 stayed foundation-only: ProbeRelationshipService could resolve a hypothesis and a candidate
-- probe, but nothing called it, and nothing recorded that a probe had ever been served. This
-- migration adds exactly the two additive things V5's runtime selector needs: one more admitted
-- selection_reason, and a new table recording *why* a probe entered a packet -- which prior attempt,
-- which source item/objective, which relationship type, which target objective, and (for the two
-- hand-authored relationship types) which published core.diagnostic_probe_relationship row
-- authorized it.
--
-- core.diagnostic_probe_relationship itself is untouched -- migrations are immutable, and V5 reads
-- that table exactly as V054 left it.

-- Pure superset of V053's eleven values, the same DROP+ADD pattern V047/V050/V051/V053 already used
-- to widen this same membership check.
ALTER TABLE core.assessment_attempt_item DROP CONSTRAINT ck_assessment_attempt_item_reason;
ALTER TABLE core.assessment_attempt_item ADD CONSTRAINT ck_assessment_attempt_item_reason CHECK (
  selection_reason IN (
    'SKILL_COVERAGE', 'DIFFICULTY_COVERAGE', 'FILL',
    'UNSEEN_ITEM', 'LOW_CONFIDENCE', 'WEAK_SKILL', 'OBJECTIVE_COVERAGE_GAP',
    'DIFFICULTY_PROGRESSION', 'MASTERY_CONFIRMATION',
    'PREREQUISITE_NOT_SECURED',
    'HYPOTHESIS_CONFIRMATION',
    'HYPOTHESIS_DRIVEN_PROBE'
  )
);

-- One row per probe actually placed in a packet -- bounded in practice to at most one per attempt by
-- MAX_HYPOTHESIS_PROBES_PER_PACKET (M2-ADR-025 §6), though nothing here enforces that count itself;
-- the selector's own pool restriction is what makes it true, this table only records the outcome.
--
-- (attempt_id, item_version_id) references the *new* attempt's own assessment_attempt_item row by
-- its existing UNIQUE (attempt_id, item_version_id) key (V045) rather than a surrogate id threaded
-- back from insertion -- AssessmentRepository.insertSelectedItems needed no signature change for
-- this migration to be usable.
--
-- relationship_type here admits all four ProbeRelationshipType values, deliberately a different
-- vocabulary from core.diagnostic_probe_relationship's own two-value CHECK: that table only ever
-- stores the two hand-authored types, but V5 can trigger a probe under any of the four semantics
-- #251 defined, including the two that are read from existing curriculum tables rather than stored.
--
-- This is authoritative audit provenance, not a log line, so its internal consistency is enforced
-- at the database boundary rather than trusted from the application alone -- the same discipline
-- core.assessment_response already holds itself to (core.protect_assessment_response verifies a
-- response names an item this attempt actually selected, not just any item that exists). Four
-- consistency facts, each checked by whichever mechanism actually expresses it:
--
--  1. source_item_version_id was really presented in source_attempt_id: a composite FK against
--     core.assessment_attempt_item's own (attempt_id, item_version_id) unique key (V045) --
--     the same key the row's own (attempt_id, item_version_id) FK below already uses for the new
--     attempt, applied here to the source one instead.
--  2/3. source_objective_id really tags source_item_version_id, and target_objective_id really
--     tags the selected item_version_id: two composite FKs against
--     core.assessment_item_objective's own (item_version_id, objective_id) primary key (V046).
--  4/5. For ROOT_CAUSE_PROBE/CONTRADICTION_CHECK, authorizing_relationship_id must be set, and for
--     SAME_OBJECTIVE_CONFIRMATION/PREREQUISITE_VALIDATION it must be null -- one CHECK, since
--     "required for these two" and "forbidden for the other two" are the same boolean condition
--     stated once. Whether a *present* id actually authorizes this exact row -- exists, is
--     PUBLISHED, and its own source/target/type match -- cannot be a plain FK (there is no column
--     to compare a literal 'PUBLISHED' against), so trg_probe_provenance_guard below does that
--     lookup explicitly, the same way core.protect_assessment_response already does an EXISTS
--     check no FK could express either.
CREATE TABLE core.diagnostic_probe_provenance (
  id UUID PRIMARY KEY,
  attempt_id UUID NOT NULL,
  item_version_id UUID NOT NULL,
  source_attempt_id UUID NOT NULL REFERENCES core.assessment_attempt(id) ON DELETE RESTRICT,
  source_item_version_id UUID NOT NULL REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT,
  source_objective_id UUID NOT NULL REFERENCES core.learning_objective(id) ON DELETE RESTRICT,
  relationship_type VARCHAR(32) NOT NULL,
  target_objective_id UUID NOT NULL REFERENCES core.learning_objective(id) ON DELETE RESTRICT,
  -- NULL for SAME_OBJECTIVE_CONFIRMATION/PREREQUISITE_VALIDATION, which are authorized by
  -- assessment_item_objective/skill_prerequisite directly, not by a diagnostic_probe_relationship row.
  authorizing_relationship_id UUID REFERENCES core.diagnostic_probe_relationship(id) ON DELETE RESTRICT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (attempt_id, item_version_id),
  FOREIGN KEY (attempt_id, item_version_id)
    REFERENCES core.assessment_attempt_item(attempt_id, item_version_id) ON DELETE RESTRICT,
  -- Fact 1: the source item genuinely belonged to the source attempt's own selected form.
  FOREIGN KEY (source_attempt_id, source_item_version_id)
    REFERENCES core.assessment_attempt_item(attempt_id, item_version_id) ON DELETE RESTRICT,
  -- Fact 2: the source objective genuinely tags the source item.
  FOREIGN KEY (source_item_version_id, source_objective_id)
    REFERENCES core.assessment_item_objective(item_version_id, objective_id) ON DELETE RESTRICT,
  -- Fact 3: the target objective genuinely tags the selected (probe) item.
  FOREIGN KEY (item_version_id, target_objective_id)
    REFERENCES core.assessment_item_objective(item_version_id, objective_id) ON DELETE RESTRICT,
  CONSTRAINT ck_diagnostic_probe_provenance_type CHECK (
    relationship_type IN (
      'SAME_OBJECTIVE_CONFIRMATION', 'PREREQUISITE_VALIDATION', 'ROOT_CAUSE_PROBE', 'CONTRADICTION_CHECK'
    )
  ),
  -- Facts 4/5: required for the two hand-authored types, forbidden for the two graph-derived ones --
  -- one iff, not two separate rules that could individually be relaxed.
  CONSTRAINT ck_diagnostic_probe_provenance_authorization CHECK (
    (relationship_type IN ('ROOT_CAUSE_PROBE', 'CONTRADICTION_CHECK'))
    = (authorizing_relationship_id IS NOT NULL)
  )
);

COMMENT ON TABLE core.diagnostic_probe_provenance IS
  'Why one attempt_item was selected under DIAGNOSTIC_SELECTION_V5: the prior attempt/item/objective '
  'whose miss raised the hypothesis, the relationship type and target objective #251 resolved, and '
  '(where applicable) the published relationship row that authorized it. Records the reason a probe '
  'was selected -- never a diagnosis, and never mastery or evidence state.';

CREATE INDEX idx_diagnostic_probe_provenance_source_attempt
  ON core.diagnostic_probe_provenance (source_attempt_id);

-- Immutable once written, the same guarantee core.assessment_attempt_item's own trigger gives the
-- packet it explains, and insertable only alongside it -- while the owning attempt is IN_PROGRESS.
-- Also validates the one consistency fact no FK or CHECK above can express: when
-- authorizing_relationship_id is present, it must actually authorize this exact row -- exist, be
-- PUBLISHED, and have the same source_objective_id/target_objective_id/relationship_type this row
-- itself claims. A row citing a DRAFT, retracted, or mismatched relationship is exactly the
-- inconsistent audit state this whole migration exists to make unrepresentable.
CREATE FUNCTION core.protect_probe_provenance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  attempt_status VARCHAR(16);
  authorizing_row core.diagnostic_probe_relationship%ROWTYPE;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'probe provenance is immutable' USING ERRCODE = '55000';
  END IF;
  SELECT status INTO attempt_status FROM core.assessment_attempt WHERE id = NEW.attempt_id;
  IF attempt_status IS DISTINCT FROM 'IN_PROGRESS' THEN
    RAISE EXCEPTION 'probe provenance may only be recorded for an in-progress attempt'
      USING ERRCODE = '55000';
  END IF;

  IF NEW.authorizing_relationship_id IS NOT NULL THEN
    SELECT * INTO authorizing_row
      FROM core.diagnostic_probe_relationship
     WHERE id = NEW.authorizing_relationship_id;
    IF NOT FOUND
        OR authorizing_row.status <> 'PUBLISHED'
        OR authorizing_row.source_objective_id <> NEW.source_objective_id
        OR authorizing_row.target_objective_id <> NEW.target_objective_id
        OR authorizing_row.relationship_type <> NEW.relationship_type THEN
      RAISE EXCEPTION
        'authorizing_relationship_id % does not authorize this provenance row -- it must exist, be '
        'PUBLISHED, and its source_objective_id/target_objective_id/relationship_type must exactly '
        'match', NEW.authorizing_relationship_id
        USING ERRCODE = '23514';
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_probe_provenance_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.diagnostic_probe_provenance
FOR EACH ROW EXECUTE FUNCTION core.protect_probe_provenance();
