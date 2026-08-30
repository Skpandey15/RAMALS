CREATE SCHEMA IF NOT EXISTS identity;

-- USAGE only. There is deliberately no REVOKE CREATE here: PostgreSQL grants CREATE on a new schema
-- to its owner alone -- ramals_core_migration -- and to no one else, so the runtime role never holds
-- it and a REVOKE would remove nothing. It also reads to the rollback checker as a privilege being
-- taken away from the previously released image, which for a schema created in this same migration
-- is not something that image could ever have held.
GRANT USAGE ON SCHEMA identity TO ramals_core_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE ramals_core_migration IN SCHEMA identity
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ramals_core_runtime;

CREATE TABLE identity.registration_operation (
  id UUID PRIMARY KEY,
  idempotency_key VARCHAR(128) NOT NULL UNIQUE,
  request_fingerprint CHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  keycloak_subject VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_registration_operation_status CHECK (status IN
    ('STARTED','IDENTITY_CREATED','EMAIL_PENDING','FAILED_RECOVERABLE'))
);

CREATE TABLE identity.learner_contact (
  learner_id UUID PRIMARY KEY REFERENCES core.learner(id) ON DELETE RESTRICT,
  first_name VARCHAR(100) NOT NULL,
  last_name VARCHAR(100) NOT NULL,
  email_normalized VARCHAR(320) NOT NULL UNIQUE,
  mobile_e164 VARCHAR(20) NOT NULL,
  country_code CHAR(2) NOT NULL,
  city VARCHAR(120),
  email_verified_at TIMESTAMPTZ,
  mobile_verified_at TIMESTAMPTZ,
  terms_version VARCHAR(64) NOT NULL,
  terms_document_ref VARCHAR(128) NOT NULL,
  privacy_version VARCHAR(64) NOT NULL,
  privacy_document_ref VARCHAR(128) NOT NULL,
  terms_accepted_at TIMESTAMPTZ NOT NULL,
  privacy_accepted_at TIMESTAMPTZ NOT NULL,
  adult_statement_version VARCHAR(64) NOT NULL,
  adult_confirmed_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_learner_contact_verified_mobile
  ON identity.learner_contact (mobile_e164) WHERE mobile_verified_at IS NOT NULL;

CREATE TABLE identity.professional_onboarding (
  learner_id UUID PRIMARY KEY REFERENCES core.learner(id) ON DELETE RESTRICT,
  onboarding_state VARCHAR(32) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_professional_onboarding_state CHECK (onboarding_state IN
    ('IDENTITY_CREATED','EMAIL_PENDING','EMAIL_VERIFIED','MOBILE_PENDING','MOBILE_VERIFIED',
     'PROFILE_PENDING','JOURNEY_PENDING','ONBOARDED'))
);

CREATE TABLE identity.mobile_verification_challenge (
  id UUID PRIMARY KEY,
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  mobile_e164 VARCHAR(20) NOT NULL,
  otp_hmac BYTEA NOT NULL,
  hmac_key_version VARCHAR(32) NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  max_attempts INTEGER NOT NULL,
  policy_version VARCHAR(32) NOT NULL,
  resend_generation INTEGER NOT NULL DEFAULT 0,
  provider_message_ref VARCHAR(128),
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  verified_at TIMESTAMPTZ,
  superseded_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_mobile_challenge_attempts CHECK
    (attempt_count >= 0 AND max_attempts BETWEEN 1 AND 10 AND attempt_count <= max_attempts)
);
CREATE INDEX idx_mobile_challenge_learner_created
  ON identity.mobile_verification_challenge (learner_id, created_at DESC);

CREATE TABLE identity.abuse_counter (
  bucket_key CHAR(64) NOT NULL,
  window_started_at TIMESTAMPTZ NOT NULL,
  request_count INTEGER NOT NULL,
  PRIMARY KEY (bucket_key, window_started_at),
  CONSTRAINT ck_abuse_counter_positive CHECK (request_count > 0)
);

CREATE TABLE audit.registration_event (
  id UUID PRIMARY KEY,
  operation_id UUID,
  learner_id UUID,
  challenge_id UUID,
  event_type VARCHAR(48) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  reason_code VARCHAR(64),
  interaction_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_registration_event_type CHECK (length(btrim(event_type)) > 0),
  CONSTRAINT ck_registration_event_outcome CHECK (outcome IN ('SUCCESS','FAILURE','REJECTED'))
);
CREATE INDEX idx_registration_event_operation ON audit.registration_event(operation_id,created_at);
CREATE INDEX idx_registration_event_learner ON audit.registration_event(learner_id,created_at);

CREATE FUNCTION audit.reject_registration_event_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit.registration_event is append-only; % is not permitted', TG_OP USING ERRCODE='55000';
END;
$$;
CREATE TRIGGER trg_registration_event_append_only BEFORE UPDATE OR DELETE ON audit.registration_event
FOR EACH ROW EXECUTE FUNCTION audit.reject_registration_event_mutation();

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA identity TO ramals_core_runtime;

COMMENT ON TABLE identity.learner_contact IS
  'Least-privilege professional registration/contact PII; core.learner remains PII-free';
COMMENT ON COLUMN identity.mobile_verification_challenge.otp_hmac IS
  'HMAC-SHA-256 over UTF-8 challenge UUID, NUL, E.164 mobile, NUL, numeric OTP';
