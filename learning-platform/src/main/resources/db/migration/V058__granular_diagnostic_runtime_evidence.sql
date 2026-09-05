-- Granular diagnostic runtime evidence capture (M2-ADR-027): immutable, event-time misconception
-- evidence, wired passively into the existing deterministic scoring flow. Consumes -- never
-- modifies -- the ontology and pure classifier M2-ADR-026 (V057) already established, and touches
-- none of H4b/H5's tables (core.diagnostic_probe_relationship, core.diagnostic_probe_provenance,
-- core.diagnostic_confidence_observation) or DiagnosticConfidenceCalculatorV1.
--
-- One observation per (response, misconception) pair the response was event-time-eligible for --
-- "event-time" meaning: only assessment_item_option_misconception rows that were already PUBLISHED
-- at or before the exact moment the response itself was recorded
-- (core.assessment_response.created_at, populated purely by that column's own DEFAULT, never
-- supplied by the application -- verified before writing this migration) count toward eligibility. A
-- mapping published afterward never retroactively explains an already-scored response.
--
-- core.assessment_response is the exact provenance anchor (a direct FK to its own primary key), not
-- a reconstructed (attempt_id, item_version_id) pair -- the evidence statement is precisely "this
-- response produced this evidence about misconception M".

CREATE TABLE core.misconception_evidence_observation (
  id UUID PRIMARY KEY,
  response_id UUID NOT NULL REFERENCES core.assessment_response(id) ON DELETE RESTRICT,
  -- Denormalized for cheap learner-scoped reads, the same choice core.diagnostic_confidence_
  -- observation (V056) already made for its own learner_id column -- DB-verified below against
  -- response -> attempt -> learner, never trusted from the application alone.
  learner_id UUID NOT NULL REFERENCES core.learner(id) ON DELETE RESTRICT,
  misconception_id UUID NOT NULL REFERENCES core.misconception(id) ON DELETE RESTRICT,
  outcome VARCHAR(16) NOT NULL,
  -- Governs the whole capture policy as one unit (eligibility semantics, evaluate-every-eligible-
  -- misconception, the outcome truth table, event-time capture, no retroactive reinterpretation) --
  -- not merely the classifier's own two-input arithmetic. MisconceptionEvidenceOutcome itself is
  -- deliberately not frozen via EngineVersionFreezeTests (M2-ADR-027 §7) -- it has no tunable
  -- threshold or weight, the same reasoning HypothesisEvidenceOutcome (H4b) was never frozen either.
  policy_version VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (response_id, misconception_id),
  CONSTRAINT ck_misconception_evidence_observation_outcome CHECK (
    outcome IN ('SUPPORTING', 'CONTRADICTORY', 'INCONCLUSIVE')
  ),
  CONSTRAINT ck_misconception_evidence_observation_policy_version CHECK (
    policy_version = 'MISCONCEPTION_EVIDENCE_V1'
  )
);

COMMENT ON TABLE core.misconception_evidence_observation IS
  'MISCONCEPTION_EVIDENCE_V1 (M2-ADR-027): one immutable, append-only record of how one scored '
  'SINGLE_CHOICE response relates to one misconception, as of the moment that response was '
  'recorded. Never a diagnosis, never fed by or fed into mastery computation, and never itself an '
  'H4b probe relationship or H5 confidence observation -- a separate, parallel evidence stream.';

CREATE INDEX idx_misconception_evidence_observation_learner
  ON core.misconception_evidence_observation (learner_id, misconception_id);

-- Immutable once written, and validates the two consistency facts no FK or CHECK above can express:
-- learner_id must be the learner who actually owns the response's own attempt (a lookup through
-- assessment_response -> assessment_attempt, since assessment_response itself carries no learner_id
-- column); and outcome must equal exactly what the event-time eligibility set for this misconception
-- and this response's own scoring facts would produce -- derived independently, directly from
-- core.assessment_item_option_misconception and core.assessment_response, never from this row's own
-- provenance children (core.misconception_evidence_observation_mapping below), which cannot exist
-- yet at the moment this parent row is written. This is the exact mechanism M2-ADR-027 §6 requires:
-- an observation's own claimed outcome can never be wrong regardless of whether its provenance is
-- ever written at all.
CREATE FUNCTION core.protect_misconception_evidence_observation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  response_row core.assessment_response%ROWTYPE;
  owning_learner_id UUID;
  any_eligible BOOLEAN;
  selected_option_eligible BOOLEAN;
  expected_outcome VARCHAR(16);
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'misconception evidence observations are immutable' USING ERRCODE = '55000';
  END IF;

  SELECT * INTO response_row FROM core.assessment_response WHERE id = NEW.response_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'response_id % does not exist', NEW.response_id USING ERRCODE = '23503';
  END IF;

  SELECT a.learner_id INTO owning_learner_id
    FROM core.assessment_attempt a WHERE a.id = response_row.attempt_id;
  IF owning_learner_id IS DISTINCT FROM NEW.learner_id THEN
    RAISE EXCEPTION
      'learner_id % does not match the learner who owns response %''s own attempt (%)',
      NEW.learner_id, NEW.response_id, owning_learner_id USING ERRCODE = '23514';
  END IF;

  SELECT EXISTS (
    SELECT 1 FROM core.assessment_item_option_misconception m
     WHERE m.item_version_id = response_row.item_version_id
       AND m.misconception_id = NEW.misconception_id
       AND m.status = 'PUBLISHED'
       AND m.published_at <= response_row.created_at
  ) INTO any_eligible;

  IF NOT any_eligible THEN
    RAISE EXCEPTION
      'misconception % has no event-time-eligible PUBLISHED mapping for response %''s item as of %',
      NEW.misconception_id, NEW.response_id, response_row.created_at USING ERRCODE = '23514';
  END IF;

  IF response_row.is_correct THEN
    expected_outcome := 'CONTRADICTORY';
  ELSE
    SELECT EXISTS (
      SELECT 1 FROM core.assessment_item_option_misconception m
       WHERE m.item_version_id = response_row.item_version_id
         AND m.misconception_id = NEW.misconception_id
         AND m.status = 'PUBLISHED'
         AND m.published_at <= response_row.created_at
         AND m.option_id = (response_row.response_jsonb -> 'selectedOptions' ->> 0)
    ) INTO selected_option_eligible;
    expected_outcome := CASE WHEN selected_option_eligible THEN 'SUPPORTING' ELSE 'INCONCLUSIVE' END;
  END IF;

  IF NEW.outcome <> expected_outcome THEN
    RAISE EXCEPTION
      'persisted outcome % does not match the event-time-derived outcome % for response %, misconception %',
      NEW.outcome, expected_outcome, NEW.response_id, NEW.misconception_id USING ERRCODE = '23514';
  END IF;

  NEW.created_at := CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_misconception_evidence_observation_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.misconception_evidence_observation
FOR EACH ROW EXECUTE FUNCTION core.protect_misconception_evidence_observation();

-- -------------------------------------------------------------------------------------------
-- core.misconception_evidence_observation_mapping: the COMPLETE event-time-eligible mapping set
-- for one observation's (response, misconception) pair -- one row per contributing mapping, not
-- merely the one matching the selected option. Reconstructable authored provenance answering
-- "which authored mapping(s) made this response evidence-eligible for M, as of event time" without
-- ever re-deriving it from whatever mappings merely happen to exist at query time.
-- -------------------------------------------------------------------------------------------

CREATE TABLE core.misconception_evidence_observation_mapping (
  observation_id UUID NOT NULL REFERENCES core.misconception_evidence_observation(id) ON DELETE RESTRICT,
  item_version_id UUID NOT NULL,
  option_id VARCHAR(8) NOT NULL,
  misconception_id UUID NOT NULL,
  PRIMARY KEY (observation_id, item_version_id, option_id),
  -- References assessment_item_option_misconception's own existing primary key -- no new unique key
  -- added to that already-merged (V057) table, the same "don't widen an already-shipped table's
  -- uniqueness" lesson the H5 hardening round already established.
  FOREIGN KEY (item_version_id, option_id, misconception_id)
    REFERENCES core.assessment_item_option_misconception(item_version_id, option_id, misconception_id)
    ON DELETE RESTRICT
);

COMMENT ON TABLE core.misconception_evidence_observation_mapping IS
  'The complete set of PUBLISHED core.assessment_item_option_misconception rows that made one '
  'observation''s (response, misconception) pair event-time-eligible -- an exact, immutable '
  'snapshot of every authored fact relied upon, not merely the one causally decisive for the '
  'outcome.';

CREATE INDEX idx_misconception_evidence_observation_mapping_misconception
  ON core.misconception_evidence_observation_mapping (item_version_id, option_id, misconception_id);

-- Immutable once written, and validates the two consistency facts no FK above can express: this
-- row's own misconception_id must match its parent observation's own misconception_id; and the
-- referenced mapping must actually be PUBLISHED (the FK above only proves a row with that key
-- exists, not that it is published) and must have been published at or before the response's own
-- created_at -- the direct, per-citation event-time proof M2-ADR-027 §6 requires. A mapping
-- published after the response it is cited against is rejected here, regardless of what the parent
-- observation itself claims.
CREATE FUNCTION core.protect_misconception_evidence_observation_mapping()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  observation_row core.misconception_evidence_observation%ROWTYPE;
  response_row core.assessment_response%ROWTYPE;
  mapping_row core.assessment_item_option_misconception%ROWTYPE;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'misconception evidence provenance rows are immutable' USING ERRCODE = '55000';
  END IF;

  SELECT * INTO observation_row
    FROM core.misconception_evidence_observation WHERE id = NEW.observation_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'observation_id % does not exist', NEW.observation_id USING ERRCODE = '23503';
  END IF;
  IF observation_row.misconception_id IS DISTINCT FROM NEW.misconception_id THEN
    RAISE EXCEPTION
      'provenance misconception_id % does not match its observation %''s own misconception_id %',
      NEW.misconception_id, NEW.observation_id, observation_row.misconception_id
      USING ERRCODE = '23514';
  END IF;

  SELECT * INTO mapping_row
    FROM core.assessment_item_option_misconception
   WHERE item_version_id = NEW.item_version_id AND option_id = NEW.option_id
     AND misconception_id = NEW.misconception_id;
  IF NOT FOUND OR mapping_row.status <> 'PUBLISHED' THEN
    RAISE EXCEPTION 'mapping (%, %, %) is not a PUBLISHED misconception option mapping',
      NEW.item_version_id, NEW.option_id, NEW.misconception_id USING ERRCODE = '23514';
  END IF;

  SELECT * INTO response_row FROM core.assessment_response WHERE id = observation_row.response_id;

  IF mapping_row.published_at > response_row.created_at THEN
    RAISE EXCEPTION
      'mapping (%, %, %) was published at % which is after response %''s own created_at % -- '
      'cannot support historical evidence',
      NEW.item_version_id, NEW.option_id, NEW.misconception_id, mapping_row.published_at,
      observation_row.response_id, response_row.created_at USING ERRCODE = '23514';
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_misconception_evidence_observation_mapping_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.misconception_evidence_observation_mapping
FOR EACH ROW EXECUTE FUNCTION core.protect_misconception_evidence_observation_mapping();
