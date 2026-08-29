-- V039: the durable negative memo for lost-acknowledgement enumeration (M2-ADR-020 §3.1).
--
-- One row per (request, batch) proving a batch was opened, read to completion, and did NOT carry
-- this request's custom_id. Enumeration skips those batches on every later attempt, which is what
-- makes a bounded search resume rather than restart.
--
-- Why this is sound: an ended batch's results are immutable, so a batch that did not carry the key
-- when fully read never will. Skipping it is the same search, not a shorter one. The precondition is
-- narrow and load-bearing -- only ended, fully-streamed, non-matching batches -- because memoising a
-- candidate nobody actually read would let a later search claim complete coverage it never had, and
-- a false ZERO is terminal.
--
-- Why it is NOT core.ai_provider_execution_observation (V038): that table means "a provider
-- execution attributable to this request" and feeds Definition-of-Done criterion 8's cost evidence.
-- This table means the exact opposite -- "this batch is not this request's". Recording one as the
-- other would fill the cost evidence with other requests' executions to save a table.

CREATE TABLE core.ai_enumeration_no_match (
  request_id            VARCHAR(64)  NOT NULL
                          REFERENCES core.ai_provider_execution(request_id) ON DELETE CASCADE,
  provider_execution_id VARCHAR(128) NOT NULL,
  -- The key that was searched for and proven absent. Stored so the memo is self-describing: a row
  -- means "batch X does not carry key K", which is only useful if K is written down. A request's
  -- custom_id never changes, so this cannot disagree with ai_provider_execution.
  custom_id             VARCHAR(128) NOT NULL,
  inspected_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- (request, batch) is the natural key and the lookup: enumeration reads every batch already ruled
  -- out for one request, then writes the ones it has newly ruled out.
  PRIMARY KEY (request_id, provider_execution_id),

  CONSTRAINT ck_ai_enumeration_no_match_ids CHECK (
    length(btrim(request_id)) > 0
    AND length(btrim(provider_execution_id)) > 0
    AND length(btrim(custom_id)) > 0)
);

COMMENT ON TABLE core.ai_enumeration_no_match IS
  'Batches proven NOT to carry a Contract B request''s custom_id (M2-ADR-020 section 3.1). A pure '
  'optimisation and never evidence: it holds no usage, no cost and no outcome, and it is safe to '
  'delete at the cost of one repeated inspection. Written only for a batch that had ended and whose '
  'results streamed to completion -- never for one still processing, unreadable, truncated, or '
  'skipped for a bound -- because a memoised candidate counts as inspected coverage, and coverage '
  'nobody established would let a search report ZERO, which is terminal.';

COMMENT ON COLUMN core.ai_enumeration_no_match.provider_execution_id IS
  'The batch that was read and did not carry this request. Deliberately NOT a reference to '
  'ai_provider_execution: this batch belongs to some other request, or to none.';

-- Access matrix, M2-ADR-018 section 3. V002 grants the runtime role every privilege on every future
-- core table, so a narrow matrix has to be produced by revoking first -- the grant below is not
-- additive, it is what remains.
--
-- SELECT and INSERT only. The runtime reads what it has ruled out and appends what it newly rules
-- out; it never needs to rewrite or erase a fact that is permanent by construction. Enforced by
-- privilege rather than by an append-only trigger, deliberately: a BEFORE DELETE trigger would also
-- fire for the ON DELETE CASCADE above and would turn purging an execution into an error.
REVOKE ALL ON TABLE core.ai_enumeration_no_match FROM PUBLIC;
REVOKE ALL ON TABLE core.ai_enumeration_no_match FROM ramals_core_runtime;
GRANT SELECT, INSERT ON TABLE core.ai_enumeration_no_match TO ramals_core_runtime;

-- The AI plane never touches Contract B state. M2-ADR-017 section 1 makes Spring/PostgreSQL
-- authoritative and the AI plane stateless, and this boundary is where that stops being a
-- convention. Conditional because the role is not provisioned in every environment.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_ai_runtime') THEN
    RAISE NOTICE 'ramals_ai_runtime is not provisioned here; skipping no-match memo boundary revoke';
    RETURN;
  END IF;
  EXECUTE 'REVOKE ALL ON TABLE core.ai_enumeration_no_match FROM ramals_ai_runtime';
END
$$;
