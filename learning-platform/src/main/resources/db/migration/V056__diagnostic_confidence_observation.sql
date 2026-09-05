-- DIAGNOSTIC_CONFIDENCE_V1: the diagnostic-confidence construct M2-ADR-023 SS2 already required H5
-- to be (a named, versioned calculator with a frozen behaviour vector, deterministic from
-- already-authoritative inputs, never fed back into mastery computation). This migration adds its
-- persistence, consuming -- never modifying -- H4b's evidence model
-- (HypothesisEvidence/HypothesisEvidenceOutcome, M2-ADR-024) and V5's provenance model
-- (core.diagnostic_probe_provenance, M2-ADR-025/V055). Neither is altered here: V055 already
-- merged, and adding a new unique key to it now would be an added CHECK-or-UNIQUE the previous
-- release's image could no longer roll back against (scripts/ci/check-migration-compatibility.py's
-- own rule) even though no row would ever actually violate it -- so every fact this migration needs
-- from that table is read through a lookup, not asserted via a composite foreign key that would
-- require widening it.
--
-- One append-only row per explicit diagnostic evidence event: a probe response being scored inside
-- DiagnosticSubmissionService.score()'s existing transaction. At that moment, every distinct
-- evidence observation gathered so far for the hypothesis tuple the response's own provenance row
-- names (learner + source_objective_id + target_objective_id + relationship_type) is recomputed by
-- DiagnosticConfidenceCalculatorV1, and one new immutable observation is appended -- never a
-- snapshot written on mere read, never a mutation of a prior observation.
--
-- triggering_provenance_id is a foreign key to the exact core.diagnostic_probe_provenance row this
-- observation was computed from, not a duplicated copy of its fields -- the same "reference existing
-- provenance, do not re-store facts" discipline V055 already held to for core.assessment_item_objective
-- and core.assessment_attempt_item. UNIQUE on it: a probe response can trigger at most one
-- observation, ever (this table's own immutability, below, is what makes a resubmission produce no
-- second row rather than a duplicate).
--
-- This is authoritative audit provenance, not a log line, so an observation's own hypothesis
-- identity (learner_id/source_objective_id/target_objective_id/relationship_type) must not be
-- representable as inconsistent with the triggering_provenance_id row it claims to have been
-- computed from -- the same discipline V055 already holds core.diagnostic_probe_provenance itself
-- to. All four facts are checked by one guard-trigger lookup below (one SELECT joining the
-- provenance row and its owning attempt, four comparisons against NEW) rather than three composite
-- FKs plus a trigger for the fourth: unlike V055's own hardening round, none of these four facts can
-- be expressed as a plain FK without first adding a new unique key to the already-merged
-- provenance table, which the note above rules out uniformly rather than case by case.
CREATE TABLE core.diagnostic_confidence_observation (
  id UUID PRIMARY KEY,
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  source_objective_id UUID NOT NULL REFERENCES core.learning_objective(id) ON DELETE RESTRICT,
  target_objective_id UUID NOT NULL REFERENCES core.learning_objective(id) ON DELETE RESTRICT,
  -- Same four-value vocabulary as core.diagnostic_probe_provenance.relationship_type (V055) -- this
  -- is the same hypothesis-tuple identity, not a new one.
  relationship_type VARCHAR(32) NOT NULL,
  triggering_provenance_id UUID NOT NULL UNIQUE
    REFERENCES core.diagnostic_probe_provenance(id) ON DELETE RESTRICT,
  supporting_count INTEGER NOT NULL,
  contradictory_count INTEGER NOT NULL,
  inconclusive_count INTEGER NOT NULL,
  -- 'INSUFFICIENT_EVIDENCE' / 'LOW' / 'MODERATE' / 'HIGH' -- DiagnosticConfidenceBand's own four
  -- values, never a fifth, never a decimal score alongside it (band-only V1).
  band VARCHAR(24) NOT NULL,
  policy_version VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_diagnostic_confidence_observation_type CHECK (
    relationship_type IN (
      'SAME_OBJECTIVE_CONFIRMATION', 'PREREQUISITE_VALIDATION', 'ROOT_CAUSE_PROBE', 'CONTRADICTION_CHECK'
    )
  ),
  CONSTRAINT ck_diagnostic_confidence_observation_band CHECK (
    band IN ('INSUFFICIENT_EVIDENCE', 'LOW', 'MODERATE', 'HIGH')
  ),
  CONSTRAINT ck_diagnostic_confidence_observation_counts_non_negative CHECK (
    supporting_count >= 0 AND contradictory_count >= 0 AND inconclusive_count >= 0
  ),
  CONSTRAINT ck_diagnostic_confidence_observation_policy_version CHECK (
    policy_version = 'DIAGNOSTIC_CONFIDENCE_V1'
  ),
  -- band is exactly what DiagnosticConfidenceCalculatorV1's frozen DIAGNOSTIC_CONFIDENCE_V1 rule
  -- would produce from the row's own persisted counts -- a direct SQL mirror of that calculator's
  -- decision tree, evaluated in the same first-match order, so an authoritative, immutable row can
  -- never silently contradict the frozen policy it claims to have been computed under. Because
  -- DIAGNOSTIC_CONFIDENCE_V1 is frozen forever (EngineVersionFreezeTests), this CHECK and the Java
  -- calculator must always agree; a future policy version is a new, differently-named
  -- band-consistency rule, never an edit to this one.
  CONSTRAINT ck_diagnostic_confidence_observation_band_matches_counts CHECK (
    band = (
      CASE
        WHEN supporting_count = 0 AND contradictory_count = 0 THEN 'INSUFFICIENT_EVIDENCE'
        WHEN contradictory_count = 0 AND supporting_count = 1 THEN 'LOW'
        WHEN contradictory_count = 0 AND supporting_count = 2 THEN 'MODERATE'
        WHEN contradictory_count = 0 AND supporting_count >= 3 THEN 'HIGH'
        WHEN contradictory_count >= 1 AND supporting_count > 3 * contradictory_count THEN 'HIGH'
        WHEN contradictory_count >= 1 AND supporting_count - contradictory_count >= 3 THEN 'MODERATE'
        ELSE 'LOW'
      END
    )
  )
);

COMMENT ON TABLE core.diagnostic_confidence_observation IS
  'DIAGNOSTIC_CONFIDENCE_V1: one immutable, append-only record of how strongly distinct evidence '
  'observations support one hypothesis tuple (learner + source objective + target objective + '
  'relationship type), as of the moment triggering_provenance_id''s probe response was scored. Never '
  'a diagnosis, never a probability, never fed by or fed into mastery computation.';

CREATE INDEX idx_diagnostic_confidence_observation_hypothesis
  ON core.diagnostic_confidence_observation
  (learner_id, source_objective_id, target_objective_id, relationship_type);

-- Immutable once written (the same guarantee core.diagnostic_probe_provenance's own trigger gives
-- the packet it explains), and validates the four consistency facts no FK or CHECK above can
-- express without widening the already-merged provenance table: learner_id must be the learner who
-- actually owns the triggering provenance row's attempt (a lookup through core.assessment_attempt,
-- since diagnostic_probe_provenance itself carries no learner_id column), and
-- source_objective_id/target_objective_id/relationship_type must exactly match that same row's own
-- values. A row claiming a different hypothesis identity than the probe response it was actually
-- computed from is exactly the inconsistent audit state this table exists to make unrepresentable.
-- A resubmission of an already-COMPLETED attempt writes nothing (DiagnosticSubmissionService's
-- existing idempotency guarantee), so the immutability half of this trigger is never exercised by a
-- legitimate retry -- only by an attempt to revise history, which this table exists to make
-- impossible rather than merely discouraged.
CREATE FUNCTION core.protect_diagnostic_confidence_observation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  provenance_row core.diagnostic_probe_provenance%ROWTYPE;
  owning_learner_id UUID;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'diagnostic confidence observations are immutable' USING ERRCODE = '55000';
  END IF;

  SELECT * INTO provenance_row
    FROM core.diagnostic_probe_provenance
   WHERE id = NEW.triggering_provenance_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'triggering_provenance_id % does not reference an existing diagnostic_probe_provenance row',
      NEW.triggering_provenance_id USING ERRCODE = '23514';
  END IF;

  SELECT a.learner_id INTO owning_learner_id
    FROM core.assessment_attempt a WHERE a.id = provenance_row.attempt_id;

  IF owning_learner_id IS DISTINCT FROM NEW.learner_id
      OR provenance_row.source_objective_id IS DISTINCT FROM NEW.source_objective_id
      OR provenance_row.target_objective_id IS DISTINCT FROM NEW.target_objective_id
      OR provenance_row.relationship_type IS DISTINCT FROM NEW.relationship_type THEN
    RAISE EXCEPTION
      'diagnostic_confidence_observation hypothesis identity does not match its '
      'triggering_provenance_id % -- learner_id/source_objective_id/target_objective_id/'
      'relationship_type must exactly match that row (and, for learner_id, the learner who owns its '
      'attempt)', NEW.triggering_provenance_id
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_diagnostic_confidence_observation_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.diagnostic_confidence_observation
FOR EACH ROW EXECUTE FUNCTION core.protect_diagnostic_confidence_observation();
