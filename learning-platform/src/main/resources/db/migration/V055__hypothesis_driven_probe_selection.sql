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
  CONSTRAINT ck_diagnostic_probe_provenance_type CHECK (
    relationship_type IN (
      'SAME_OBJECTIVE_CONFIRMATION', 'PREREQUISITE_VALIDATION', 'ROOT_CAUSE_PROBE', 'CONTRADICTION_CHECK'
    )
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
CREATE FUNCTION core.protect_probe_provenance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  attempt_status VARCHAR(16);
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'probe provenance is immutable' USING ERRCODE = '55000';
  END IF;
  SELECT status INTO attempt_status FROM core.assessment_attempt WHERE id = NEW.attempt_id;
  IF attempt_status IS DISTINCT FROM 'IN_PROGRESS' THEN
    RAISE EXCEPTION 'probe provenance may only be recorded for an in-progress attempt'
      USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_probe_provenance_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.diagnostic_probe_provenance
FOR EACH ROW EXECUTE FUNCTION core.protect_probe_provenance();
