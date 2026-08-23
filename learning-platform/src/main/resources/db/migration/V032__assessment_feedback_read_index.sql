-- M2-T13: support the learner-owned latest-feedback join without scanning the decision ledger.
-- Additive index only; the previous application image remains fully compatible with schema N+1.
CREATE INDEX idx_assessment_evaluation_context_decided
  ON ledger.assessment_evaluation_decision (context_id, decided_at DESC, id DESC);

COMMENT ON INDEX ledger.idx_assessment_evaluation_context_decided IS
  'M2-T13 bounded learner feedback read via owned grounding context and latest decision';
