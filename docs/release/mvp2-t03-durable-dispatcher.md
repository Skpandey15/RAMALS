# M2-T03 durable PostgreSQL dispatcher evidence

- **Task:** M2-T03
- **Status:** Implemented; pending PR acceptance
- **Decisions:** M2-ADR-003, M2-ADR-004, M2-ADR-011, M2-ADR-012
- **Standard:** `Production_Grade_Coding_and_Performance_Standards_React_Java_Python_v1.0`

## Implemented boundary

Spring now claims committed `core.agent_work_outbox` rows with one PostgreSQL
`FOR UPDATE SKIP LOCKED` statement. The claim transaction commits before the adaptation provider is
called. Completion, retry, and terminal transitions require both the claimed row and its lease
owner, so a stale worker cannot overwrite recovery by a new owner.

The temporary `AFTER_COMMIT` event listener and event contract have been removed. Recommendation
transactions only persist authoritative state and durable work; the scheduled dispatcher is the
single delivery path. Agent output remains comparison-only and cannot alter the deterministic
recommendation.

## Bounded failure and recovery policy

- Poll interval, batch size, lease, maximum attempts, and backoff bounds are external configuration.
- Defaults are a batch of 4, a 60-second lease, 5 attempts, and 1-to-60-second exponential backoff
  with deterministic 20% jitter.
- Configuration validation ensures the sequential batch's four 12-second provider deadlines plus
  margin fit inside the lease.
- Dependency and guard failures retry; caller-invalid failures and exhausted work become terminal.
- An expired claim is reclaimable. Every transition is owner-guarded.
- Terminal replay is explicit. It starts a fresh bounded attempt cycle, increments `replay_count`,
  and preserves `total_attempt_count` for lifetime operational accounting.
- Structured logs carry work, request, interaction, trace, attempt, outcome, and stable error code.
  Counters and duration timers expose completed, retry, terminal, and poll-failure outcomes.

## Verification

- `:learning-platform:check`: PASS.
- Focused dispatcher and AI boundary tests: PASS.
- Fresh PostgreSQL 18 migration through V026: PASS.
- Real PostgreSQL recommendation/outbox suite: PASS, including two concurrent dispatchers claiming
  distinct rows, expired-lease recovery, stale-owner rejection, terminal quarantine, explicit
  replay, and preserved lifetime attempt accounting.
- Compatibility listener removal is compilation- and architecture-suite verified.

The PostgreSQL-backed tests require the guarded `RAMALS_TEST_POSTGRES_*` environment used by CI;
they reset only the dedicated test database schemas.
