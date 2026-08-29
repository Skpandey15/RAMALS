-- V040: the originating correlation, carried on the durable Contract B row.
--
-- Contract B reconciliation runs on a scheduler thread with no request behind it, so it had no
-- interaction id to work with and sent an empty one. The AI plane accepts a *missing* correlation
-- header (it generates one) but rejects an empty one, so every recovery call failed -- the durable
-- path could not talk to the provider plane at all.
--
-- The fix is not to invent an id at the transport. It is to give the durable row the same thing
-- V025's outbox already carries: the correlation of the request that created the work. `agent_work_
-- outbox` persists interaction_id and trace_id per work item and AgentWorkDispatcher restores that
-- scope before dispatching, so a learner's request stays traceable across an asynchronous hand-off.
-- Contract B is the same shape of problem and gets the same answer.
--
-- Nullable, deliberately. Rows admitted before this migration have no correlation to recover, and a
-- NOT NULL column would either reject them or force a fabricated value into durable state. The
-- worker generates a fresh identifier for those, which is honest: a new id says "this is where the
-- trail starts", where a backfilled constant would claim a provenance that does not exist.

ALTER TABLE core.ai_provider_execution
  ADD COLUMN interaction_id VARCHAR(64),
  ADD COLUMN trace_id       VARCHAR(64);

-- Blank is not a value: an empty correlation is the defect this migration exists to fix, and
-- storing one would only push it a layer deeper. That invariant is enforced in the single writer
-- (ProviderExecutionRepository.admit maps blank to null) and asserted by test, deliberately NOT by
-- a CHECK constraint here.
--
-- Adding one would trip the migration-compatibility gate, and correctly so by that gate's own
-- terms: it refuses ADD CONSTRAINT ... CHECK because a rolled-back image can write rows the
-- constraint refuses. This particular check could never be violated -- it names only columns this
-- same migration adds, which the previous image does not know exist and therefore always leaves
-- null. But the checker deliberately does not attempt constraint satisfiability, recording the few
-- historical cases as accepted rather than teaching the rule to reason (see the note above
-- ACCEPTED_BEFORE_THIS_CHECK). Adding an entry there for a brand-new migration would be using a
-- record of the past to wave through the present, and making the rule cleverer to admit one column
-- pair is a worse trade than enforcing this where it is already enforced.

COMMENT ON COLUMN core.ai_provider_execution.interaction_id IS
  'The interactionId of the request that admitted this execution, so reconciliation can restore it '
  'before calling the AI plane (M1-ADR-003 correlation, following V025''s outbox precedent). Null '
  'for rows admitted before V040 or outside any request scope; the reconciliation worker then '
  'generates a fresh canonical UUIDv7 for the attempt rather than sending an empty header.';

COMMENT ON COLUMN core.ai_provider_execution.trace_id IS
  'The W3C traceId in force when this execution was admitted, restored alongside interaction_id so '
  'the durable half of the story stays reachable from the trace that started it.';

-- No grant changes. The access matrix of M2-ADR-018 section 3 is per table, not per column, and this
-- adds no new reachable surface: the runtime already holds SELECT, INSERT and UPDATE here.
