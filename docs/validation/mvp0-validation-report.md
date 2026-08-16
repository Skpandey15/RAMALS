# MVP-0 End-to-End Validation Report

Archived evidence for **M0-T22**. Every claim below was produced by an executed run, not asserted
from design intent. Re-running the commands in [Reproducing](#reproducing) regenerates it.

| | |
| --- | --- |
| Validated commit | `1458d32` (first fully green release pipeline) |
| Database | PostgreSQL 18.1 (container), schema at Flyway version **013** |
| Backend suite | **200 tests across 56 classes — 0 failures, 0 errors, 0 skipped** |
| Frontend suite | 12 tests — lint clean, build succeeds |
| Environment | local validation host; **not** the authoritative fixed-spec performance environment |

## 1. Executed suites

| Suite | Scope | Result |
| --- | --- | --- |
| Functional | domain services, API contracts, webmvc role/ownership | pass |
| Security | negative authorization, IDOR/BOLA, rate limiting, headers, DB privilege, secret hygiene | pass |
| Concurrency | monotonic mastery versions, one-active-attempt, optimistic session transitions | pass |
| Migration | fresh install V001→head, forward-upgrade validation, per-migration content contracts | pass |
| Recovery | restart/state persistence, backup/restore, forced-failure containment | pass |
| Performance | harness structurally verified; baseline not yet captured on the authoritative environment | partial — see §5 |

## 2. Full Kafka diagnostic vertical slice

`MvpZeroValidationTests.fullKafkaDiagnosticVerticalSlice` drives one learner through the entire
deterministic loop against a freshly migrated database:

1. **Session** started (`ACTIVE`), durable and resumable.
2. **Progression** before evidence — `KAFKA_BROKER` `ELIGIBLE`, `KAFKA_TOPIC` `LOCKED` (prerequisite gate).
3. **Attempt** created idempotently, items served **without answer keys**.
4. **Submission** scored deterministically → attempt `COMPLETED`, per-skill scores returned.
5. **Evidence** appended immutably, carrying its `interactionId`.
6. **Mastery snapshot** at `aggregate_version = 1`, score `1.0000`, with evidence confidence and
   `WEIGHTED_MASTERY_V1` recorded.
7. **Recommendation + DecisionRecord** produced and linked.
8. **Session** transitioned to `COMPLETED`.

**Result: pass.** One learner can complete the full deterministic vertical slice.

## 3. Drills

### 3.1 Safe retry drill — pass
Replaying attempt creation with the original `Idempotency-Key` returned the **same** attempt
(`created = false`); re-submitting the completed attempt returned the original result. Evidence,
mastery-snapshot and decision-record counts were **identical before and after**, so a retry produced
no duplicate logical state.

### 3.2 Service restart / recovery — pass
A completely fresh object graph (nothing cached in memory) read back learner, mastery, recommendation
and progression state from durable storage. A new session after completion started cleanly rather
than resuming a stale one.

### 3.3 Historical decision reconstruction — pass
The archived `DecisionRecord` is self-describing: its cited snapshot, denormalized score/confidence,
and every algorithm and policy version match the stored snapshot. **Replaying
`RecommendationPolicy` against the cited snapshot reproduced the recorded action**, and the
underlying evidence remains present and immutable.

### 3.4 Failure-trace drill using only interactionId — pass
Starting from nothing but the support code a learner would read off the error banner, the drill
located the correlated **decision records, evidence, mastery snapshots and session transitions** —
every consequential write is reachable by `interactionId` alone, without reproducing the failure.

### 3.5 Forced database failure — pass
- The least-privileged runtime identity was **denied** an attempt to rewrite provenance
  (`SQLSTATE 42501`), confirming immutability holds at the privilege layer.
- A query against a nonexistent relation surfaced as a contained `DataAccessException`; the
  learner's authoritative state was verified intact afterwards.

## 4. Fresh install, upgrade path and backup/restore

**Fresh install:** the validation run migrates an empty database from `V001` to head (≥13
migrations) before any assertion. **Upgrade path:** `PostgresMigrationIntegrationTests` installs a
baseline, then applies the forward-upgrade set and validates.

**Backup/restore drill** (`scripts/validation/backup-restore-drill.sh`), executed against the
populated database:

```
flyway=013 evidence=5 snapshots=5 decisions=5
ok   flyway version preserved (013)
ok   evidence rows preserved (5)
ok   mastery snapshots preserved (5)
ok   decision records preserved (5)
ok   latest mastery pointer intact (1)
ok   decision -> snapshot chain unbroken (0)
ok   evidence -> learner chain unbroken (0)
ok   foreign keys restored (present)
```

Restore validated **foreign keys, Flyway version, append-only provenance chains and latest mastery
pointers** — not merely a zero exit code.

## 5. Known gaps — deliberately not claimed as passing

1. **Performance baseline not captured.** The T20 harness is structurally verified, but no baseline
   has been run on the authoritative fixed-spec environment. MVP-0 therefore has **no calibrated
   latency/throughput numbers**, only engineering objectives. This is by design (the matrix forbids
   promoting noisy shared-runner numbers to an SLA) but means the performance Definition of Done is
   *not* satisfied by this report.
2. **Deployment not exercised against a live environment.** The release pipeline publishes
   attested, scanned images and the deploy controller's state machine is proven in isolation, but
   `deploy/desired-version.json` still holds placeholder digests — no real pull-based deployment has
   run. Promoting the green build is **T23** work.
3. **Keycloak-issued token path untested end to end.** Authorization is validated with mock JWTs and
   the realm now mints the required claims, but no test drives a token actually issued by a running
   Keycloak.
4. **Environment is not the authoritative one.** All results above come from a local validation host.

## Reproducing

```bash
# 1. Start PostgreSQL
docker run -d --name ramals-validation -e POSTGRES_DB=ramals_test \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres-test-only \
  -p 5432:5432 postgres:18.1-alpine

# 2. Full backend suite including the validation drills
export RAMALS_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/ramals_test
export RAMALS_TEST_POSTGRES_ADMIN_USER=postgres
export RAMALS_TEST_POSTGRES_ADMIN_PASSWORD=postgres-test-only
export RAMALS_TEST_POSTGRES_ALLOW_RESET=true
./gradlew clean test

# 3. Backup/restore drill against the populated database
PGPASSWORD=postgres-test-only ./scripts/validation/backup-restore-drill.sh ramals_test

# 4. Frontend suite
cd web-ui && npm ci && npm run lint && npm test && npm run build
```
