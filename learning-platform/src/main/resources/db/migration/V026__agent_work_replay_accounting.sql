-- M2-T03 / M2-ADR-011: preserve lifetime delivery accounting when an operator explicitly replays
-- terminal work. This is deliberately expand-only so the V025 image remains rollback-compatible.
-- Non-negative CHECK constraints are deferred until that image is outside the rollback window.
-- attempt_count remains the bounded current-cycle counter used by the dispatcher.
ALTER TABLE core.agent_work_outbox
  ADD COLUMN replay_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN total_attempt_count INTEGER NOT NULL DEFAULT 0;
