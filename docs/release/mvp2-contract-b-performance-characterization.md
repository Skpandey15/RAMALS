# Contract B — performance characterization

- **Characterized:** 2026-08-29, at `main` `1ffe322edd4d5d75c90d6ff1551df1e4318ddb9e` (after `#188`).
- **Scope:** the durable execution and reconciliation path. Contract A is out of scope.
- **Basis:** measured where measurement exists — the two real-provider qualification runs and the
  local integration suite — and derived arithmetic where it does not. **Every number below is
  labelled measured or derived.** Nothing here is a projection dressed as an observation.
- **Satisfies:** the performance half of Contract B Definition-of-Done criterion 9.
- **Approval:** see [approval record](mvp2-contract-b-approval.md).

## What this path is, in performance terms

Contract B is not a latency-sensitive path. It commissions work that takes minutes to hours at the
provider and whose whole premise is that it outlives the process that started it. **The performance
question is not "how fast" but "how much does it cost to wait"** — in provider requests, database
work, and quota shared with everything else.

That framing matters because the one real performance defect found in this path (W2 defect 3) was
not slowness. It was a recovery loop that repaid its full cost on every retry and exceeded the
provider's organisation-wide rate limit from a single lost acknowledgement.

## 1. Reconciliation cadence

| Parameter | Default | Effect |
| --- | --- | --- |
| `interval-ms` | 30 000 | 2 passes/minute per process |
| `batch-size` | 20 | Executions leased per pass |
| `backoff-ms` → `max-backoff-ms` | 30 000 → 900 000 | Exponential with jitter, capped at 15 min |
| `backoff-jitter-ms` | 5 000 | Prevents a fleet re-converging on the provider |
| `unacknowledged-grace-ms` | 300 000 | Delay before an unacknowledged submission is enumerated |

**The cap is load-bearing, not decoration.** Doubling from 30 s passes the 26-hour search horizon in
about a dozen attempts, so an uncapped backoff would stop retrying long before the horizon that is
meant to end the search — leaving executions non-terminal and unexamined. The 15-minute cap keeps
roughly 100 attempts inside the horizon. *(Derived; the cap and its arithmetic are asserted by
`backoffIsCapped` and `backoffGrowsWithAttempts`.)*

**Passes are serial within a process.** One `@Scheduled` method iterates the leased batch; there is
no intra-pass parallelism. Concurrency is therefore bounded at 1 per process by construction, which
is why the request-rate problem was never a concurrency problem.

## 2. Database lease and concurrency behaviour

**Leasing is `FOR UPDATE SKIP LOCKED`** over `ai_reconciliation_work`, matching the `V025` outbox.
The lock is held only for the claim statement, so a worker that dies holding a lease is recovered by
expiry rather than by anyone noticing.

- **Lease duration** 60 s — longer than one provider round trip, far shorter than a deployment.
- **Index**: `ix_ai_reconciliation_work_due` on `next_attempt_at` where `lease_owner IS NULL`, so due
  work is found without scanning the queue.
- **Fresh owner id per process start**, so a restarted worker cannot inherit its own stale leases.

**Measured:** the local integration suite drives multi-worker lease contention, fence loss, and
recovery-after-death against real PostgreSQL; 260 integration tests pass at
`--max-workers=1`. **Not measured:** genuine simultaneity across processes — see
[known limits](#9-known-limits-that-remain-debt-rather-than-guarantees).

**Query cost per pass** is bounded and small: one lease statement, one orphan sweep (two indexed
queries), then per execution a handful of single-row reads and writes. There is no query in this
path whose cost grows with total table size.

## 3. Enumeration list and results-call bounds

Correlation costs **one provider call per candidate**, and that shape is forced rather than chosen:
`GET /v1/messages/batches` returns no `custom_id`, so the listing only narrows the field and the
correlation must come from opening each candidate's results.

| Bound | Default | Behaviour at the bound |
| --- | --- | --- |
| Page size | 100 | Provider maximum |
| Max pages per search | 10 (1000 batches) | `INCONCLUSIVE`, `limitReached=pages` |
| Max inspections per search | 50 | `INCONCLUSIVE`, `limitReached=inspections` |
| Window | `submitted_at ± 1 h` | Batches outside are never opened |
| Horizon | 26 h | Past it, `INCONCLUSIVE` becomes terminal |

**Hitting any bound yields `INCONCLUSIVE`, never `ZERO` and never `ONE`.** A truncated search that
reported an outcome would be asserting a conclusion it did not reach.

**Measured cost of one search** (qualification run 2, 3 candidates, budget 2):

| Search state | List calls | `results()` calls |
| --- | --- | --- |
| First pass, nothing memoised | 1 | 2 |
| Steady state, all readable candidates memoised | **1** | **0** |
| Pass that finds the target | 1 | 1 (+1 retrieval, +1 status) |

## 4. Inspection-budget semantics

**The budget is per reconciliation pass, not per search** (M2-ADR-020 §3.2), default **15**.

Per-search bounds do not compose: a pass leasing 20 orphans at 50 inspections each authorises 1000
provider calls — the same unbounded behaviour a single search's bound was meant to prevent,
reintroduced by the loop around it. One allowance shared across the pass is what actually bounds it.

**Exhaustion is not a failure.** The pass performs no further enumeration; its remaining orphans
wait. A search that stops for budget reports `INCONCLUSIVE`, which already means "try again later".

**15 is a load-control default, not a proof**, and this document will not claim otherwise. It does
not guarantee the organisation-wide limit cannot be exceeded: other traffic shares that limit, and
the budget is per process. At a 30-second cadence it is ~30 inspections/minute against an observed
limit of 50 requests/minute — headroom, not a ceiling.

## 5. Durable negative memo effect

**This is the change that made the path viable**, and its effect is the largest single number here.

A batch that has ended and whose results streamed to completion without carrying the `custom_id` is
recorded in `ai_enumeration_no_match` and never opened again for that request. Sound because an
ended batch's results are immutable: skipping it is the same search, not a shorter one.

**Measured** (qualification run 2): **28 consecutive steady-state passes**, each costing exactly one
list call and zero result fetches, with the memo unchanged and the outcome `INCONCLUSIVE` throughout.

| | Before (`#185`) | After (`#186`) |
| --- | --- | --- |
| Non-concluding pass, 3 candidates | 1 list + 2 results | **1 list + 0 results** *(measured)* |
| Non-concluding pass, 45 candidates | 1 list + 45 results | **1 list + 0 results** *(derived)* |
| Cost of N retries | N × full cost | Full cost **once**, then ~1 call/pass |

**The steady-state cost is independent of the candidate count.** That is the structural claim, and
it is what turns an unbounded loop into a bounded one.

**Budget and memo are one mechanism.** A budget without the memo does not slow a search down, it
stops it finishing: the same first candidates are re-read every pass and the window is never
covered, converging on horizon exhaustion 26 hours later. This is pinned by executable negative
controls in both languages.

## 6. Provider rate-limit exposure

**Observed limit:** 50 requests/minute, organisation-wide, on the Message Batches API — recorded
from a real `RateLimitError` during W2.

**Derived exposure at defaults**, 30-second cadence:

| Situation | Calls/minute |
| --- | --- |
| Steady state, any number of memoised orphans | ~2 (one list per pass) |
| Transient, first search over a 45-candidate window | ~32 for `⌈45/15⌉ = 3` passes, then ~2 |
| Worst case, budget fully spent every pass | ~32 |

**Measured:** no 429 occurred in either qualification run, and none was induced.

**Handling** (M2-ADR-020 §7): a 429 is classified apart from an outage — they call for opposite
responses. `Retry-After` is honoured when supplied, clamped to the backoff bounds; otherwise
exponential backoff with jitter. **A rate-limited pass stops**, because the limit is
organisation-wide and the next orphan would ask the same exhausted quota.

**Not verified:** whether Anthropic actually sends `Retry-After` on a 429, and in what format. No 429
was induced, deliberately — inducing one means reproducing the amplification that was just removed.
The fallback path is what runs if the header is absent, and it is covered by test.

## 7. Workspace-density assumptions

The costs above assume **the ±1 h window holds a small number of batches**. That assumption is
explicit because the design depends on it:

- **Inspections** scale with in-window candidates, bounded at 50/search and 15/pass.
- **Listing does not benefit from the memo.** The walk starts at the newest batch and counts
  everything it passes, including batches *newer* than the window. A workspace creating >1000
  batches between a submission and its recovery hits the page bound and returns `INCONCLUSIVE`
  until the horizon.

**Measured density:** 3–6 batches in-window during qualification — a research environment.
**Derived threshold:** listing degrades at ~1000 batches per recovery interval; inspection degrades
at ~50 in-window candidates per search.

Both are recorded as M2-ADR-020 revisit triggers rather than as engineering limits that have been
tested. **Neither has been exercised at scale**, and this document does not claim they have.

## 8. Cost implications

**Token cost is negligible and is not the constraint.** Nine real batches across both qualification
runs cost ≈ $0.0003 total. The binding constraints are wall clock (batch turnaround ~2 minutes
observed, provider deadline 24 hours) and provider request quota.

**Enumeration is not token-billed.** `list` and `results` calls consume request quota, not tokens, so
recovery cost is measured in requests rather than money.

**The real cost risk is a duplicate provider execution**: a batch that runs, bills and is never
adopted. It is bounded by the 24-hour batch expiry and is now **visible** —
`ai_provider_execution_observation` records usage for every discovered execution, adopted or not, so
a duplicate appears in the evidence rather than being inferred from a bill later.

## 9. Known limits that remain debt rather than guarantees

Stated as limits, deliberately. None is presented as a tested guarantee.

| # | Limit | Status |
| --- | --- | --- |
| P1 | **The budget is per process.** N reconciliation workers means ~N× the request rate. A global budget needs shared coordination state — a distributed rate limiter, disproportionate to MVP-2. | Debt. M2-ADR-020 §7 states it; deploying >1 worker is a recorded revisit trigger. |
| P2 | **Listing cost is not reduced by the memo** and grows with unrelated workspace activity. | Debt. Revisit trigger in M2-ADR-020. |
| P3 | **15 is load control, not a proven ceiling.** | By design; stated in the ADR and in the code. |
| P4 | **`Retry-After` unverified against a real 429.** | Debt. Fallback path is tested. |
| P5 | **No metrics or alerting.** Every number here comes from a database query or a log line. | Debt (W4), already ACCEPTED_DEBT under criterion 6. |
| P6 | **No load or soak testing.** Behaviour is characterized at research volumes, not stressed. | Debt. Belongs with AWS multi-replica qualification. |
| P7 | **Cross-process concurrent reconciliation not exercised.** Fencing and leasing are proven; genuine simultaneity is not. | Debt. Recorded in the closure assessment as gap 6. |

**What is genuinely characterized:** cadence, lease behaviour, enumeration bounds, budget semantics,
memo effect, and rate-limit exposure at research density — with the steady-state figure measured
against the real provider rather than derived.

**What is not:** behaviour under load, at scale, or across replicas. Criterion 9 asks for a
performance characterization, and this is one. It is not a capacity model, and it must not be cited
as evidence that the path will hold at production volume.
