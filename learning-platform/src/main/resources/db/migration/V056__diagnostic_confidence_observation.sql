-- DIAGNOSTIC_CONFIDENCE_V1 (M2-ADR-026): the first consumer of H4b's evidence model
-- (HypothesisEvidence/HypothesisEvidenceOutcome, M2-ADR-024) and of V5's provenance model
-- (core.diagnostic_probe_provenance, M2-ADR-025/V055). Neither is touched by this migration --
-- migrations are immutable, and H5 reads both exactly as they already stand.
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
  -- values, never a fifth, never a decimal score alongside it (M2-ADR-026: band-only V1).
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
  )
);

COMMENT ON TABLE core.diagnostic_confidence_observation IS
  'DIAGNOSTIC_CONFIDENCE_V1 (M2-ADR-026): one immutable, append-only record of how strongly distinct '
  'evidence observations support one hypothesis tuple (learner + source objective + target objective '
  '+ relationship type), as of the moment triggering_provenance_id''s probe response was scored. Never '
  'a diagnosis, never a probability, never fed by or fed into mastery computation.';

CREATE INDEX idx_diagnostic_confidence_observation_hypothesis
  ON core.diagnostic_confidence_observation
  (learner_id, source_objective_id, target_objective_id, relationship_type);

-- Append-only: no UPDATE or DELETE, ever. A resubmission of an already-COMPLETED attempt writes
-- nothing (DiagnosticSubmissionService's existing idempotency guarantee), so this trigger is never
-- exercised by a legitimate retry -- only by an attempt to revise history, which this table exists
-- to make impossible rather than merely discouraged.
CREATE FUNCTION core.protect_diagnostic_confidence_observation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'diagnostic confidence observations are immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_diagnostic_confidence_observation_guard
BEFORE UPDATE OR DELETE ON core.diagnostic_confidence_observation
FOR EACH ROW EXECUTE FUNCTION core.protect_diagnostic_confidence_observation();
