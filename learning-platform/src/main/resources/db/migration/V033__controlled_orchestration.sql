-- M2-T14: controlled multi-agent orchestration.
--
-- The workflow spine is deterministic and Spring-owned. LangGraph stays inside the AI plane and
-- governs one agent's execution flow only; every authoritative milestone of the composition is a
-- row here, so a workflow can be observed, retried and attributed without reading an agent
-- checkpoint. Nothing in these tables is written by an agent.
--
-- Bounded composition is enforced structurally rather than by convention: a run holds at most one
-- row per step (uq_learning_workflow_step_name), so a repeated or malformed agent result advances
-- an existing step instead of appending a new one. That is what makes "no unbounded agent loop" a
-- schema property and not a code review promise.

-- Evaluation of a free-text answer is an observation about a skill, like a diagnostic or a quiz.
-- It is a distinct type because its provenance is an AI proposal that a deterministic gate
-- accepted, and evidence provenance is exactly what an auditor needs to separate.
ALTER TABLE ledger.evidence DROP CONSTRAINT ck_evidence_type;
ALTER TABLE ledger.evidence ADD CONSTRAINT ck_evidence_type
  CHECK (evidence_type IN ('DIAGNOSTIC', 'QUIZ', 'PRACTICE', 'SCENARIO', 'ADJUSTMENT', 'EVALUATION'));

-- These two tables live in core, not ledger, and that placement is deliberate. The ledger schema is
-- the immutable record: the runtime role holds SELECT and INSERT there and nothing else, which is
-- what makes an audit row unrewritable. A workflow run is the opposite kind of object -- a state
-- machine whose whole job is to advance -- so it belongs beside core.agent_work_outbox, which is
-- the same shape for the same reason. Putting it in ledger would have required granting the runtime
-- UPDATE on an immutable schema to make claiming work, which is the wrong trade entirely.

CREATE TABLE core.learning_workflow_run (
  id UUID PRIMARY KEY,
  workflow_type VARCHAR(32) NOT NULL,
  policy_version VARCHAR(32) NOT NULL,
  trigger_key VARCHAR(128) NOT NULL,
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  skill_id UUID NOT NULL REFERENCES core.skill(id) ON DELETE RESTRICT,
  curriculum_version_id UUID NOT NULL REFERENCES core.curriculum_version(id) ON DELETE RESTRICT,
  attempt_id UUID NOT NULL REFERENCES core.assessment_attempt(id) ON DELETE RESTRICT,
  assessment_version_id UUID NOT NULL REFERENCES core.assessment_version(id) ON DELETE RESTRICT,
  -- Carried on the run, not re-read from the gate decision. A step must be resumable after a crash
  -- from this row alone; re-deriving a score at retry time is how a resumed run quietly disagrees
  -- with the evidence its first attempt already wrote.
  normalized_score NUMERIC(5, 4) NOT NULL,
  evaluation_request_id VARCHAR(64) NOT NULL
    REFERENCES ledger.assessment_evaluation_decision(request_id) ON DELETE RESTRICT,
  status VARCHAR(24) NOT NULL,
  current_step VARCHAR(32),
  terminal_reason VARCHAR(64),
  interaction_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64),
  deadline_at TIMESTAMPTZ NOT NULL,
  started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMPTZ,
  -- One workflow per trigger. A duplicate trigger collapses onto the existing run instead of
  -- starting a second chain of agent calls (G05).
  CONSTRAINT uq_learning_workflow_trigger UNIQUE (trigger_key),
  -- One workflow per gated evaluation, so a replayed evaluation cannot fan out.
  CONSTRAINT uq_learning_workflow_evaluation UNIQUE (evaluation_request_id),
  CONSTRAINT ck_learning_workflow_type CHECK (workflow_type IN ('EVALUATION_TO_ADAPTATION')),
  CONSTRAINT ck_learning_workflow_status CHECK (
    status IN ('RUNNING', 'COMPLETED', 'STOPPED', 'CANCELLED', 'TIMED_OUT', 'FAILED')),
  CONSTRAINT ck_learning_workflow_step CHECK (
    current_step IS NULL
    OR current_step IN ('RECORD_EVALUATION_EVIDENCE', 'RECOMPUTE_MASTERY', 'DIAGNOSE', 'ADAPT')),
  -- Every terminal state must say why it ended, including the deterministic non-terminal stops.
  -- A workflow that simply stopped being mentioned is not an auditable outcome.
  CONSTRAINT ck_learning_workflow_terminal CHECK (
    (status = 'RUNNING' AND terminal_reason IS NULL AND completed_at IS NULL)
    OR (status <> 'RUNNING' AND terminal_reason IS NOT NULL AND completed_at IS NOT NULL)),
  CONSTRAINT ck_learning_workflow_identity CHECK (
    length(btrim(trigger_key)) BETWEEN 1 AND 128
    AND length(btrim(interaction_id)) BETWEEN 1 AND 64
    AND length(btrim(evaluation_request_id)) BETWEEN 1 AND 64
    AND (trace_id IS NULL OR length(btrim(trace_id)) BETWEEN 1 AND 64)),
  CONSTRAINT ck_learning_workflow_score CHECK (normalized_score >= 0 AND normalized_score <= 1),
  CONSTRAINT ck_learning_workflow_reason CHECK (
    terminal_reason IS NULL OR terminal_reason ~ '^[A-Z][A-Z0-9_]{0,63}$')
);

CREATE INDEX idx_learning_workflow_learner
  ON core.learning_workflow_run (learner_id, started_at DESC);

CREATE INDEX idx_learning_workflow_running
  ON core.learning_workflow_run (deadline_at)
  WHERE status = 'RUNNING';

CREATE TABLE core.learning_workflow_step (
  id UUID PRIMARY KEY,
  run_id UUID NOT NULL REFERENCES core.learning_workflow_run(id) ON DELETE RESTRICT,
  step_name VARCHAR(32) NOT NULL,
  step_index INTEGER NOT NULL,
  status VARCHAR(24) NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  -- The claim. A step is executable only by the worker holding the current token, which exists
  -- exactly while an attempt is in flight. Clearing it on any terminal transition is what makes a
  -- cancellation or timeout win against a worker that is still talking to a model: that worker's
  -- completion is matched on the token and finds nothing to update.
  execution_token UUID,
  claimed_at TIMESTAMPTZ,
  reason_code VARCHAR(64),
  -- Correlates this step to core.ai_execution and core.agent_work_outbox. Null for the two
  -- deterministic steps, which make no agent call and therefore have no request identity.
  request_id VARCHAR(64),
  result_ref UUID,
  started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMPTZ,
  -- The structural loop bound: a step exists at most once per run.
  --
  -- Uniqueness on (run_id, step_index) is deliberately NOT declared as well. step_index is a pure
  -- function of step_name, so it would restate this same fact -- but ON CONFLICT can infer only one
  -- index, and a genuine two-worker race then collides on the uninferred one and raises instead of
  -- being handled. A redundant constraint that turns a handled conflict into an error is a
  -- liability, not defence in depth. The range CHECK below still pins step_index to the four steps.
  CONSTRAINT uq_learning_workflow_step_name UNIQUE (run_id, step_name),
  CONSTRAINT ck_learning_workflow_step_name CHECK (
    step_name IN ('RECORD_EVALUATION_EVIDENCE', 'RECOMPUTE_MASTERY', 'DIAGNOSE', 'ADAPT')),
  CONSTRAINT ck_learning_workflow_step_status CHECK (
    status IN ('PENDING', 'RUNNING', 'COMPLETED', 'SKIPPED', 'CANCELLED', 'TIMED_OUT', 'FAILED')),
  CONSTRAINT ck_learning_workflow_step_index CHECK (step_index BETWEEN 0 AND 3),
  CONSTRAINT ck_learning_workflow_step_attempts CHECK (attempt_count BETWEEN 0 AND 32),
  CONSTRAINT ck_learning_workflow_step_reason CHECK (
    reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  -- A live token and a running step are the same fact; neither may exist without the other.
  CONSTRAINT ck_learning_workflow_step_claim CHECK (
    (status = 'RUNNING') = (execution_token IS NOT NULL)),
  -- An attempt count above zero means the step was actually claimed, so it must carry the time it
  -- was. SKIPPED steps and a run that timed out before claiming anything keep a count of zero,
  -- which is what stops the audit from showing attempts that never happened.
  CONSTRAINT ck_learning_workflow_step_claimed_at CHECK (
    (attempt_count = 0) = (claimed_at IS NULL)),
  CONSTRAINT ck_learning_workflow_step_completion CHECK (
    (status IN ('PENDING', 'RUNNING') AND completed_at IS NULL)
    OR (status NOT IN ('PENDING', 'RUNNING') AND completed_at IS NOT NULL))
);

CREATE INDEX idx_learning_workflow_step_run
  ON core.learning_workflow_step (run_id, step_index);

CREATE INDEX idx_learning_workflow_step_request
  ON core.learning_workflow_step (request_id)
  WHERE request_id IS NOT NULL;

COMMENT ON TABLE core.learning_workflow_run IS
  'M2-T14 deterministic composition of evaluation, mastery, diagnosis and adaptation';
COMMENT ON COLUMN core.learning_workflow_run.trigger_key IS
  'Deterministic trigger identity; a duplicate trigger collapses onto the existing run';
COMMENT ON COLUMN core.learning_workflow_run.deadline_at IS
  'Absolute workflow deadline; the sweeper moves an overrun run to TIMED_OUT';
COMMENT ON TABLE core.learning_workflow_step IS
  'M2-T14 per-step observability; one row per step per run bounds the composition structurally';
COMMENT ON COLUMN core.learning_workflow_step.execution_token IS
  'Identifies the worker attempt that currently owns this step; null unless the step is RUNNING';
COMMENT ON COLUMN core.learning_workflow_step.request_id IS
  'Joins the step to core.ai_execution and core.agent_work_outbox; null for deterministic steps';
