-- Granular diagnostic confidence (M2-ADR-028): aggregates immutable MISCONCEPTION_EVIDENCE_V1
-- observations (V058) into a deterministic confidence band, reusing -- never modifying -- H5's own
-- frozen DiagnosticConfidenceCalculatorV1/DIAGNOSTIC_CONFIDENCE_V1 (V056). A second, independent
-- confidence stream: core.diagnostic_confidence_observation, DiagnosticConfidenceService, and
-- DiagnosticConfidenceRepository are untouched.
--
-- Aggregation identity is (learner_id, misconception_id) -- a semantic decision, not an artifact of
-- V058's schema: a PUBLISHED misconception is one immutable governed incorrect belief, so evidence
-- from any assessment version that ever mapped an item to it is evidence about the SAME object.
-- Never merged across distinct misconception_ids.
--
-- The persisted row's own identity is (attempt_id, misconception_id) -- "the confidence state of
-- this misconception, as recomputed once by this submission" -- deliberately distinct from the
-- aggregation identity above. Exactly one row is written per misconception actually affected by a
-- submission, computed once after that submission's whole per-response loop finishes (never one row
-- per individual evidence observation, which would manufacture artificial diagnostic history within
-- a single submission).
--
-- Append-only, immutable once written, mirroring core.diagnostic_confidence_observation's own model.
-- A historical snapshot is bound permanently to the evidence set it was computed from; it is never
-- revisited when a later submission adds more evidence for the same misconception. Deliberately NO
-- trigger re-derives a row's persisted counts from a live re-aggregation of
-- core.misconception_evidence_observation -- such a check would only ever validate a row against
-- whatever evidence existed at ITS OWN insert time (never retroactively), so it would protect
-- nothing a correct write-time read did not already guarantee, while inviting the false impression
-- that persisted counts are always "current." Correctness at write time is the application's
-- responsibility (MisconceptionConfidenceService reads the complete evidence set once and derives
-- counts, calculator input, band, and provenance ids all from that same read); the database's role
-- here is to keep the row honest about itself (band consistent with its own counts, identity
-- consistent with its own attempt) and permanently immutable thereafter.
CREATE TABLE core.misconception_confidence_observation (
  id UUID PRIMARY KEY,
  attempt_id UUID NOT NULL REFERENCES core.assessment_attempt(id) ON DELETE RESTRICT,
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  misconception_id UUID NOT NULL REFERENCES core.misconception(id) ON DELETE RESTRICT,
  supporting_count INTEGER NOT NULL,
  contradictory_count INTEGER NOT NULL,
  inconclusive_count INTEGER NOT NULL,
  -- 'INSUFFICIENT_EVIDENCE' / 'LOW' / 'MODERATE' / 'HIGH' -- DiagnosticConfidenceBand's own four
  -- values, reused unchanged.
  band VARCHAR(24) NOT NULL,
  -- Reuses DiagnosticConfidenceCalculatorV1.POLICY_VERSION directly -- this is the SAME calculator
  -- and policy as H5, not a new one, so it carries the same policy identifier.
  policy_version VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- One snapshot per misconception per submission, ever -- see the snapshot-event identity note
  -- above. A resubmission of an already-COMPLETED attempt never re-scores
  -- (DiagnosticSubmissionService's own existing idempotency), so this is never exercised by a
  -- legitimate retry, only by an attempt to write history twice.
  UNIQUE (attempt_id, misconception_id),
  CONSTRAINT ck_misconception_confidence_observation_band CHECK (
    band IN ('INSUFFICIENT_EVIDENCE', 'LOW', 'MODERATE', 'HIGH')
  ),
  CONSTRAINT ck_misconception_confidence_observation_counts_non_negative CHECK (
    supporting_count >= 0 AND contradictory_count >= 0 AND inconclusive_count >= 0
  ),
  CONSTRAINT ck_misconception_confidence_observation_policy_version CHECK (
    policy_version = 'DIAGNOSTIC_CONFIDENCE_V1'
  ),
  -- band is exactly what DiagnosticConfidenceCalculatorV1's frozen rule would produce from the row's
  -- own persisted counts -- the same SQL-mirror-of-the-decision-tree idiom
  -- core.diagnostic_confidence_observation (V056) already established, so an immutable row can never
  -- silently disagree with the frozen policy it claims to have been computed under. This is a static
  -- fact about the row's own values, never a claim about live database state, so it in no way
  -- threatens the historical-snapshot guarantee above.
  CONSTRAINT ck_misconception_confidence_observation_band_matches_counts CHECK (
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

COMMENT ON TABLE core.misconception_confidence_observation IS
  'M2-ADR-028: one immutable, append-only snapshot of accumulated MISCONCEPTION_EVIDENCE_V1 evidence '
  'strength for one (learner, misconception) pair, as of the moment one specific submission '
  '(attempt_id) recomputed it. Never a diagnosis, never a probability, never mastery or progression '
  'authority, and never fed by or fed into H5''s own hypothesis-tuple confidence stream.';

CREATE INDEX idx_misconception_confidence_observation_learner
  ON core.misconception_confidence_observation (learner_id, misconception_id, created_at);

-- Immutable once written, and validates the one consistency fact no FK or CHECK above can express:
-- learner_id must be the learner who actually owns attempt_id's own submission (a lookup through
-- core.assessment_attempt, since this table's own attempt_id is the snapshot-event anchor, not a
-- duplicated learner fact). Never re-derives supporting_count/contradictory_count/inconclusive_count
-- from a live query -- see the migration header for why that would be pointless at best and
-- misleading at worst for a table whose entire purpose is permanent historical correctness.
CREATE FUNCTION core.protect_misconception_confidence_observation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  owning_learner_id UUID;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'misconception confidence observations are immutable' USING ERRCODE = '55000';
  END IF;

  SELECT learner_id INTO owning_learner_id FROM core.assessment_attempt WHERE id = NEW.attempt_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'attempt_id % does not exist', NEW.attempt_id USING ERRCODE = '23503';
  END IF;

  IF owning_learner_id IS DISTINCT FROM NEW.learner_id THEN
    RAISE EXCEPTION
      'learner_id % does not match the learner who owns attempt %''s own submission (%)',
      NEW.learner_id, NEW.attempt_id, owning_learner_id USING ERRCODE = '23514';
  END IF;

  NEW.created_at := CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_misconception_confidence_observation_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.misconception_confidence_observation
FOR EACH ROW EXECUTE FUNCTION core.protect_misconception_confidence_observation();

-- -------------------------------------------------------------------------------------------
-- core.misconception_confidence_observation_evidence: the COMPLETE set of
-- misconception_evidence_observation rows -- SUPPORTING, CONTRADICTORY, and INCONCLUSIVE alike --
-- that contributed to one confidence snapshot, recorded explicitly rather than left to be
-- reconstructed later from whatever evidence merely happens to exist at query time. This is the
-- authoritative snapshot boundary M2-ADR-028 requires: an older snapshot's own provenance set never
-- grows to include evidence a later submission produces.
-- -------------------------------------------------------------------------------------------

CREATE TABLE core.misconception_confidence_observation_evidence (
  confidence_observation_id UUID NOT NULL
    REFERENCES core.misconception_confidence_observation(id) ON DELETE RESTRICT,
  evidence_observation_id UUID NOT NULL
    REFERENCES core.misconception_evidence_observation(id) ON DELETE RESTRICT,
  PRIMARY KEY (confidence_observation_id, evidence_observation_id)
);

COMMENT ON TABLE core.misconception_confidence_observation_evidence IS
  'The complete set of misconception_evidence_observation rows (SUPPORTING, CONTRADICTORY, and '
  'INCONCLUSIVE alike) that contributed to one misconception_confidence_observation snapshot -- an '
  'exact, immutable, permanent record of that snapshot''s own evidentiary boundary.';

CREATE INDEX idx_misconception_confidence_observation_evidence_evidence
  ON core.misconception_confidence_observation_evidence (evidence_observation_id);

-- Immutable once written, and validates the one consistency fact no FK above can express: the cited
-- evidence_observation_id must belong to the SAME (learner_id, misconception_id) pair as its parent
-- confidence_observation_id -- a snapshot may never cite evidence about a different learner or a
-- different misconception than the one it claims to summarize.
CREATE FUNCTION core.protect_misconception_confidence_observation_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  confidence_row core.misconception_confidence_observation%ROWTYPE;
  evidence_row core.misconception_evidence_observation%ROWTYPE;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'misconception confidence provenance rows are immutable' USING ERRCODE = '55000';
  END IF;

  SELECT * INTO confidence_row
    FROM core.misconception_confidence_observation WHERE id = NEW.confidence_observation_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'confidence_observation_id % does not exist', NEW.confidence_observation_id
      USING ERRCODE = '23503';
  END IF;

  SELECT * INTO evidence_row
    FROM core.misconception_evidence_observation WHERE id = NEW.evidence_observation_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'evidence_observation_id % does not exist', NEW.evidence_observation_id
      USING ERRCODE = '23503';
  END IF;

  IF evidence_row.learner_id IS DISTINCT FROM confidence_row.learner_id
      OR evidence_row.misconception_id IS DISTINCT FROM confidence_row.misconception_id THEN
    RAISE EXCEPTION
      'evidence_observation % (learner %, misconception %) does not belong to '
      'confidence_observation %''s own learner %/misconception %',
      NEW.evidence_observation_id, evidence_row.learner_id, evidence_row.misconception_id,
      NEW.confidence_observation_id, confidence_row.learner_id, confidence_row.misconception_id
      USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_misconception_confidence_observation_evidence_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.misconception_confidence_observation_evidence
FOR EACH ROW EXECUTE FUNCTION core.protect_misconception_confidence_observation_evidence();
