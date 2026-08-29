# Contract B — operational runbook

- **Covers:** the durable recoverable AI execution path (M2-ADR-016, M2-ADR-017, M2-ADR-018,
  M2-ADR-019, M2-ADR-020) as it stands at `V040`.
- **Audience:** whoever is on call for the RAMALS platform. Assumes database access as the
  migration role and the ability to read application logs; assumes no Contract B knowledge.
- **Companion to** [`observability-runbook.md`](observability-runbook.md), which predates Contract B
  and does not cover it. This document does not replace it.
- **Status of the path itself:** both switches are **off by default**. `ramals.contract-b.enabled`
  (the public route) and `ramals.contract-b.reconciliation.enabled` (the background worker) are
  independent, and the route must stay off until the Definition of Done permits activation. If you
  are reading this because something is misbehaving in a deployment, **check both flags first** —
  the most likely explanation for "Contract B is doing nothing" is that it is switched off, which is
  the intended state today.

## The one thing to know before touching anything

**Reconciliation never submits.** Not on timeout, not on a lost acknowledgement, not when an
execution has been stuck for a day. If you find yourself reaching for a way to "retry the
submission", stop: the provider offers no replay-safe admission (M2-ADR-016 §3), so a resubmission
of an execution whose identity RAMALS does not know is the one action that can duplicate live,
billable work. Every procedure below is written to avoid that, and none of them ends in "resubmit".

The corollary: an execution that ends `UNKNOWN_TERMINAL` is **not** a failure to be retried. It is a
recorded, honest statement that RAMALS could not establish what happened. Re-driving it is an
operator decision with real consequences, described under [`UNKNOWN_TERMINAL`](#unknown_terminal).

## State machine

```
ADMITTED ─▶ SUBMITTED ─▶ RUNNING ⇄ RECONCILING ─▶ SUCCEEDED | FAILED | CANCELLED
                                                └▶ UNKNOWN_TERMINAL
```

`SUCCEEDED`, `FAILED`, `CANCELLED` and `UNKNOWN_TERMINAL` are terminal. Nothing else is.

An execution in `SUBMITTED` **with no `provider_execution_id`** is the lost-acknowledgement case: the
write-ahead claim committed and the provider was called, but no identity came back. That row is the
subject of most of this runbook.

## Where to look

Every question below is answered from four tables. None of them contains model output except
`ai_execution_result`, which is ciphertext.

| Table | What it tells you |
| --- | --- |
| `core.ai_provider_execution` | One row per request: state, identity, fence, usage, timestamps, correlation |
| `core.ai_execution_transition` | Append-only ledger: every state change with actor, fence and reason |
| `core.ai_provider_execution_observation` | Every provider execution *discovered* for a request, adopted or not, with usage |
| `core.ai_reconciliation_work` | The queue: lease owner, expiry, attempt count, next attempt |

Start here for any incident:

```sql
SELECT request_id, state, provider_execution_id, submit_fence,
       admitted_at, submitted_at, terminal_at, interaction_id
  FROM core.ai_provider_execution
 WHERE request_id = :request_id;

SELECT id, from_state, to_state, actor, fence, reason, recorded_at
  FROM core.ai_execution_transition
 WHERE request_id = :request_id
 ORDER BY id;
```

The ledger is the forensic record and is designed to be read in order. `reason` is a short code, not
prose; the codes are listed against each procedure below.

---

## Reconciliation worker operation

**What it does.** Every `interval-ms` (default 30 s) the worker: re-queues executions that are
recoverable but have no work item; leases up to `batch-size` (default 20) due items; and drives each
one forward. A pass carries a single **inspection budget** (default 15) shared across every orphan in
it — see [performance characterization](../release/mvp2-contract-b-performance-characterization.md).

**Configuration**, all under `ramals.contract-b.reconciliation`:

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Whether the worker runs at all |
| `interval-ms` | `30000` | Pass cadence |
| `lease-ms` | `60000` | How long a lease is held |
| `backoff-ms` | `30000` | First backoff after a failed attempt; doubles from here |
| `max-backoff-ms` | `900000` | Backoff ceiling — keeps retries meaningful inside the 26 h horizon |
| `backoff-jitter-ms` | `5000` | Random spread, so a fleet does not re-converge on the provider |
| `batch-size` | `20` | Executions leased per pass |
| `unacknowledged-grace-ms` | `300000` | How long "sent, unacknowledged" is left alone before enumeration |
| `inspection-budget-per-pass` | `15` | Provider result inspections one pass may spend |

Under `ramals.contract-b.recovery`: `search-skew-ms` (1 h), `search-horizon-ms` (26 h),
`max-inspections-per-search` (50).

**Health check.** The worker is healthy if work items are draining:

```sql
SELECT count(*) FILTER (WHERE next_attempt_at <= now())            AS due_now,
       count(*) FILTER (WHERE lease_owner IS NOT NULL)             AS leased,
       max(attempts)                                               AS worst_attempts
  FROM core.ai_reconciliation_work;
```

`due_now` growing without bound, or `worst_attempts` climbing steadily, means the worker is running
but not making progress — go to [provider outage](#provider-outage--unreachable-provider).
`due_now` growing with `leased = 0` and no log lines means it is not running: check `enabled`.

**There are no metrics and no alerting.** This is recorded, accepted debt (W4). Everything in this
runbook is queried from the database or read from logs. Do not expect a dashboard.

---

## Provider outage / unreachable provider

**Symptom.** `contract B enumeration failed [requestId=…, error=…]` at WARN, or repeated
non-terminal passes with rising `attempts`.

**What the system does on its own.** An unreachable provider says nothing about whether an orphan
exists, so it is never terminal on its own. Attempts back off exponentially with jitter, capped at
15 minutes, and resume when the provider returns. **No action is required for a transient outage.**

**When to intervene.** If the outage outlasts the 26-hour search horizon, affected orphans become
`UNKNOWN_TERMINAL` with reason `SEARCH_HORIZON_EXHAUSTED` — recorded as *horizon-exhausted*, not as
*nothing found*, deliberately: one means we looked and there was nothing, the other means we could
not see. Treat those as [`UNKNOWN_TERMINAL`](#unknown_terminal).

**Rate limiting is not an outage** and is handled separately — see
[cost/token anomaly](#costtoken-anomaly). The log line says so explicitly:
`contract B reconciliation stopped: the provider is rate limiting`.

---

## Lost acknowledgement / orphan recovery

**Symptom.** A row in `SUBMITTED` with `provider_execution_id IS NULL` and `submitted_at` set.

```sql
SELECT request_id, submitted_at, submit_fence,
       now() - submitted_at AS age
  FROM core.ai_provider_execution
 WHERE state = 'SUBMITTED' AND provider_execution_id IS NULL
 ORDER BY submitted_at;
```

**What the system does.** After `unacknowledged-grace-ms` (5 min — long enough that a submission
still in flight is not mistaken for a lost one), the sweep queues the row and reconciliation
enumerates provider batches over `submitted_at ± 1 h`, correlating on the request's `custom_id`
(which *is* the server-derived idempotency key). Four outcomes, and only one adopts anything:

| Outcome | Ledger reason | Meaning |
| --- | --- | --- |
| One match | `IDENTITY_RECOVERED` | Adopted under fence; ordinary reconciliation resumes |
| Zero matches, complete search | `SEARCH_FOUND_NOTHING` | Conclusive. Nothing was created. Terminal |
| More than one | `DUPLICATE_PROVIDER_EXECUTION` | See [MULTIPLE](#multiple--duplicate-provider-execution) |
| Search did not finish | *(stays non-terminal)* | See [INCONCLUSIVE](#inconclusive) |

Recovery is logged at WARN and is expected to be rare:
`contract B recovered a lost acknowledgement [requestId=…, providerExecutionId=…]`.

**Operator action: none, normally.** This path is automatic and is the mechanism criterion 3 exists
to provide. Intervene only if the same request oscillates or the age exceeds the horizon.

---

## `INCONCLUSIVE`

**Not an error, and not an answer.** It means the search could not cover the window: a candidate
batch was still processing (its results do not exist yet), its results were unreadable, or a bound
or the pass budget was reached. The honest response is to try again later, which the worker does.

**Symptom.** `contract B enumeration inconclusive [requestId=…, uninspectable=…, limitReached=…,
inspected=…, alreadyRuledOut=…]` at WARN, repeatedly, for one request.

**What to check.** `alreadyRuledOut` should climb across passes and then hold steady — that is the
durable memo working. `uninspectable` staying at 1 for minutes usually means the orphan itself is
still running at the provider, which is exactly when `INCONCLUSIVE` is the correct answer.

**When it stops being benign.** If `limitReached` is `pages`, the window holds more than 1000
batches and the listing bound is being hit — a workspace-density problem, recorded as a limit rather
than a guarantee in the performance characterization. Escalate; do not widen the bound casually.

**Operator action: wait.** `INCONCLUSIVE` before the 26-hour horizon needs nothing. At the horizon it
becomes `UNKNOWN_TERMINAL` with `SEARCH_HORIZON_EXHAUSTED`.

---

## `MULTIPLE` / duplicate provider execution

**This is the case that requires you.** Two or more provider executions carry one request's
`custom_id`. RAMALS records every one of them, adopts **none**, and marks the execution
`UNKNOWN_TERMINAL` with reason `DUPLICATE_PROVIDER_EXECUTION`.

**Why nothing is chosen automatically.** Adopting the first attributes a learner's diagnosis to an
arbitrary execution; adopting the newest silently prefers a duplicate over the original. No rule
makes the choice correct, because the information needed does not exist on RAMALS' side. This is a
deliberate refusal, not a gap (M2-ADR-020 §4).

**Symptom.**

```
contract B found N provider executions for one request [requestId=…, customId=…]
```

**Investigate:**

```sql
SELECT provider_execution_id, custom_id, outcome, discovered_by,
       input_tokens, output_tokens, provider_created_at, provider_ended_at, observed_at
  FROM core.ai_provider_execution_observation
 WHERE request_id = :request_id
 ORDER BY observed_at;
```

Every discovered execution appears here with its usage, so the duplicate is **costed**, not merely
noticed. That is what satisfies criterion 8.

**Operator decision.** Determine from the observations which execution is authoritative — usually
the earliest `provider_created_at`, but that is a judgement about what happened, not a rule the
system may apply. Then either:

- accept the recorded `UNKNOWN_TERMINAL` and let the learner-facing workflow re-request, which is
  safe because adoption never happened; or
- adopt manually, which is a deliberate, audited act. There is no supported procedure for this
  today, and inventing one under incident pressure is how a wrong diagnosis reaches a learner.
  **Prefer the first option.**

Record the decision. The observation rows are append-only and never purged, so the evidence survives.

---

## `UNKNOWN_TERMINAL`

**What it means.** RAMALS could not establish what happened, and says so rather than guessing. It is
the honest end state, not a bug.

**Distinguish by ledger reason** — they mean genuinely different things:

| Reason | What actually happened | Provider execution may exist? |
| --- | --- | --- |
| `SUBMIT_AMBIGUOUS` | The submit call did not complete readably | **Yes** |
| `SUBMIT_ACK_UNUSABLE` | A 2xx with no usable acknowledgement body | **Yes** |
| `SUBMIT_UNCLASSIFIED` | An unclassified failure after the call began | **Yes** |
| `SEARCH_FOUND_NOTHING` | Enumeration covered the window; nothing carried the key | **No** |
| `SEARCH_HORIZON_EXHAUSTED` | Enumeration never completed before 26 h | **Unknown** |
| `DUPLICATE_PROVIDER_EXECUTION` | Two or more found | **Yes, more than one** |
| `NO_PROVIDER_IDENTITY` | No `submitted_at` to anchor a search | **Unknown** |
| `SUBMIT_FENCE_LOST` | Another worker superseded this one | Not this worker's concern |

`SEARCH_FOUND_NOTHING` is the only one that positively excludes a provider execution. Everything
else leaves an execution that may be running and billing with no RAMALS row — bounded by the
provider's 24-hour batch expiry.

**Operator action.** For `SEARCH_FOUND_NOTHING`, nothing: the request may be re-driven safely by the
originating workflow. For the others, check the observations table before doing anything, and never
resubmit the same logical request without first establishing that no provider execution exists.

---

## Encryption-key failure

**Symptom.** `ResultEncryptionKeyUnavailableException`; the execution stays non-terminal.

**What the system does.** The result is **not** stored and the execution **stays recoverable** while
the provider still holds the result (29-day provider retention). It does not fail, and it does not
store plaintext. This is deliberate: a missing key is a configuration problem, not a result problem,
and the provider is still holding the answer.

**Operator action.** Restore the key material and let reconciliation retry. Retrieval will run again
and the result will be sealed with the active key. The key id is recorded per row in
`ai_execution_result.key_id`, so a rotation that leaves old key ids readable is safe; one that does
not will surface as `ResultEnvelopeCorruptException` on read.

**Never** work around this by disabling encryption or by widening grants. The envelope is enforced
by a database constraint (`ck_ai_execution_result_envelope` — byte 0 must be the version, minimum
length 31), so plaintext is **uncommittable**: there is no configuration in which it can be written.

---

## Result persistence / adoption failure

**Persistence.** The result is validated against `diagnostic-proposal.v1` *before* encryption and
re-serialised from the parsed proposal, so out-of-contract fields have nowhere to go. A
non-conforming document is refused with `RESULT_SCHEMA_INVALID` and the execution becomes `FAILED` —
this is the schema gate working, and it is what happens if a model returns something that is not a
proposal.

**Adoption.** Adoption is a single transaction (`core.adopt_ai_execution_result`) that writes the
decision and deletes the result together. A crash inside it rolls back both: you will never find a
decision without its result or a result whose decision half-committed. Re-running adoption is safe
and idempotent.

**Symptom of a stuck adoption.** `state = 'SUCCEEDED'` with a row still present in
`ai_execution_result`:

```sql
SELECT e.request_id, e.state, r.stored_at, r.purge_after, r.key_id
  FROM core.ai_provider_execution e
  JOIN core.ai_execution_result r USING (request_id)
 WHERE e.state = 'SUCCEEDED';
```

Rows here are normal briefly and abnormal if they persist: the result should be deleted on adoption.
A backlog means adoption is failing — check the ledger for the last entry and the application log
for a `DataAccessException`.

---

## Purge failure

**This alerts, and it is a governance failure rather than a backlog** (M2-ADR-018 §10, M2-ADR-019).

**Symptom.**

```
contract B ceiling sweep FAILED and results may now be outliving the retention ceiling
[retentionDays=30]. This is a governance failure, not a backlog.
```

The exception is logged **and rethrown**. Swallowing it would turn a retention breach into silence.

**Check the exposure immediately:**

```sql
SELECT count(*) AS overdue, min(purge_after) AS oldest_overdue
  FROM core.ai_execution_result
 WHERE purge_after < now();
```

**Operator action.** Any non-zero `overdue` that persists is a reportable retention breach under the
data classification, not a queue to drain quietly. Fix the cause (usually a database permission or
availability problem — the runtime holds `EXECUTE` on
`core.purge_expired_ai_execution_results(INTEGER, INTEGER)` and `DELETE` on the result table), then
re-run the sweep and confirm `overdue` returns to zero. Record the window during which results
outlived the ceiling.

The 30-day ceiling is enforced structurally as well: `ck_ai_execution_result_ceiling` refuses a
`purge_after` more than 30 days after `stored_at`, so the ceiling cannot be extended by writing a
row.

---

## Cost / token anomaly

**Where cost lives.** `ai_provider_execution` carries usage for the adopted execution;
`ai_provider_execution_observation` carries usage for **every** execution discovered for a request,
adopted or not. A duplicate therefore appears in the bill *and* in the evidence.

```sql
SELECT request_id, count(*) AS executions,
       sum(input_tokens) AS input, sum(output_tokens) AS output
  FROM core.ai_provider_execution_observation
 GROUP BY request_id
HAVING count(*) > 1
 ORDER BY 2 DESC;
```

Any request with more than one row is a duplicate provider execution — see
[MULTIPLE](#multiple--duplicate-provider-execution).

**Rate limiting.** The Message Batches API is limited organisation-wide (50 requests/minute
observed). Enumeration is bounded per pass and remembers negatives durably, so a stable orphan costs
**one** list call per pass in steady state. If you see

```
contract B reconciliation stopped: the provider is rate limiting [requestId=…, retryInMs=…]
```

the pass ended deliberately — continuing would ask the same exhausted quota the same question. The
provider's `Retry-After` is honoured when supplied, clamped to the backoff bounds. Repeated
rate limiting with few orphans means something *else* is consuming the quota; look outside Contract
B before tuning it.

**The inspection budget is load control, not a guarantee.** It does not prove the organisation-wide
limit cannot be exceeded, and it is per process — two workers are two budgets.

---

## Credential rotation

Two credentials matter, and they rotate independently.

**Provider API key** (`RAMALS_AI_PROVIDER_API_KEY`, held by the AI plane only). The platform never
holds it. Rotation is a restart of the AI plane with the new value; no RAMALS state refers to it.
In-flight batches are unaffected — they are identified by `msgbatch_…`, not by the key that created
them, and a new key on the same workspace can read them.

**Result encryption key** (`ResultEncryptionKeyProvider`, key ids recorded per row). Rotation must
be **additive first**: introduce the new key as active while keeping previous key ids resolvable,
because stored envelopes name the key that sealed them. Retiring a key id that still appears in
`ai_execution_result.key_id` makes those results unreadable — and they are unreadable *permanently*,
since the envelope is the only copy RAMALS holds after the provider's 29-day retention lapses.

```sql
SELECT key_id, count(*), min(stored_at), max(purge_after)
  FROM core.ai_execution_result GROUP BY key_id ORDER BY 3;
```

A key id may be retired once it appears in no rows. The 30-day ceiling guarantees that happens
within 30 days of the last write under it.

**Workload identity** (Spring → AI plane) rotates with the platform's OIDC client credentials and is
unrelated to Contract B state.

---

## Alert / triage procedure

There is no alerting today (W4). This section is the manual substitute; run it on a schedule until
metrics exist.

**Triage order — most consequential first:**

1. **Purge failures.** Search logs for `ceiling sweep FAILED`. Any hit is a governance matter.
   → [Purge failure](#purge-failure)
2. **Duplicates.** Run the cost-anomaly query. Any request with >1 observation needs a decision.
   → [MULTIPLE](#multiple--duplicate-provider-execution)
3. **Terminal-with-unknown.** Count `UNKNOWN_TERMINAL` by reason; a rising `SUBMIT_*` count means
   ambiguous submissions, which cost money invisibly.
   ```sql
   SELECT reason, count(*) FROM core.ai_execution_transition
    WHERE to_state = 'UNKNOWN_TERMINAL' GROUP BY reason ORDER BY 2 DESC;
   ```
4. **Stuck orphans.** `SUBMITTED` with no identity, older than the 26-hour horizon, still
   non-terminal — should be impossible; if present, the worker is not running.
5. **Queue depth.** `due_now` from the worker health check, trending.

**Escalate rather than improvise** for: any duplicate, any purge failure, any `UNKNOWN_TERMINAL`
whose reason is not `SEARCH_FOUND_NOTHING`. The safe holding action in every case is to leave the
execution alone — nothing degrades further while it sits.

---

## Safe restart / recovery expectations

**Restart is not a special mode.** The worker holds nothing in memory that matters: state is read
from PostgreSQL, and a replacement worker asks the provider exactly what the dead one would have
asked. This is qualified — the crash matrix covers ten kill points and every recovery reconstructs
from PostgreSQL alone.

**What to expect:**

- A worker that dies holding leases does not block anything. Leases expire after `lease-ms` (60 s)
  and another worker picks the item up. There is no cleanup step.
- The new process takes a fresh owner id, so it cannot inherit its own stale leases.
- A submission interrupted mid-call leaves `SUBMITTED` with no identity — recoverable, see
  [lost acknowledgement](#lost-acknowledgement--orphan-recovery). It is **never** re-submitted.
- Fencing (`submit_fence`) means a worker whose lease was superseded cannot write over the worker
  that replaced it; it defers instead, with `SUBMIT_FENCE_LOST` in the ledger.
- The durable no-match memo survives restart, so a partially-covered enumeration resumes where it
  stopped rather than restarting.

**Safe stop.** Disable `reconciliation.enabled` and let the current pass finish. Nothing needs to be
drained: an interrupted pass leaves leases that expire and work items that stay queued.

**What restart does not fix.** A rate limit, a missing key, or a duplicate. Restarting in response
to any of those wastes a pass and changes nothing.
