-- Learning session state. The learner journey is durable server-side state, not the
-- state of one synchronous HTTP request, so it survives restarts and resumes without
-- request coupling. Each transition is a single short optimistic UPDATE guarded by a
-- version predicate; there is no long-lived transaction across a learner's think time.
-- checkpoint_jsonb holds workflow-local state only and must not duplicate authoritative
-- learner truth. A partial unique index allows at most one open session per learner and
-- curriculum version, so restarting resumes the existing session instead of forking it.

CREATE TABLE core.learning_session (
  id UUID PRIMARY KEY,
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  curriculum_version_id UUID NOT NULL REFERENCES core.curriculum_version(id) ON DELETE RESTRICT,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  version INTEGER NOT NULL DEFAULT 1,
  checkpoint_jsonb JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_interaction_id VARCHAR(64) NOT NULL,
  last_interaction_id VARCHAR(64) NOT NULL,
  last_command VARCHAR(16),
  started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMPTZ,
  CONSTRAINT ck_learning_session_status
    CHECK (status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'ABANDONED')),
  CONSTRAINT ck_learning_session_version CHECK (version >= 1),
  CONSTRAINT ck_learning_session_checkpoint CHECK (jsonb_typeof(checkpoint_jsonb) = 'object'),
  CONSTRAINT ck_learning_session_created_interaction
    CHECK (length(btrim(created_interaction_id)) > 0),
  CONSTRAINT ck_learning_session_last_interaction
    CHECK (length(btrim(last_interaction_id)) > 0)
);

COMMENT ON TABLE core.learning_session IS
  'Durable, resumable learner journey state; transitions are optimistic and short-lived';

CREATE UNIQUE INDEX uq_learning_session_one_open
  ON core.learning_session (learner_id, curriculum_version_id)
  WHERE status IN ('ACTIVE', 'PAUSED');

CREATE INDEX idx_learning_session_learner
  ON core.learning_session (learner_id, curriculum_version_id, started_at DESC);

CREATE TRIGGER trg_learning_session_touch_updated_at
BEFORE UPDATE ON core.learning_session
FOR EACH ROW EXECUTE FUNCTION core.set_updated_at();

CREATE TABLE core.learning_session_transition (
  id UUID PRIMARY KEY,
  session_id UUID NOT NULL REFERENCES core.learning_session(id) ON DELETE RESTRICT,
  from_status VARCHAR(16),
  to_status VARCHAR(16) NOT NULL,
  command VARCHAR(16) NOT NULL,
  version_after INTEGER NOT NULL,
  interaction_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (session_id, version_after),
  CONSTRAINT ck_session_transition_command
    CHECK (command IN ('START', 'PAUSE', 'RESUME', 'COMPLETE', 'ABANDON')),
  CONSTRAINT ck_session_transition_to_status
    CHECK (to_status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'ABANDONED')),
  CONSTRAINT ck_session_transition_interaction CHECK (length(btrim(interaction_id)) > 0)
);

COMMENT ON TABLE core.learning_session_transition IS
  'Append-only log of session transitions with interactionId correlation';

CREATE INDEX idx_session_transition_interaction
  ON core.learning_session_transition (interaction_id);
