-- Administrative activity audit. Every privileged content operation (and every
-- rejected attempt) is recorded in the immutable audit schema with the actor, the
-- action and its outcome, and the interaction/trace correlation ids. Immutable at
-- two layers: the runtime role holds only SELECT and INSERT on audit tables (from
-- the V002 default privileges), and an append-only trigger rejects any UPDATE or
-- DELETE regardless of role.

CREATE TABLE audit.admin_activity (
  id UUID PRIMARY KEY,
  actor_subject VARCHAR(255) NOT NULL,
  action VARCHAR(48) NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id UUID,
  outcome VARCHAR(16) NOT NULL,
  detail TEXT,
  interaction_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_admin_activity_outcome CHECK (outcome IN ('SUCCESS', 'REJECTED')),
  CONSTRAINT ck_admin_activity_actor CHECK (length(btrim(actor_subject)) > 0),
  CONSTRAINT ck_admin_activity_interaction CHECK (length(btrim(interaction_id)) > 0)
);

COMMENT ON TABLE audit.admin_activity IS
  'Append-only audit of privileged content operations and rejected attempts';

CREATE INDEX idx_admin_activity_interaction ON audit.admin_activity (interaction_id);
CREATE INDEX idx_admin_activity_actor ON audit.admin_activity (actor_subject, created_at DESC);

CREATE FUNCTION audit.reject_admin_activity_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'audit.admin_activity is append-only; % is not permitted', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_admin_activity_append_only
BEFORE UPDATE OR DELETE ON audit.admin_activity
FOR EACH ROW EXECUTE FUNCTION audit.reject_admin_activity_mutation();
