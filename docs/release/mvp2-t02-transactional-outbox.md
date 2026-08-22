# M2-T02 transactional outbox evidence

- **Task:** M2-T02
- **Status:** Implemented; pending PR acceptance
- **Closes foundation:** TD-T18-01 persistence half
- **Decisions:** M2-ADR-002, M2-ADR-003, M2-ADR-004, M2-ADR-012, M2-ADR-015

## Implemented boundary

`RecommendationService.recommend()` now writes three records in the same Spring transaction:

1. the immutable authoritative `ledger.decision_record`;
2. the learner-facing `core.learning_recommendation`; and
3. one versioned `core.agent_work_outbox` item for the adaptation comparison.

The outbox uses a deterministic request identity derived from the source decision and a uniqueness
constraint on `(source_decision_id, agent_type, capability)`. Recomputing the same mastery snapshot
therefore returns the same decision and one logical work item. A rollback removes all three rows.

`AFTER_COMMIT` remains temporarily as an MVP-1 compatibility dispatch. It is no longer the durable
record: a process crash can delay work but cannot erase the persisted item. M2-T03 will replace that
listener with the PostgreSQL claim/lease dispatcher.

## Database guarantees

- V025 creates the additive `core.agent_work_outbox` table.
- The payload is checked against its indexed identity/correlation columns.
- Logical identity, payload, source decision, and creation time are immutable by trigger.
- Delivery state and retry/lease fields are present for T03.
- The Spring runtime may insert/read/update delivery state; the AI runtime receives no database
  privilege.
- The migration passes the previous-image expand/contract compatibility checker.

## Verification

- Unit, governance, and architecture categories: PASS.
- Fresh PostgreSQL migration through V025: PASS.
- Recommendation/outbox PostgreSQL suite: PASS, including atomic commit, forced rollback,
  idempotent recompute, strict payload linkage, and identity/payload mutation refusal.
- Focused real-PostgreSQL result: 15 tests, 0 failures, 0 skipped.

## Remaining boundary

This task proves durable creation, not delivery. Claim/lease concurrency, backoff, retry, terminal
failure, replay operations, and removal of the compatibility listener belong to M2-T03.
