-- M2-T15.2 Contract A: single-submission / fail-closed diagnostic execution.
--
-- A provider call whose outcome is unknown is not a provider failure. Record that uncertainty
-- explicitly, keep the request terminal/non-dispatchable, and retain only provider-issued receipt
-- identifiers and a response digest when a response actually survives to the success transaction.

ALTER TABLE core.ai_execution
  ADD COLUMN provider_request_id VARCHAR(128),
  ADD COLUMN provider_message_id VARCHAR(128),
  ADD COLUMN response_digest CHAR(64);

ALTER TABLE core.ai_execution
  DROP CONSTRAINT ck_ai_execution_status,
  ADD CONSTRAINT ck_ai_execution_status
    CHECK (status IN ('SUCCEEDED', 'FAILED', 'INDETERMINATE'));

CREATE INDEX ix_ai_execution_provider_request
  ON core.ai_execution(provider_request_id)
  WHERE provider_request_id IS NOT NULL;

CREATE INDEX ix_ai_execution_provider_message
  ON core.ai_execution(provider_message_id)
  WHERE provider_message_id IS NOT NULL;

ALTER TABLE core.ai_execution_event
  ADD COLUMN provider_request_id VARCHAR(128),
  ADD COLUMN provider_message_id VARCHAR(128),
  ADD COLUMN response_digest CHAR(64);

ALTER TABLE core.ai_execution_event
  DROP CONSTRAINT ck_ai_execution_event_type,
  ADD CONSTRAINT ck_ai_execution_event_type
    CHECK (event_type IN ('STARTED', 'SUCCEEDED', 'FAILED', 'INDETERMINATE'));

COMMENT ON COLUMN core.ai_execution.provider_request_id IS
  'Provider-issued request identifier retained for support correlation only; not a replay key';
COMMENT ON COLUMN core.ai_execution.provider_message_id IS
  'Provider-issued message/completion identifier retained for support correlation only';
COMMENT ON COLUMN core.ai_execution.response_digest IS
  'SHA-256 of the provider response text retained without storing provider/model content';
COMMENT ON COLUMN core.ai_execution_event.provider_request_id IS
  'Provider-issued request identifier copied into the immutable terminal lifecycle event';
COMMENT ON COLUMN core.ai_execution_event.provider_message_id IS
  'Provider-issued message/completion identifier copied into the immutable terminal lifecycle event';
COMMENT ON COLUMN core.ai_execution_event.response_digest IS
  'SHA-256 of the provider response text copied into the immutable terminal lifecycle event';
