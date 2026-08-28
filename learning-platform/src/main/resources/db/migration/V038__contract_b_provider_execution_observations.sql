-- Contract B: every provider execution discovered for a request, adopted or not (M2-ADR-020 §5).
--
-- One table, additive, touching nothing that exists. It records the answer to a question RAMALS
-- previously could not ask: when a submission acknowledgement was lost and enumeration went looking,
-- what did it find?
--
-- Separate from core.ai_provider_execution on purpose. That table is one row per RAMALS request,
-- keyed on request_id, with a unique index on provider_execution_id -- by construction it cannot
-- hold two executions for one request, and widening it to allow that would destroy the very
-- invariant that makes a duplicate detectable. An observation is a different fact with a different
-- lifecycle: "at this time, this provider execution was found carrying this request's custom_id",
-- which stays true afterwards regardless of what is adopted.
--
-- Identifiers, counts and timestamps only. No free-text column, so V023's structural-redaction
-- guarantee extends here unchanged: a prompt, a context package or model output cannot be stored in
-- this table even by a caller that tried.

CREATE TABLE core.ai_provider_execution_observation (
  id                    BIGSERIAL    PRIMARY KEY,
  request_id            VARCHAR(64)  NOT NULL,
  provider_execution_id VARCHAR(128) NOT NULL,
  custom_id             VARCHAR(128) NOT NULL,
  outcome               VARCHAR(24)  NOT NULL,
  discovered_by         VARCHAR(32)  NOT NULL,
  input_tokens          INTEGER,
  output_tokens         INTEGER,
  cached_input_tokens   INTEGER,
  estimated_cost_usd    NUMERIC(18, 8),
  provider_created_at   TIMESTAMPTZ,
  provider_ended_at     TIMESTAMPTZ,
  observed_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- One observation per (request, provider execution). Re-running a search must not multiply the
  -- evidence: a second sweep that finds the same duplicate has learned nothing new, and a count of
  -- observations should mean "how many executions exist", not "how many times we looked".
  CONSTRAINT uq_ai_provider_execution_observation
    UNIQUE (request_id, provider_execution_id),
  CONSTRAINT ck_ai_provider_execution_observation_ids CHECK (
    length(btrim(request_id)) > 0
    AND length(btrim(provider_execution_id)) > 0
    AND length(btrim(custom_id)) > 0),
  CONSTRAINT ck_ai_provider_execution_observation_outcome CHECK (
    outcome IN ('succeeded', 'errored', 'canceled', 'expired', 'unknown')),
  -- How it was found. Enumeration is the only source today; naming it means a future source is a
  -- new value rather than a silent change of meaning.
  CONSTRAINT ck_ai_provider_execution_observation_source CHECK (
    discovered_by IN ('ENUMERATION', 'ACKNOWLEDGEMENT')),
  CONSTRAINT ck_ai_provider_execution_observation_usage CHECK (
    (input_tokens IS NULL OR input_tokens >= 0)
    AND (output_tokens IS NULL OR output_tokens >= 0)
    AND (cached_input_tokens IS NULL OR cached_input_tokens >= 0))
);

CREATE INDEX ix_ai_provider_execution_observation_request
  ON core.ai_provider_execution_observation(request_id, observed_at);

-- Append-only in the strong sense: no UPDATE, no DELETE. An observation is evidence of what was
-- found, and evidence that can be revised is not evidence.
--
-- Deliberately not carrying an "adopted" flag, which would have required UPDATE. Which execution
-- was adopted is already recorded -- it is the one in core.ai_provider_execution.
-- provider_execution_id -- so a flag here would be a second copy of a fact, free to disagree with
-- the first.
CREATE FUNCTION core.reject_ai_provider_execution_observation_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'contract B provider execution observations are append-only'
    USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_ai_provider_execution_observation_append_only
BEFORE UPDATE OR DELETE ON core.ai_provider_execution_observation
FOR EACH ROW EXECUTE FUNCTION core.reject_ai_provider_execution_observation_mutation();

COMMENT ON TABLE core.ai_provider_execution_observation IS
  'Every provider execution discovered for a Contract B request, adopted or not (M2-ADR-020 §5). '
  'Satisfies the Definition of Done requirement that cost evidence account for every provider '
  'execution attributable to one logical request: a duplicate found by enumeration is recorded here '
  'with its usage, so it appears in the evidence rather than only in a log. Append-only, never '
  'purged: it holds bounded metadata and is what explains a duplicate after the results are gone.';
COMMENT ON COLUMN core.ai_provider_execution_observation.custom_id IS
  'The correlation key the execution was proven to carry, read from batch results. Never taken from '
  'batch list metadata, which carries no custom_id at all.';

-- Access control, exactly as V037 established it. The revokes are not decoration: V002 grants
-- SELECT, INSERT, UPDATE and DELETE on every future core table to ramals_core_runtime by default
-- privilege, so all four arrive at CREATE and the matrix is produced by taking them away.
REVOKE ALL ON TABLE core.ai_provider_execution_observation FROM PUBLIC;
REVOKE ALL ON TABLE core.ai_provider_execution_observation FROM ramals_core_runtime;
GRANT SELECT, INSERT ON TABLE core.ai_provider_execution_observation TO ramals_core_runtime;
GRANT USAGE, SELECT ON SEQUENCE core.ai_provider_execution_observation_id_seq
  TO ramals_core_runtime;

-- The AI plane gets nothing, stated rather than left to omission, and guarded exactly as V015,
-- V023 and V037 guard it: ramals_core_migration deliberately lacks CREATEROLE, so a database
-- without the role provisioned is a legitimate configuration rather than an error.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ramals_ai_runtime') THEN
    RAISE NOTICE 'ramals_ai_runtime is not provisioned here; skipping observation boundary revokes';
    RETURN;
  END IF;

  EXECUTE 'REVOKE ALL ON TABLE core.ai_provider_execution_observation FROM ramals_ai_runtime';
  EXECUTE 'REVOKE ALL ON SEQUENCE core.ai_provider_execution_observation_id_seq '
          'FROM ramals_ai_runtime';
END
$$;
