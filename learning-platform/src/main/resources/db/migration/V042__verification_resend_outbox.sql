-- Moves the verification-resend provider call off the request path.
--
-- The route answers 202 identically for an unknown address, an already-verified one and a genuine
-- unverified one, so the response body and status reveal nothing. Execution time did: only the
-- third case went on to call the provider's send-verify-email, so the reply for a real unverified
-- account carried an extra admin round trip. Repeated latency samples separate those populations
-- statistically, which rebuilds by the clock the enumeration oracle the uniform body removes.
--
-- The send therefore becomes durable work claimed by a scheduled worker, matching the outbox in
-- V025 rather than inventing a second delivery mechanism: one row is written for every accepted
-- resend, and the request path performs identical work regardless of what the address turns out
-- to be.
--
-- No address is stored. `subject` holds the provider's opaque identifier when the lookup resolved
-- an unverified identity, and NULL when it did not. A NULL row is a deliberate no-op: it exists so
-- that the write on the request path is the same write in every case, and the worker deletes it
-- without contacting anyone. That is what keeps a probe for someone else's email from persisting
-- that email anywhere -- the pseudonym already exists (core.learner.subject) or nothing is kept.
CREATE TABLE identity.verification_resend_outbox (
  id UUID PRIMARY KEY,
  -- Provider subject (OIDC `sub`, ADR 0001). NULL means "nothing to send"; see above.
  subject VARCHAR(64),
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL,
  last_error_code VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_verification_resend_status CHECK (status IN ('PENDING', 'RETRY', 'TERMINAL')),
  CONSTRAINT ck_verification_resend_attempts CHECK (attempt_count >= 0),
  CONSTRAINT ck_verification_resend_subject CHECK (subject IS NULL OR length(btrim(subject)) > 0),
  CONSTRAINT ck_verification_resend_terminal CHECK (
    (status = 'TERMINAL' AND last_error_code IS NOT NULL)
    OR (status <> 'TERMINAL'))
);

-- The worker's only query shape: due work, oldest first. Partial on the non-terminal statuses so
-- abandoned rows, which are never claimed again, stay out of the index the hot path scans.
CREATE INDEX idx_verification_resend_due
  ON identity.verification_resend_outbox(next_attempt_at, created_at)
  WHERE status IN ('PENDING', 'RETRY');

GRANT SELECT, INSERT, UPDATE, DELETE ON identity.verification_resend_outbox TO ramals_core_runtime;

COMMENT ON TABLE identity.verification_resend_outbox IS
  'Durable verification-resend work. Written on every accepted resend so the request path costs the '
  'same regardless of whether the address resolves; carries a provider subject or NULL, never an '
  'email address. Rows are deleted once delivered or abandoned.';
COMMENT ON COLUMN identity.verification_resend_outbox.subject IS
  'Provider subject to send to, or NULL for an accepted request with nothing to send (unknown or '
  'already-verified address). NULL rows exist only to keep the request path uniform in time.';
