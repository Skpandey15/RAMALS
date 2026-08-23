-- M2-T12: immutable, replay-safe acceptance boundary for AI-assisted rubric evaluation.
--
-- The row stores normalized rubric results and learner-safe feedback, not prompts, hidden reasoning,
-- answer text or provider secrets. A successful ASSESSMENT ai_execution and the exact grounded
-- context are mandatory parents. Rejected/manual-review rows are decisions, but only ACCEPTED is an
-- authorization signal for a later authoritative evidence workflow.

CREATE TABLE ledger.assessment_evaluation_decision (
  id UUID PRIMARY KEY,
  proposal_id VARCHAR(64) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  agent_run_id VARCHAR(64) NOT NULL,
  ai_execution_id UUID NOT NULL REFERENCES core.ai_execution(id) ON DELETE RESTRICT,
  context_id VARCHAR(64) NOT NULL
    REFERENCES ledger.grounding_retrieval_record(context_id) ON DELETE RESTRICT,
  answer_evidence_id VARCHAR(64) NOT NULL,
  answer_version VARCHAR(64) NOT NULL,
  rubric_version VARCHAR(64) NOT NULL,
  outcome VARCHAR(24) NOT NULL,
  reason_codes JSONB NOT NULL,
  referenced_evidence_ids JSONB NOT NULL,
  dimension_results JSONB NOT NULL,
  feedback TEXT,
  confidence NUMERIC(9, 8),
  deterministic_check VARCHAR(24) NOT NULL,
  deterministic_reason_code VARCHAR(64),
  parser_reason_code VARCHAR(64),
  policy_version VARCHAR(64) NOT NULL,
  decision_digest CHAR(64) NOT NULL,
  interaction_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64),
  decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_assessment_evaluation_request UNIQUE (request_id),
  CONSTRAINT uq_assessment_evaluation_proposal_policy UNIQUE (proposal_id, policy_version),
  CONSTRAINT ck_assessment_evaluation_outcome
    CHECK (outcome IN ('ACCEPTED', 'REJECTED', 'MANUAL_REVIEW')),
  CONSTRAINT ck_assessment_evaluation_reasons
    CHECK (jsonb_typeof(reason_codes) = 'array' AND jsonb_array_length(reason_codes) > 0),
  CONSTRAINT ck_assessment_evaluation_evidence
    CHECK (jsonb_typeof(referenced_evidence_ids) = 'array'),
  CONSTRAINT ck_assessment_evaluation_dimensions
    CHECK (jsonb_typeof(dimension_results) = 'array' AND jsonb_array_length(dimension_results) <= 32),
  CONSTRAINT ck_assessment_evaluation_feedback
    CHECK (feedback IS NULL OR length(btrim(feedback)) BETWEEN 1 AND 4000),
  CONSTRAINT ck_assessment_evaluation_confidence
    CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
  CONSTRAINT ck_assessment_evaluation_deterministic_check
    CHECK (deterministic_check IN ('NOT_APPLICABLE', 'AGREES', 'CONFLICTS')),
  CONSTRAINT ck_assessment_evaluation_conflict_reason
    CHECK (deterministic_check <> 'CONFLICTS' OR deterministic_reason_code IS NOT NULL),
  CONSTRAINT ck_assessment_evaluation_parsed_result
    CHECK (
      outcome = 'REJECTED'
      OR (jsonb_array_length(dimension_results) > 0
          AND feedback IS NOT NULL
          AND confidence IS NOT NULL
          AND parser_reason_code IS NULL)),
  CONSTRAINT ck_assessment_evaluation_identifiers CHECK (
    length(btrim(proposal_id)) BETWEEN 1 AND 64
    AND length(btrim(request_id)) BETWEEN 1 AND 64
    AND length(btrim(agent_run_id)) BETWEEN 1 AND 64
    AND length(btrim(answer_evidence_id)) BETWEEN 1 AND 64
    AND length(btrim(answer_version)) BETWEEN 1 AND 64
    AND length(btrim(rubric_version)) BETWEEN 1 AND 64
    AND length(btrim(interaction_id)) BETWEEN 1 AND 64
    AND (trace_id IS NULL OR length(btrim(trace_id)) BETWEEN 1 AND 64)),
  CONSTRAINT ck_assessment_evaluation_codes CHECK (
    (deterministic_reason_code IS NULL
      OR deterministic_reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$')
    AND (parser_reason_code IS NULL OR parser_reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$')),
  CONSTRAINT ck_assessment_evaluation_digest CHECK (decision_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_assessment_evaluation_answer
  ON ledger.assessment_evaluation_decision (answer_evidence_id, answer_version, decided_at DESC);

CREATE INDEX idx_assessment_evaluation_execution
  ON ledger.assessment_evaluation_decision (ai_execution_id);

CREATE INDEX idx_assessment_evaluation_review_queue
  ON ledger.assessment_evaluation_decision (decided_at, id)
  WHERE outcome = 'MANUAL_REVIEW';

CREATE TRIGGER trg_assessment_evaluation_decision_append_only
BEFORE UPDATE OR DELETE ON ledger.assessment_evaluation_decision
FOR EACH ROW EXECUTE FUNCTION ledger.reject_grounding_audit_mutation();

COMMENT ON TABLE ledger.assessment_evaluation_decision IS
  'M2-T12 immutable rubric gate decision; only ACCEPTED permits a downstream authoritative effect';
COMMENT ON COLUMN ledger.assessment_evaluation_decision.ai_execution_id IS
  'Successful ASSESSMENT model execution that produced the non-authoritative proposal';
COMMENT ON COLUMN ledger.assessment_evaluation_decision.answer_evidence_id IS
  'Exact grounded learner-answer fact evaluated by this decision; answer text is not retained here';
COMMENT ON COLUMN ledger.assessment_evaluation_decision.dimension_results IS
  'Bounded normalized rubric results with evidence identifiers; never hidden chain-of-thought';
COMMENT ON COLUMN ledger.assessment_evaluation_decision.decision_digest IS
  'SHA-256 of semantic decision content; excludes mutable interaction and trace correlation';
COMMENT ON COLUMN ledger.assessment_evaluation_decision.trace_id IS
  'Actual distributed trace identifier when tracing is available; NULL rather than fabricated';
