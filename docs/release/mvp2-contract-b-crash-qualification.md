# Contract B — crash/recovery qualification

- **Status:** Executed 2026-08-28 against the merged durable lifecycle (`#181`).
- **Scope:** Proof of runtime durability and recovery behaviour. No new architecture, no route
  activation, no provider contact.
- **Harness:** `ContractBCrashQualificationIntegrationTests`, real PostgreSQL, ten kill points plus
  three sweep-reachability cases and three cross-cutting invariants.
- **Does not satisfy:** the Definition of Done's real-provider lost-acknowledgement matrix. That
  requires inducing a lost acknowledgement against Anthropic and has not been run.

## What "a crash" means here

Every kill point throws `SimulatedProcessDeath`, which is an `Error` rather than an exception. That
choice is the whole harness. The lifecycle's `catch (RuntimeException …)` handlers exist to
*classify failures*, and a dead process classifies nothing — anything catchable would let the service
record an outcome, which is precisely what a crash cannot do. The `Error` unwinds through every
handler, leaving exactly what had been committed to PostgreSQL at that instant.

Every recovery then builds **new repositories, a new store, a new adoption boundary and a new
lifecycle service** over the same database. Nothing crosses the kill point except PostgreSQL, which
is the guarantee under test: M2-ADR-017 §1 makes the platform the sole holder of durable state, so if
a fresh instance cannot reconstruct the execution from the database, the claim is false.

## Summary matrix

| # | Kill point | Durable state before restart | Provider calls | Recovery action | Final state | Duplicate risk | Expected result |
|---|---|---|---|---|---|---|---|
| K1 | After `ADMITTED`, before submit | `ADMITTED`, no identity | 0 | Submit, fenced | `SUBMITTED` | **None** — nothing was sent | PASS |
| K2 | After write-ahead `SUBMITTED`, before the call returned | `SUBMITTED`, `submitted_at` set, no identity | 1 | Record indeterminate | `UNKNOWN_TERMINAL` | **Orphaned provider execution**, never a duplicate submission | PASS |
| K3 | Provider accepted, dead before identity stored | `SUBMITTED`, no identity | 1 | Record indeterminate | `UNKNOWN_TERMINAL` | Same as K2 — indistinguishable in the database, deliberately | PASS |
| K4 | Identity stored, dead before work item enqueued | `SUBMITTED` + identity, no work row | 1 | Sweep re-queues from the durable row, then polls | `SUCCEEDED` | None | PASS |
| K5 | Dead mid-poll (`RUNNING`/`RECONCILING`) | `RUNNING`/`RECONCILING` + identity | 1 | Re-poll the same identity | `SUCCEEDED` | None — identity reused, never re-established | PASS |
| K6 | Provider terminal, dead before retrieval | `RECONCILING` + identity, no result | 1 | Retrieve, validate, seal, store | `SUCCEEDED` | None | PASS |
| K7 | Result retrieved, dead before the encrypted write | `RECONCILING`, **no result row** | 1 | Retrieve again, store | `SUCCEEDED` | None — no partial row, no plaintext | PASS |
| K8 | Ciphertext committed, dead before terminal | `RECONCILING` + **result row present** | 1 | Retrieve, store is a no-op, mark terminal | `SUCCEEDED` | None — one result row, `ON CONFLICT DO NOTHING` | PASS |
| K9 | Terminal with stored result, dead before adoption | `SUCCEEDED` + result row | 1 | Adopt | `SUCCEEDED`, result deleted | None | PASS |
| K10 | Dead inside the adoption transaction | `SUCCEEDED` + result row, decision **rolled back** | 1 | Adopt again | `SUCCEEDED`, result deleted, one `ADOPTED` entry | None — decision and result share one fate | PASS |

Provider-call counts are read from the fake's own counter, not inferred. "Exactly one provider
submission" is a claim about how many times the provider was contacted, and the only way to test it
is to count.

## Invariants asserted across the matrix

| Invariant | How it is proven |
|---|---|
| No duplicate provider submission | Submission counter asserted at every kill point; K1 drives three separate instances at one admitted row and still counts 1 |
| No fabricated success or failure | Every non-terminal crash asserts `state.terminal()` is false; no kill point produces `SUCCEEDED` or `FAILED` without the provider having said so |
| State reconstructable from PostgreSQL | Every recovery uses new repositories, store, adoption boundary and service |
| Provider identity reused when known | K4/K5/K6 assert the identity is unchanged and that recovery made no submission |
| Reconciliation idempotent | Five replacements on a finished execution: retrieval count unchanged, one result row, one `RESULT_STORED` entry |
| Result never persisted as plaintext | Byte 0 asserted as the envelope version; canary absent from all four Contract B tables after crashes on both sides of the write |
| Terminal result not duplicated | K8 asserts exactly one result row after a recovery that stores again |
| Adoption atomic | K10 asserts the result survives a rolled-back decision and the decision is absent — never one without the other |
| Contract A unchanged | Row counts on `ai_execution`, `ai_execution_event`, `ai_execution_dispatch` are zero; `purge_expired_ai_executions` still runs |

## Three defects the qualification found

None were visible by review. All three are fixed in the same change, and each has a test that fails
against the old behaviour — verified by restoring the defect, not assumed.

1. **K8 recovery was a crash loop.** A replacement retrieved the result and inserted it again,
   violating the primary key. The result write is now `ON CONFLICT (request_id) DO NOTHING`. Keeping
   the first row is correct rather than merely convenient: a result is immutable once written
   (M2-ADR-018 §3), so the existing row is authoritative by definition.

2. **Adoption wrote two ledger entries.** `core.adopt_ai_execution_result` records the adoption
   inside the transaction, and the service wrote a second one *outside* it. Redundant, and worse,
   non-atomic — a process dying between the commit and that line would leave adoption evidence that
   disagreed with itself. The out-of-transaction write is removed.

3. **Stranded executions were invisible to the sweep.** The worker leased only from
   `ai_reconciliation_work`, so K2, K3 and K4 all produced rows that were durably recoverable and
   that nothing would ever drive. `ProviderExecutionRepository.reconcilable(…)` existed for exactly
   this and was dead code. The worker now sweeps two populations before its lease loop: acknowledged
   executions missing a work item (re-queued immediately), and sent-but-unacknowledged submissions
   older than a grace period (queued to be recorded `INDETERMINATE`).

The grace period is load-bearing rather than defensive. A submission in progress right now is
indistinguishable from one whose acknowledgement was lost, so only elapsed time separates them, and
terminating a live execution would be worse than the ambiguity it resolves. A test asserts a young
row is left alone.

## Unsupported recovery windows

These are properties of the provider contract and of what is implemented, not of this harness.

1. **A lost acknowledgement is not recovered — it is recorded.** K2 and K3 end at
   `UNKNOWN_TERMINAL`. The provider may hold a live execution RAMALS cannot name, and that execution
   is **orphaned**: it will run, it will bill, and nothing will adopt it. Recovering it needs batch
   enumeration matched on `custom_id`, which is **not implemented and is not claimed**.

2. **A duplicate provider execution created by a lost acknowledgement is undetectable.** V037's
   unique index prevents two RAMALS requests claiming one batch id; it cannot see a batch RAMALS
   never recorded. Definition-of-Done criterion 3 — *duplicates detected and auditable* — is
   therefore **not yet satisfiable**, and this qualification does not claim it.

3. **No real provider was contacted.** Everything above runs against a scripted fake. Provider
   behaviour under a genuine timeout, a genuine partial response, or a genuine 29-day expiry is
   unqualified.

4. **A commit that reached PostgreSQL while its acknowledgement was lost is not modelled.** The
   harness kills between statements, not inside one. That window is real and is a property of the
   JDBC boundary rather than of Contract B.

5. **Cancellation is unqualified.** The Python adapter implements it; no lifecycle path calls it.

6. **Concurrency across processes is not exercised.** The lease exists and its expiry is modelled,
   but two genuinely simultaneous workers on one execution were not run.

## How to run

```bash
RAMALS_TEST_POSTGRES_URL=jdbc:postgresql://localhost:5432/ramals_test \
RAMALS_TEST_POSTGRES_ADMIN_USER=postgres \
RAMALS_TEST_POSTGRES_ADMIN_PASSWORD=postgres-test-only \
RAMALS_TEST_POSTGRES_ALLOW_RESET=true \
./gradlew --no-daemon --max-workers=1 clean check
```

Contract B remains disabled throughout: `ramals.contract-b.enabled` is false, the reconciliation
worker is off, and the AI plane's durable endpoints are absent unless
`RAMALS_AI_DURABLE_EXECUTION_ENABLED` is set.
