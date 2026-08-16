-- Closes two conformance gaps against the Implementation Master Plan §8 (Database Correlation
-- Strategy), found by auditing the schema against the plan rather than against the design docs.
--
-- 1. core.assessment_attempt carried no interaction_id, so an attempt row could not be joined back
--    to the logical interaction that created it. The plan requires it ("Yes / useful business-flow
--    correlation"); every ledger table already complies.
--
-- 2. audit.security_audit did not exist at all. The plan requires it with BOTH interaction_id and
--    trace_id for security investigation. Only administrative activity was audited, so
--    authentication and authorization denials survived nowhere but the application log.

-- --- 1. Attempt correlation ----------------------------------------------------------------------
-- Nullable: rows created before this migration have no interaction to attribute, and inventing one
-- would be worse than recording the truth. New rows are always written with the caller's id.
ALTER TABLE core.assessment_attempt ADD COLUMN interaction_id VARCHAR(64);

COMMENT ON COLUMN core.assessment_attempt.interaction_id IS
  'Logical interaction that created the attempt; NULL only for rows predating V014';

CREATE INDEX idx_assessment_attempt_interaction ON core.assessment_attempt (interaction_id)
  WHERE interaction_id IS NOT NULL;

-- --- 2. Security audit ---------------------------------------------------------------------------
-- Immutable at two layers, matching audit.admin_activity: the runtime role holds only SELECT and
-- INSERT on audit tables (V002 default privileges), and an append-only trigger rejects UPDATE or
-- DELETE regardless of role.
--
-- Deliberately NOT stored: tokens, credentials, request bodies, or the value of any header that
-- could carry a secret. `detail` is for a short safe reason phrase only.
CREATE TABLE audit.security_audit (
  id UUID PRIMARY KEY,
  event_type VARCHAR(48) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  subject VARCHAR(255),
  http_method VARCHAR(10),
  route VARCHAR(255),
  status_code INTEGER,
  reason_code VARCHAR(64),
  detail TEXT,
  interaction_id VARCHAR(64) NOT NULL,
  trace_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_security_audit_outcome CHECK (outcome IN ('DENIED', 'ALLOWED')),
  CONSTRAINT ck_security_audit_event CHECK (length(btrim(event_type)) > 0),
  CONSTRAINT ck_security_audit_interaction CHECK (length(btrim(interaction_id)) > 0)
);

COMMENT ON TABLE audit.security_audit IS
  'Append-only audit of authentication and authorization decisions (Master Plan §8)';

-- Investigation starts from an interactionId off a support ticket, or sweeps a subject over time.
CREATE INDEX idx_security_audit_interaction ON audit.security_audit (interaction_id);
CREATE INDEX idx_security_audit_subject ON audit.security_audit (subject, created_at DESC)
  WHERE subject IS NOT NULL;
CREATE INDEX idx_security_audit_event ON audit.security_audit (event_type, created_at DESC);

CREATE FUNCTION audit.reject_security_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'audit.security_audit is append-only; % is not permitted', TG_OP
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_security_audit_append_only
BEFORE UPDATE OR DELETE ON audit.security_audit
FOR EACH ROW EXECUTE FUNCTION audit.reject_security_audit_mutation();
