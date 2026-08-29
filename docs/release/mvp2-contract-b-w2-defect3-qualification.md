# Contract B — W2 defect 3 real-provider qualification (enumeration request rate)

- **Verdict: PASS**, on the second run (2026-08-29, `main` at `71c35e37c23e63f2d5fd9097312f3d82a38413ab`).
  The first run returned **INCONCLUSIVE** and is kept below in full, because it is what found the two
  defects that had to be fixed before a conclusive run was possible at all.
- **Also qualified in the same run:** D1 (reconciliation correlation) and D2 (durable transport),
  both against the real provider path through the merged production wiring.
- **Authorises:** nothing. No Definition-of-Done criterion is claimed, no route is activated, and the
  [closure assessment](mvp2-closure-assessment.md) verdict is unchanged.
- **Spend:** 9 real batches across both runs, **≈ $0.0003**, against a $1.00 ceiling and a $0.10
  abort threshold.

---

# Run 2 — conclusive (2026-08-29, after `#187`)

## What changed since run 1

Run 1 could not exercise the production path: its submission had to be created out of band and its
correlation had to be established by the harness, because both were broken. `#187` fixed them, so
run 2 uses the shipped wiring with **no workarounds** — the port comes from `ContractBConfiguration`,
the submission goes through that client to the AI plane to Anthropic, and the passes are driven by
`ContractBReconciliationWorker.poll()` on a thread with no MDC.

Three real batches: two decoys allowed to end, then one target whose longer generation
(`max_output_tokens = 1500`) keeps it `in_progress` across the observation window. Nothing about
production semantics was relaxed to extend that window — only the fixture prompt's length changed.

`inspectionBudgetPerPass = 2`, and a short constant backoff (4 s, cap 4 s, no jitter) so passes come
promptly. Both are configured knobs; the backoff mechanism, its cap and its jitter all run as
written. **This qualifies the memo/budget mechanism, not the production default of 15.**

## D2 — submission through the real transport

| | |
| --- | --- |
| Path | `ContractBConfiguration` → `RestClient` → AI plane → Anthropic |
| Result | **201**, `msgbatch_01JqTrZgadDjfWjbup39y43W`, state `RUNNING` |
| Provider `create` calls | **1** |
| `Upgrade` header offered | **none**, on any request |
| Out-of-band submission / transport workaround | **none** |

The h2c upgrade that made every submission fail is gone, observed at the AI plane rather than
asserted by the caller.

## D1 — correlation across the scheduled hand-off

Precondition recorded before the first pass: `MDC.interactionId = null`. This is the scheduler's
actual condition, not a simulation of it.

| Observation | Result |
| --- | --- |
| Reconciliation requests reaching the AI plane | **32** |
| Carrying an interaction id | **32 / 32** |
| Empty or blank | **0** |
| Canonical lowercase UUIDv7 | **32 / 32** |
| HTTP status | **all 200** (plus the one 201 submit) |
| MDC after each of 30 passes | **null every time** — no leakage |

The single request without an interaction header is the harness's own submission, made outside any
correlation scope: the client **omits** the header rather than sending an empty one, and the AI plane
generates its own. That is the designed behaviour and the opposite of the D1 defect.

Thirty distinct ids across thirty passes, because this execution was admitted outside a request and
so has no recorded correlation to restore — the documented fallback, generating one per attempt.

## W2 defect 3 — steady state

**28 consecutive steady-state passes** (passes 2–29). Two were required.

| Pass | List calls | `results()` inspections | `create` calls | Memo | Search outcome |
| --- | --- | --- | --- | --- | --- |
| 1 | 1 | 2 | 0 | 0 → 2 | `INCONCLUSIVE` |
| **2–29** | **1 each** | **0 each** | **0 each** | **2 → 2, unchanged** | **`INCONCLUSIVE`** |
| 30 | 1 | 2 | 0 | 2 → 2 | `ONE` → adopted |

A steady-state search, as the AI plane reported it: `batchesInspected: 0`, `batchesExcluded: 2`,
`batchesUninspectable: 1`. Zero inspections, both negatives skipped from the durable memo, the target
still unreadable, and the honest answer still `INCONCLUSIVE`.

Across the whole run: **29 `INCONCLUSIVE` searches and 1 `ONE`**. No `ZERO` was ever reported while a
candidate remained uninspectable.

The old behaviour on each of those 28 passes would have been 1 list + 2 result fetches, repeated
until the 26-hour horizon.

### No batch was re-inspected by enumeration

```
08:47:14.058 create                                          <- the submission
08:47:15.312 results msgbatch_01XJzjL9onQ3ek4TZ6nbEicN       <- decoy, pass 1
08:47:16.160 results msgbatch_01PqM1YRpjnzjWYzH4D328mS       <- decoy, pass 1
                                                              (passes 2-29: no results calls at all)
08:50:00.481 results msgbatch_01JqTrZgadDjfWjbup39y43W       <- enumeration finds the target
08:50:01.422 retrieve msgbatch_01JqTrZgadDjfWjbup39y43W      <- adoption, then ordinary polling
08:50:01.833 results msgbatch_01JqTrZgadDjfWjbup39y43W       <- result retrieval, not re-inspection
```

Each decoy opened exactly once across thirty passes. The target's second pair is the adoption path
fetching the result to store it — a different operation from enumeration.

**Recovery still works:** the recovered identity matched the out-of-band ground truth exactly.

## Abort conditions — none triggered

| Condition | Observed |
| --- | --- |
| Duplicate enumeration inspection | none |
| False `ZERO` | none — no `ZERO` at all |
| Invalid or empty interaction id | none |
| Transport 4xx caused by RAMALS | none — 201 + 32 × 200 |
| Provider submission during reconciliation | none — 1 `create`, before the passes |
| 429 | none, and none induced |

## Verdict

**D1 PASS · D2 PASS · W2 defect 3 PASS.**

Still unqualified, and unchanged by this run: `Retry-After` against a real 429 (not tested, by
design), provider rate-limit headers (not exposed on the calls this scenario required), the per-process
budget under more than one worker, and the listing bound at scale.

---

# Run 1 — INCONCLUSIVE (2026-08-29, on `#186`)

Kept in full. It is the run that found D1 and D2, and its verdict stands as recorded.

- **Verdict: INCONCLUSIVE.** The success condition was two consecutive steady-state passes; **one**
  was observed. The target ended before a second could run. Per the agreed protocol no replacement
  batches were created and the run was not extended.
- **Performed:** against `main` at `0977276c3c8cf40dfaa4694d322aec60636ebf44` (PR `#186`).
- **Spend:** 6 real batches, ≈ $0.0001.

## Measured per-pass call table

| Pass | Provider calls | `results()` inspections | Memo | Outcome | Steady state |
| --- | --- | --- | --- | --- | --- |
| 1 | 3 | 2 | 0 → 2 | `INCONCLUSIVE` | no |
| 2 | 3 | 2 | 2 → 4 | `INCONCLUSIVE` | no |
| 3 | 2 | 1 | 4 → 5 | `INCONCLUSIVE` | no |
| **4** | **1** | **0** | 5 → 5 | `INCONCLUSIVE` | **yes (1 of 2 required)** |
| 5 | 4 | 2 | 5 → 5 | identity recovered → `FAILED` | no |

Batch turnaround was ~2 minutes rather than the ~2.5 assumed when planning, and the window admitted
only one steady-state pass. Run 2 fixed this by needing fewer passes to memoise (two decoys, not
five candidates) and by giving the target a longer generation.

`FAILED` there was `RESULT_SCHEMA_INVALID`: the fixture asked for the word "ok", which is not a
`diagnostic-proposal.v1` document, so the store fail-closed. The M2-ADR-018 §10 gate working.

## The two defects run 1 found

Both were invisible to every existing test because the Java suites used a fake port and never crossed
the HTTP boundary. Both are fixed in `#187`, each with a boundary regression test that fails when the
fix is reverted.

### D1 — reconciliation sent an empty `X-Interaction-ID` and was refused

`CorrelationContext.currentInteractionId()` returns `""` on a thread with no MDC — exactly a Spring
`@Scheduled` worker. The AI plane accepts a *missing* header (it generates one) but rejects an empty
one: `is_canonical_uuid7("")` is false, giving `400 INVALID_INTERACTION_ID`. Every enumeration and
status call from the worker failed, so recovery could never complete.

### D2 — the durable transport offered an HTTP/2 upgrade the AI plane refuses

Submissions returned `422` with no body server-side. The first reading — "the client fails to
serialise its body" — was **wrong**, and the HTTP-boundary test written afterwards disproved it: the
exact bytes the client sends are accepted by the AI plane's own `DurableSubmitRequest` model.

Reproduced against the real uvicorn stack with a stub adapter (no provider call), the mechanism is
visible in the server log:

```
uvicorn: WARNING  Unsupported upgrade request.
uvicorn: WARNING  Invalid HTTP request received.
        -> 422, request reaches the route with no body
```

`ContractBConfiguration` built its `RestClient` with no request factory, so Spring's default JDK
`HttpClient` transport offered `Upgrade: h2c`. uvicorn speaks HTTP/1.1 only. Every other AI client in
the codebase passes a factory and never offers the upgrade; Contract B was the one that did not, and
it was the one that could not submit.

---

## Method notes (both runs)

**The harnesses are not committed and have been deleted.** They spend real money and were gated on
`RAMALS_W2D3_QUALIFICATION` / `RAMALS_W2_FINAL`.

**No secrets and no model output are recorded.** The credential was verified with a zero-token
`models.list` call, read from the user environment into the harness process, never displayed and
never written. This document holds batch identifiers, correlation keys, timestamps, call counts, memo
progression, outcomes and transitions only.

**One batch in run 1 was created unintentionally** — `msgbatch_01LHTv3eTSh5aA2kFuvuAq49`, from a
diagnostic probe while isolating D2. Recorded rather than omitted; it became a sixth candidate in
that window.

**Ground truth is the provider SDK call log**, captured by wrapping the SDK object the adapter
already uses, plus the AI plane's own record of what arrived at its HTTP boundary. Neither is the
platform's account of itself.

**No Definition-of-Done criterion is claimed, and the DoD is unchanged.**
