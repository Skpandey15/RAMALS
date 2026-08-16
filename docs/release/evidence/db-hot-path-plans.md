# Performance evidence: database hot-path query plans

Archived for **M0-T23**. Captured with `performance/db/explain-analyze.sql` executed as the
least-privileged runtime role against a database populated by the M0-T22 validation run.

| | |
| --- | --- |
| Release | `v0.1.0-rc1` (commit `36645cf`) |
| PostgreSQL | 18.1, schema at Flyway `013` |
| Dataset | validation dataset — 1 learner, 5 evidence rows, 5 snapshots, 5 decisions, Kafka v1 curriculum (15 skills) |
| Role | `ramals_core_runtime` (SELECT/INSERT-only on ledger/audit) |
| Environment | **local validation host — informational, not the authoritative performance environment** |

## Result: every hot path is index-served

No sequential scan appears in any of the eight critical queries. Each resolves through the index
that was designed for it, including the partial one-active-attempt index.

| # | Query | Access path | Execution |
| --- | --- | --- | --- |
| Q1 | Latest mastery snapshot | `idx_mastery_snapshot_latest` (Index Scan) | 0.049 ms |
| Q2 | Mastery map, latest per skill | `idx_mastery_snapshot_latest` + `skill_pkey` | 0.100 ms |
| Q3 | Evidence for recomputation | `idx_evidence_learner_skill` | 0.526 ms |
| Q4 | Curriculum skill graph load | `idx_skill_version_curriculum_order` + `skill_pkey` | 0.115 ms |
| Q5 | Active attempt lookup | `uq_assessment_attempt_one_active` (partial index) | 0.019 ms |
| Q6 | Current recommendations per skill | `idx_learning_recommendation_current` | 0.065 ms |
| Q7 | Latest progression statuses | `idx_mastery_snapshot_latest` | 0.042 ms |
| Q8 | Open learning session | `uq_learning_session_one_open` (partial index) | 0.025 ms |

Representative plan (Q1):

```
Index Scan using idx_mastery_snapshot_latest on mastery_snapshot
  (cost=0.14..8.17 rows=1) (actual time=0.019..0.019 rows=1.00 loops=1)
  Index Cond: ((learner_id = ...) AND (skill_id = ...) AND (curriculum_version_id = ...))
Planning Time: 0.932 ms
Execution Time: 0.049 ms
```

## What this evidence does and does not establish

**Establishes:** the schema's indexing strategy is correct — the designed access path is the one the
planner actually chooses for every hot-path query, at both the mastery/evidence read paths and the
partial-index invariants (one active attempt, one open session). A regression that dropped an index
or changed a predicate would surface here as a sequential scan.

**Does not establish:** latency or throughput under load. Timings come from a small dataset on a
local host with a warm cache; they are **not** an SLO and must not be quoted as one. Plan shapes can
change as tables grow, so this should be recaptured against a production-scale dataset on the
authoritative environment.

Risk **R1** (no calibrated performance baseline) therefore remains open. This evidence narrows it —
the DB benchmark dimension of M0-T20 is now executed and archived — but the k6 latency/throughput
baseline on fixed-spec hardware is still required before any performance claim.

## Reproducing

```bash
# Populate a database via the validation run, then:
psql -U ramals_core_runtime -d ramals_test -f performance/db/explain-analyze.sql
# or, for a timestamped capture:
RAMALS_DB_URL=postgresql://ramals_core_runtime@localhost:5432/ramals \
  ./performance/db/run-db-benchmarks.sh
```
