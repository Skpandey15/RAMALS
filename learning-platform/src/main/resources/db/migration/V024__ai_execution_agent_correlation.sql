-- M1-T17 slice 3 / M1-ADR-011 / Observability HLD §9: carry agent correlation into the durable
-- execution record.
--
-- The support pivot the HLD describes runs interactionId -> business event -> traceId -> the run
-- that produced it, and then into durable provenance. Until now it stopped at the database:
-- core.ai_execution recorded prompt_version and nothing else about which prompt or which run.
--
-- Two questions it could not answer, both of which have real answers now:
--
--   * whether an execution generated an assessment item or evaluated a learner's response. Both
--     prompts share ASSESSMENT_PROMPT_V1, and evaluation/baselines.json already treats them as
--     different agents with different golden datasets -- so the row could not distinguish two
--     things the rest of the system does.
--   * which orchestrated run produced the row, so a log line naming an agentRunId could be joined
--     to the execution it accounts for.
--
-- This is the EXPAND half of an expand/contract change, and it is deliberately the whole of it.
-- Both columns are nullable with no default and no backfill:
--
--   * nullable, because the previously released image inserts without them. A rollback restores
--     that image against this schema, and a NOT NULL column would make every insert fail -- turning
--     a bad release into an outage at the moment somebody is recovering from one.
--   * not backfilled, because rows written before this migration genuinely have no agent run. A
--     backfilled placeholder would be indistinguishable from a real value later, and the whole
--     point of the column is that it identifies something.
--
-- There is no contract half to schedule. Nothing is being replaced: these are new facts about
-- executions, not a new spelling of an old one.
ALTER TABLE core.ai_execution
  ADD COLUMN prompt_template_id VARCHAR(64),
  ADD COLUMN agent_run_id VARCHAR(64);

-- Widths match the contract's maxLength for both fields, so a value the AI plane may legally send
-- cannot be silently rejected here. ContractIdentifierWidthTests holds that correspondence.

COMMENT ON COLUMN core.ai_execution.prompt_template_id IS
  'Which prompt produced the proposal (M1-ADR-011). Null for executions recorded before V024, and '
  'for failures where no proposal was produced.';
COMMENT ON COLUMN core.ai_execution.agent_run_id IS
  'The orchestrated agent execution behind this record (Observability HLD 9). Null for executions '
  'recorded before V024, and for failures with no run.';

-- Supports the pivot the columns exist for: given an agentRunId from a log line, find the execution
-- it accounts for. Created without CONCURRENTLY because Flyway runs each migration in a
-- transaction; at MVP-1 volumes the exclusive lock is momentary. A table large enough for that to
-- matter needs the index created outside Flyway, which is a decision to make with the data in front
-- of you rather than in advance.
CREATE INDEX ix_ai_execution_agent_run ON core.ai_execution(agent_run_id)
  WHERE agent_run_id IS NOT NULL;
