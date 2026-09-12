-- M2-ADR-034 Amendment 4 (Step 4): DIAGNOSTIC_SELECTION_V6 exact-replay provenance.
--
-- Amendment 3 §V originally claimed a V6 decision's historical exposure state was exactly
-- reconstructable from core.assessment_attempt.created_at alone, with no migration. Amendment 4
-- corrected that: created_at orders row-creation statement time, never commit-visibility order, so
-- a created_at-bounded reconstruction can diverge from what the original decision actually saw --
-- proven against real PostgreSQL under an ordinary concurrent-commit interleaving (no adversarial
-- timing required). Separately, V6's source attempt is chosen by a "most recent completed attempt"
-- lookup, itself time-sensitive: re-running it later, after the learner completes a further
-- attempt, can return a different attempt than the one the original decision used.
--
-- Amendment 4's governing principle: a V6 input that depends on transient decision-time database
-- state -- which rows were visible, or which row a time-sensitive query would return -- and cannot
-- later be reconstructed exactly, must be persisted at decision time instead. This migration adds
-- exactly the two additive tables that boundary requires: which source attempt V6 used, and the
-- exact V6-actionable hypothesis / candidate-probe working set it evaluated. Everything else V6
-- reads (source-attempt evidence, published relationships, the engines' own mathematics) is already
-- immutable and stays reconstructable once the source attempt's own identity is known -- see the
-- ADR's own Category A/B split (Amendment 4 §E/§F).
--
-- Persist WHICH source attempt was used; reconstruct WHAT that source attempt contained.

-- One header row per DIAGNOSTIC_SELECTION_V6 attempt, written unconditionally regardless of
-- outcome -- V6's own select() already runs, and already decides NO_SOURCE_ATTEMPT/every other
-- fallback, for every such attempt; this table persists the result of a computation that already
-- happens, never a new evaluation path. destination_attempt_id is UNIQUE so at most one snapshot
-- can ever exist per attempt -- defense-in-depth on top of createAttempt's own idempotency
-- short-circuit, which already guarantees V6 runs at most once per attempt actually inserted.
--
-- source_attempt_id is authoritative decision-time provenance -- WHICH attempt V6 actually used,
-- fixed at the moment of decision -- never a cache of a value replay could otherwise compute. NULL
-- if and only if NO_SOURCE_ATTEMPT. Historical replay reads this column directly and must never
-- re-run findMostRecentCompletedAttempt(...) to rediscover it: that lookup is itself time-sensitive
-- (a later-completing attempt changes its answer), so re-deriving "the source attempt" at replay
-- time can silently substitute an attempt the original decision never saw.
CREATE TABLE core.diagnostic_selection_replay_input (
  id UUID PRIMARY KEY,
  destination_attempt_id UUID NOT NULL UNIQUE
    REFERENCES core.assessment_attempt(id) ON DELETE RESTRICT,
  source_attempt_id UUID REFERENCES core.assessment_attempt(id) ON DELETE RESTRICT,
  snapshot_contract_version VARCHAR(64) NOT NULL,
  relationship_authorized_count INTEGER NOT NULL,
  actionable_hypothesis_count INTEGER NOT NULL,
  candidate_probe_count INTEGER NOT NULL,
  participating_hypothesis_count INTEGER NOT NULL,
  step1_status VARCHAR(32),
  step2_status VARCHAR(32),
  activated BOOLEAN NOT NULL,
  -- One of V6FallbackReason's eight values; audit/integrity value only -- replay always recomputes
  -- the activation/fallback outcome from the persisted working set below and compares it against
  -- this column, rather than trusting it as the sole source of truth (Amendment 4 §T).
  fallback_reason VARCHAR(32),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- Frozen to exactly the one contract version this repository's only writer
  -- (DiagnosticSelectionReplayInputRepository) ever inserts today. A future _V2 contract widens
  -- this CHECK (the same DROP+ADD pattern already used elsewhere in this schema to widen an
  -- enumerated membership check, e.g. ck_assessment_attempt_item_reason) in the migration that
  -- introduces it -- never by relaxing this one to "any non-empty string." Replay itself (
  -- DiagnosticSelectionV6ReplayService) independently rejects an unsupported contract version
  -- rather than guessing compatibility from table shape; this CHECK is defense-in-depth at the
  -- point of insertion, not a substitute for that runtime check.
  CONSTRAINT ck_diagnostic_selection_replay_input_version
    CHECK (snapshot_contract_version = 'DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1'),
  CONSTRAINT ck_diagnostic_selection_replay_input_counts CHECK (
    relationship_authorized_count >= 0 AND actionable_hypothesis_count >= 0
    AND candidate_probe_count >= 0 AND participating_hypothesis_count >= 0
  ),
  -- Exactly one of "activated" / "has a fallback reason" -- never both, never neither.
  CONSTRAINT ck_diagnostic_selection_replay_input_activation
    CHECK (activated = (fallback_reason IS NULL)),
  CONSTRAINT ck_diagnostic_selection_replay_input_fallback_reason CHECK (
    fallback_reason IS NULL OR fallback_reason IN (
      'NO_SOURCE_ATTEMPT', 'NO_ACTIONABLE_HYPOTHESES', 'SINGLE_CANDIDATE_TOTAL',
      'STEP1_NOT_APPLICABLE', 'STEP1_INSUFFICIENT_EVIDENCE', 'FEWER_THAN_TWO_PARTICIPANTS',
      'STEP2_NOT_APPLICABLE', 'ALL_SCORES_ZERO'
    )
  )
);

COMMENT ON TABLE core.diagnostic_selection_replay_input IS
  'M2-ADR-034 Amendment 4: one row per DIAGNOSTIC_SELECTION_V6 attempt, regardless of outcome. '
  'Authoritative decision-time provenance for exact historical replay -- never re-derived from '
  'created_at, and never rediscovered by re-running a time-sensitive "most recent" query.';

COMMENT ON COLUMN core.diagnostic_selection_replay_input.source_attempt_id IS
  'WHICH source attempt V6 actually used, fixed at decision time. NULL iff NO_SOURCE_ATTEMPT. '
  'Replay must read this column, never re-run findMostRecentCompletedAttempt(...) -- that lookup '
  'can return a different, later attempt once more time has passed.';

CREATE INDEX idx_diagnostic_selection_replay_input_source_attempt
  ON core.diagnostic_selection_replay_input (source_attempt_id);

-- One row per candidate probe surviving in the exact V6-actionable working set the original
-- decision evaluated -- never the learner's whole exposure history, never every published
-- relationship, never a replay-time rediscovery. Carries the full, frozen, five-field
-- DiagnosticHypothesis identity per candidate (never collapsed to a partial key), so replay's own
-- Step-1/Step-2 reconstruction uses exactly the hypothesis identity Amendment 1's canonical order
-- and V6's own exact-identity de-duplication already require.
--
-- admission_ordinal is audit/debug legibility only, never authoritative: HYPOTHESIS_DISCRIMINATION_V1's
-- own frozen RANKING_ORDER (score DESC -> hypothesis canonical order -> probe UUID) is already
-- independent of input-list order, so replay re-derives ranking from RANKING_ORDER applied to this
-- (logically unordered) set, never from row-storage order.
--
-- scoreable is persisted even though every real V6 candidate is scoreable by construction today
-- (ProbeRelationshipRepository.itemsForObjective returns only verified, scoreable items) -- it
-- protects a future change to that invariant from silently corrupting historical replay unnoticed.
CREATE TABLE core.diagnostic_selection_replay_candidate_probe (
  id UUID PRIMARY KEY,
  replay_input_id UUID NOT NULL
    REFERENCES core.diagnostic_selection_replay_input(id) ON DELETE RESTRICT,
  admission_ordinal INTEGER NOT NULL,
  probe_item_version_id UUID NOT NULL
    REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT,
  scoreable BOOLEAN NOT NULL,
  trigger_item_version_id UUID NOT NULL
    REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT,
  trigger_objective_id UUID NOT NULL
    REFERENCES core.learning_objective(id) ON DELETE RESTRICT,
  relationship_type VARCHAR(32) NOT NULL,
  target_objective_id UUID NOT NULL
    REFERENCES core.learning_objective(id) ON DELETE RESTRICT,
  -- NULL for SAME_OBJECTIVE_CONFIRMATION/PREREQUISITE_VALIDATION; required for
  -- ROOT_CAUSE_PROBE/CONTRADICTION_CHECK -- the same authorization discipline
  -- core.diagnostic_probe_provenance already holds itself to.
  authorizing_relationship_id UUID
    REFERENCES core.diagnostic_probe_relationship(id) ON DELETE RESTRICT,
  CONSTRAINT ck_diagnostic_selection_replay_candidate_probe_ordinal CHECK (admission_ordinal > 0),
  CONSTRAINT ck_diagnostic_selection_replay_candidate_probe_type CHECK (
    relationship_type IN (
      'SAME_OBJECTIVE_CONFIRMATION', 'PREREQUISITE_VALIDATION', 'ROOT_CAUSE_PROBE', 'CONTRADICTION_CHECK'
    )
  ),
  CONSTRAINT ck_diagnostic_selection_replay_candidate_probe_authorization CHECK (
    (relationship_type IN ('ROOT_CAUSE_PROBE', 'CONTRADICTION_CHECK'))
    = (authorizing_relationship_id IS NOT NULL)
  ),
  -- Defense-in-depth against a bug re-inserting the identical row for the same admitted hypothesis.
  UNIQUE (replay_input_id, admission_ordinal, probe_item_version_id),
  -- The candidate probe item genuinely tags the hypothesis's own target objective.
  FOREIGN KEY (probe_item_version_id, target_objective_id)
    REFERENCES core.assessment_item_objective(item_version_id, objective_id) ON DELETE RESTRICT,
  -- The hypothesis's own trigger item genuinely tags its claimed trigger objective.
  FOREIGN KEY (trigger_item_version_id, trigger_objective_id)
    REFERENCES core.assessment_item_objective(item_version_id, objective_id) ON DELETE RESTRICT
);

COMMENT ON TABLE core.diagnostic_selection_replay_candidate_probe IS
  'M2-ADR-034 Amendment 4: the exact surviving candidate probes -- full five-field '
  'DiagnosticHypothesis identity each -- V6 actually evaluated for one replay-input snapshot. '
  'Never the learner''s whole exposure history; never a replay-time rediscovery.';

CREATE INDEX idx_diagnostic_selection_replay_candidate_probe_replay_input
  ON core.diagnostic_selection_replay_candidate_probe (replay_input_id);

-- Immutable once written -- append-only historical provenance, the same guarantee
-- trg_probe_provenance_guard gives core.diagnostic_probe_provenance -- insertable only while the
-- owning destination attempt is IN_PROGRESS, and (M2-ADR-034 Amendment 4 correction round) only for
-- an attempt whose own selection_policy is actually DIAGNOSTIC_SELECTION_V6. Without this second
-- check, a V6 replay snapshot could be attached to a V1-V5 attempt, producing exactly the
-- contradictory persisted state ("this V5 attempt has V6 replay provenance") DiagnosticSelectionV6ReplayService
-- must otherwise detect and reject at read time -- the database now makes that state
-- unrepresentable in the first place.
CREATE FUNCTION core.protect_diagnostic_selection_replay_input()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  attempt_status VARCHAR(16);
  attempt_selection_policy VARCHAR(48);
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'diagnostic selection replay-input snapshots are immutable' USING ERRCODE = '55000';
  END IF;
  SELECT status, selection_policy INTO attempt_status, attempt_selection_policy
    FROM core.assessment_attempt WHERE id = NEW.destination_attempt_id;
  IF attempt_status IS DISTINCT FROM 'IN_PROGRESS' THEN
    RAISE EXCEPTION 'a replay-input snapshot may only be recorded for an in-progress attempt'
      USING ERRCODE = '55000';
  END IF;
  IF attempt_selection_policy IS DISTINCT FROM 'DIAGNOSTIC_SELECTION_V6' THEN
    RAISE EXCEPTION
      'a DIAGNOSTIC_SELECTION_V6 replay-input snapshot may only be recorded for an attempt whose '
      'own selection_policy is DIAGNOSTIC_SELECTION_V6, not %', attempt_selection_policy
      USING ERRCODE = '55000';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_diagnostic_selection_replay_input_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.diagnostic_selection_replay_input
FOR EACH ROW EXECUTE FUNCTION core.protect_diagnostic_selection_replay_input();

-- Immutable once written. Also validates the two consistency facts no FK/CHECK above can express,
-- mirroring core.protect_probe_provenance's own discipline exactly:
--  1. the hypothesis's own trigger item was genuinely presented in the owning snapshot's own source
--     attempt (the same "Fact 1" check core.diagnostic_probe_provenance's own trigger enforces for
--     its source item) -- since source_attempt_id lives on the parent header row, this requires a
--     lookup, not a declarative FK. A header with source_attempt_id NULL (NO_SOURCE_ATTEMPT) can
--     never have a candidate row pass this check, which is exactly correct: no candidates should
--     ever exist for that case.
--  2. when authorizing_relationship_id is present, it must actually authorize this exact row --
--     exist, be PUBLISHED, and have the same trigger_objective_id/target_objective_id/
--     relationship_type this row itself claims. A row citing a DRAFT, retracted, or mismatched
--     relationship is exactly the inconsistent audit state this table exists to make
--     unrepresentable, the same standard core.diagnostic_probe_provenance already holds itself to.
CREATE FUNCTION core.protect_diagnostic_selection_replay_candidate_probe()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  header core.diagnostic_selection_replay_input%ROWTYPE;
  attempt_status VARCHAR(16);
  authorizing_row core.diagnostic_probe_relationship%ROWTYPE;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    RAISE EXCEPTION 'diagnostic selection replay candidate-probe snapshots are immutable'
      USING ERRCODE = '55000';
  END IF;
  SELECT * INTO header FROM core.diagnostic_selection_replay_input WHERE id = NEW.replay_input_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'replay_input_id % does not exist', NEW.replay_input_id USING ERRCODE = '23503';
  END IF;
  SELECT status INTO attempt_status FROM core.assessment_attempt WHERE id = header.destination_attempt_id;
  IF attempt_status IS DISTINCT FROM 'IN_PROGRESS' THEN
    RAISE EXCEPTION 'a candidate-probe snapshot may only be recorded for an in-progress attempt''s decision'
      USING ERRCODE = '55000';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM core.assessment_attempt_item ai
     WHERE ai.attempt_id = header.source_attempt_id AND ai.item_version_id = NEW.trigger_item_version_id
  ) THEN
    RAISE EXCEPTION
      'trigger_item_version_id % was not presented in replay_input %''s own source attempt',
      NEW.trigger_item_version_id, NEW.replay_input_id
      USING ERRCODE = '23514';
  END IF;
  IF NEW.authorizing_relationship_id IS NOT NULL THEN
    SELECT * INTO authorizing_row
      FROM core.diagnostic_probe_relationship
     WHERE id = NEW.authorizing_relationship_id;
    IF NOT FOUND
        OR authorizing_row.status <> 'PUBLISHED'
        OR authorizing_row.source_objective_id <> NEW.trigger_objective_id
        OR authorizing_row.target_objective_id <> NEW.target_objective_id
        OR authorizing_row.relationship_type <> NEW.relationship_type THEN
      RAISE EXCEPTION
        'authorizing_relationship_id % does not authorize this candidate-probe row -- it must exist, '
        'be PUBLISHED, and its source_objective_id/target_objective_id/relationship_type must exactly '
        'match', NEW.authorizing_relationship_id
        USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_diagnostic_selection_replay_candidate_probe_guard
BEFORE INSERT OR UPDATE OR DELETE ON core.diagnostic_selection_replay_candidate_probe
FOR EACH ROW EXECUTE FUNCTION core.protect_diagnostic_selection_replay_candidate_probe();
