-- Evidence confidence. A mastery snapshot now also records the deterministic
-- EvidenceConfidenceCalculator output, the confidence threshold in force, and the
-- confidence algorithm version, so the progression-gating inputs are reproducible
-- alongside the mastery score. Columns are nullable additive: existing snapshots
-- (if any) remain valid, and the append-only trigger is untouched (this is DDL,
-- not a row mutation).

ALTER TABLE ledger.mastery_snapshot
  ADD COLUMN evidence_confidence NUMERIC(5, 4),
  ADD COLUMN confidence_threshold NUMERIC(5, 4),
  ADD COLUMN confidence_algorithm_version VARCHAR(32);

ALTER TABLE ledger.mastery_snapshot
  ADD CONSTRAINT ck_mastery_snapshot_confidence
    CHECK (evidence_confidence IS NULL OR (evidence_confidence >= 0 AND evidence_confidence <= 1));

ALTER TABLE ledger.mastery_snapshot
  ADD CONSTRAINT ck_mastery_snapshot_confidence_threshold
    CHECK (confidence_threshold IS NULL OR (confidence_threshold >= 0 AND confidence_threshold <= 1));

COMMENT ON COLUMN ledger.mastery_snapshot.evidence_confidence IS
  'EvidenceConfidenceCalculator blend: 0.40 volume + 0.35 objective coverage + 0.15 recency + 0.10 consistency';
