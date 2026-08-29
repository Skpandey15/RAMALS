# Contract B — W2 defect 3 real-provider qualification (enumeration request rate)

- **Verdict: INCONCLUSIVE.** The success condition was **two consecutive steady-state passes**;
  **one** was observed. The target batch ended before a second could run. Per the agreed protocol no
  replacement batches were created and the run was not extended.
- **Performed:** 2026-08-29, against the real Anthropic Message Batches API, on `main` at
  `0977276c3c8cf40dfaa4694d322aec60636ebf44` (PR `#186`).
- **Authorises:** nothing. No Definition-of-Done criterion is claimed, no route is activated, and the
  [closure assessment](mvp2-closure-assessment.md) verdict is unchanged.
- **Spend:** 6 real batches, **≈ $0.0001**, against a $1.00 ceiling and a $0.10 abort threshold.
- **Two new defects were found**, both of which block Contract B in production and neither of which
  is the defect under test. They are the most important output of this run.

## What was being tested

That the merged fix — durable negative memo (§3.1), per-pass inspection budget (§3.2) and
rate-aware retry (§7) — does not reproduce the request-rate amplification W2 found.

The amplification was never "one search is expensive". It was that a search which *could not
conclude* repaid its full cost on every retry. So the scenario had to contain a search that
legitimately could not conclude for several passes: four decoy batches allowed to end, then a target
batch created last and still `in_progress`, holding the search open.

`inspectionBudgetPerPass = 2` was used **only to force cumulative multi-pass coverage** with a small
candidate set. **This qualifies the memo/budget mechanism, not the production default of 15.**

## Measured per-pass call table

Ground truth is the Anthropic SDK call log, recorded by wrapping the SDK object the adapter already
uses — not the platform's own account of what it did.

| Pass | Provider calls | `results()` inspections | Memo before → after | Outcome | Steady state |
| --- | --- | --- | --- | --- | --- |
| 1 | 3 | 2 | 0 → 2 | `RECONCILING` | no |
| 2 | 3 | 2 | 2 → 4 | `RECONCILING` | no |
| 3 | 2 | 1 | 4 → 5 | `RECONCILING` | no |
| **4** | **1** | **0** | 5 → 5 | `RECONCILING` | **yes (1 of 2 required)** |
| 5 | 4 | 2 | 5 → 5 | identity recovered → `FAILED` | no |

Totals: **15 provider calls, 7 inspections**, across 5 passes at a 10-second cadence — deliberately
*faster* than the production 30 seconds, because a higher request rate is a stronger test of the
property under qualification, never a weaker one.

### The one steady-state pass

Pass 4 is the measurement the whole run exists to produce. Every readable candidate had been
memoised and the target was still uninspectable, so the search performed **one list call and zero
result fetches** and correctly reported `INCONCLUSIVE` rather than `ZERO`.

The old behaviour on that same pass would have been **1 list + 5 result fetches**, and would have
repeated it every pass until the 26-hour horizon.

### No batch was re-inspected by enumeration

From the call log, each of the five non-target candidates was opened **exactly once across all five
passes**:

```
04:56:26.586 list
04:56:27.003 results msgbatch_01LHTv3eTSh5aA2kFuvuAq49
04:56:27.870 results msgbatch_01UTWLwPvGsuJStGMQuHtHiz
04:56:39.252 list
04:56:39.659 results msgbatch_012DET4wUzJGt2EFevnxDZWp
04:56:40.538 results msgbatch_017jB6VcDT1i62pgUJbUEEwK
04:56:51.479 list
04:56:51.857 results msgbatch_01YLWt47Zo2548ZQ6miYu9nX
04:57:02.847 list                                        <- steady state: no inspections
04:57:13.404 list
04:57:13.824 results msgbatch_01TdzQrVnNFRpxdET9YC4U7D   <- enumeration finds the target
04:57:14.846 retrieve msgbatch_01TdzQrVnNFRpxdET9YC4U7D  <- adoption, then ordinary polling
04:57:15.203 results msgbatch_01TdzQrVnNFRpxdET9YC4U7D   <- result retrieval, not re-inspection
```

The target appears twice, and that is not a re-inspection: the second pair is the adoption path
fetching the result to store it, a different operation from enumeration.

## What did hold, and what did not

| Property | Result |
| --- | --- |
| Already-inspected negatives are not reopened | **Demonstrated** — five candidates, one inspection each, across five passes |
| Cumulative memo coverage accumulates durably | **Demonstrated** — 0 → 2 → 4 → 5 in PostgreSQL |
| A bounded pass resumes rather than restarts | **Demonstrated** — coverage advanced every pass |
| Steady-state pass costs exactly one call | **Observed once**, twice required → **INCONCLUSIVE** |
| An unfinished candidate never reads as absence | **Demonstrated** — `INCONCLUSIVE`, never `ZERO`, while the target ran |
| Recovery still works through the memo | **Demonstrated** — recovered `msgbatch_01TdzQrVnNFRpxdET9YC4U7D`, matching the out-of-band ground truth exactly |
| Every discovered execution is recorded with usage | **Demonstrated** — one observation, 14 in / 4 out, `ENUMERATION` |
| No submission during reconciliation | **Demonstrated** — zero `create` calls after setup |
| No 429 | **Demonstrated** — none occurred; none was induced |
| Provider rate-limit headers | **Not observed.** The SDK did not expose them on the calls this scenario already required, and no call was added to obtain them |
| `Retry-After` handling against a real 429 | **Not tested, by design.** Covered by merged fake tests only |

### Why the execution ended `FAILED`

```
ADMITTED
EVENT               OBSERVED_msgbatch_01TdzQrVnNFRpxdET9YC4U7D
SUBMITTED           IDENTITY_RECOVERED
RECONCILING         RECONCILE_STARTED
FAILED              RESULT_SCHEMA_INVALID
```

The fixture prompt asks the model for the word "ok", which is not a `diagnostic-proposal.v1`
document, so the result store refused it. That is the M2-ADR-018 §10 schema gate working, not a
defect, and it is the same refusal W2 recorded. Recovery itself succeeded: the identity was found,
adopted under fence, and recorded with its usage.

## Two new defects, both blocking, neither the one under test

Both were invisible to every existing test because the Java suites use a fake port and never cross
the HTTP boundary to the AI plane. Both were found within minutes of the first real end-to-end run.

### D1 — reconciliation sends an empty `X-Interaction-ID` and is rejected with HTTP 400

`RamalsAiDurableExecutionClient` sends `CorrelationContext.currentInteractionId()`, which returns
`""` on any thread with no MDC. The reconciliation worker is a Spring `@Scheduled` method with no
correlation scope, so every Contract B call it makes carries an empty header. The AI plane's
correlation middleware accepts a *missing* header (it generates one) but rejects an empty one:
`is_canonical_uuid7("")` is false, giving `400 INVALID_INTERACTION_ID`.

**Consequence:** with `reconciliation.enabled=true`, every enumeration and status call from the
worker fails. Recovery would never complete — each attempt would look like an unreachable provider
and retry to horizon exhaustion.

`AgentWorkDispatcher` establishes a correlation scope before its AI calls; nothing in
`execution/contractb/` does.

### D2 — the durable transport offers an HTTP/2 upgrade the AI plane refuses

The submit path returned `422` with an empty body server-side, while the identical JSON posted by
`curl` succeeded. The first reading — "the client fails to serialise its body" — was wrong, and the
HTTP-boundary test written afterwards disproved it: against a JDK `HttpServer` the client sends a
complete, correct payload that the AI plane's own `DurableSubmitRequest` model accepts.

Reproduced against the real uvicorn/Starlette stack with a stub adapter (no provider call), the
actual mechanism is visible:

```
uvicorn: WARNING  Unsupported upgrade request.
uvicorn: WARNING  Invalid HTTP request received.
        -> 422, request reaches the route with no body
```

`ContractBConfiguration` built its `RestClient` with **no request factory**, so Spring's default JDK
`HttpClient` transport offered an HTTP/2 cleartext upgrade (`Upgrade: h2c`). uvicorn speaks HTTP/1.1
only, refuses the upgrade, and FastAPI then validates a bodyless request. Every other AI client in
the codebase passes a `SimpleClientHttpRequestFactory`-derived factory and never offers the upgrade;
Contract B was the one that did not, and it was the one that could not submit.

**Consequence:** no Contract B submission could ever succeed against the deployed AI plane.

Neither was fixed during the qualification run itself. D1 was worked around inside the throwaway
harness so the approved qualification could proceed; that workaround was harness-only and was
deleted with the harness. D2 was worked around by creating the target batch out of band —
provider-identical, since the resulting state is the same batch carrying the same `custom_id` with
RAMALS holding no record of it.

**Both are fixed in the pull request that carries this document**, each with an HTTP-boundary
regression test that fails when the fix is reverted. The verdict on defect 3 above is unchanged by
that work and remains **INCONCLUSIVE**.

## Method notes

**The harness is not committed and has been deleted.** It spends real money and was gated on
`RAMALS_W2D3_QUALIFICATION=true`.

**No secrets and no model output are recorded.** The credential was verified with a zero-token
`models.list` call, read from the user environment scope into the harness process, never displayed
and never written. This document holds batch identifiers, correlation keys, timestamps, call counts,
memo progression, outcomes and transitions only.

**One batch here was created unintentionally** — `msgbatch_01LHTv3eTSh5aA2kFuvuAq49`, from a
diagnostic `curl` probe while isolating D2. It is recorded rather than omitted, and it became a
sixth candidate in the window.

**Batch turnaround was ~2 minutes**, faster than the ~2.5 assumed when planning. That is the entire
reason the verdict is INCONCLUSIVE: the target's uninspectable window admitted only one steady-state
pass before it ended.

## What a conclusive run needs

The scenario is sound; only the observation window was too short. The cheapest fix is to make the
target's uninspectable period longer than the passes needed to memoise the decoys — for example by
creating the target with a larger `max_tokens`, or by memoising the decoys in a prior run so that
pass 1 is already steady state. Neither requires more batches than this run used.

**No Definition-of-Done criterion is claimed, and the DoD is unchanged.**
