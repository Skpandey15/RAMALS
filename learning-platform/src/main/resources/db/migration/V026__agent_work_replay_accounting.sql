-- M2-T03 / M2-ADR-011: preserve lifetime delivery accounting when an operator explicitly replays
-- terminal work. attempt_count remains the bounded current-cycle counter used by the dispatcher.
ALTER TABLE core.agent_work_outbox
  ADD COLUMN replay_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN total_attempt_count INTEGER NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_agent_work_outbox_replays CHECK (replay_count >= 0),
  ADD CONSTRAINT ck_agent_work_outbox_total_attempts CHECK (total_attempt_count >= 0);
