# Contract B — residual S2 resolved: submission outcome classification

- **Discharges:** the binding condition attached to the criterion 9 approval
  ([approval record](mvp2-contract-b-approval.md)) — *"S2 must be resolved and separately reviewed
  before `ramals.contract-b.enabled` may be activated in any environment."*
- **Reviewed:** 2026-08-29, against `main` `e336461b735500827d9051ce86416e902329efa5`.
- **Does not activate anything.** Route activation remains a separate decision. This document
  discharges one precondition of it and nothing else.

## The finding, as approved

> **S2** — the submission path treats **any** status the AI plane chooses as proof nothing was
> created. Correct for a 4xx; wrong for a 5xx raised *after* `batches.create` has already succeeded,
> where it would record a definite `FAILED` for a provider execution that exists.

## Root cause

`RamalsAiDurableExecutionClient.submit` caught `RestClientResponseException` — *any* HTTP status the
far side chose — and raised `DurableExecutionRefusedException`, whose contract is that the caller may
record a definite `FAILED`. The comment said so explicitly: *"It knows nothing was submitted."*

**That inference does not hold, because a status code cannot carry the fact it was being read for.**
The same `500` covers:

- the Anthropic SDK was not installed, so no call was ever made — nothing created; and
- the connection dropped after `batches.create` was sent — a batch may be running.

Those are opposite answers to the only question that matters, and the status is identical. Whether
provider-side creation can be ruled out is knowledge that exists **only inside the AI-plane process**,
at the moment of failure, and it was being discarded at the boundary and then guessed at on the far
side.

The rest of the lifecycle was already correct. `ContractBExecutionService.submit` has exactly one
path to `FAILED` — a caught `DurableExecutionRefusedException` — and routes ambiguous, unusable and
fence-lost outcomes to `UNKNOWN_TERMINAL`. **S2 was entirely localized to who raises that
exception.**

## The fix: state it, do not infer it

The AI plane now says explicitly whether a provider execution may exist, and the platform classifies
on that statement alone.

```json
{"detail": {"code": "PROVIDER_TIMEOUT", "detail": "...", "submission": "MAY_EXIST"}}
```

**Only a deliberate, parsed rejection rules creation out.** `_CREATION_RULED_OUT` lists exactly the
codes where something read the request and said no: the capability gate and route-not-configured
(refused before the adapter was reached), the governance ceilings (refused before any provider call),
and `PROVIDER_INVALID_REQUEST` / `PROVIDER_AUTH_ERROR` / `PROVIDER_RATE_LIMITED` (the provider
answered). Everything else — including anything added later — is `MAY_EXIST`.

**`PROVIDER_TIMEOUT` and `PROVIDER_UNAVAILABLE` are deliberately absent.** A timeout is the absence
of an answer, never an answer; a connection reset can happen after the bytes were sent, and a
provider 5xx can follow work it already began.

**The platform requires the marker to be present.** Its absence is not evidence of anything: a proxy
error page, a gateway that never reached the AI plane, an older AI plane, and an unparseable body all
arrive without it, and none rules creation out. Reading it never throws — a classifier that could
fail while classifying a failure would decide the outcome by accident.

## Outcome taxonomy — old versus new

| Outcome | Old | New | Correct? |
| --- | --- | --- | --- |
| Definite refusal before creation (capability gate, route not configured, governance ceiling) | `FAILED` | `FAILED` | ✅ unchanged |
| Provider 4xx — parsed and rejected (`PROVIDER_INVALID_REQUEST`, `PROVIDER_AUTH_ERROR`) | `FAILED` | `FAILED` | ✅ unchanged |
| Rate limit `429` **stated by the AI plane** | `FAILED` | `FAILED` | ✅ unchanged |
| Accepted, usable identity | `SUBMITTED` | `SUBMITTED` | ✅ unchanged |
| **AI-plane 5xx after `batches.create` may have succeeded** | **`FAILED`** ❌ | **`UNKNOWN_TERMINAL`** | ✅ **fixed — the defect** |
| Provider timeout | `FAILED` ❌ | `UNKNOWN_TERMINAL` | ✅ fixed |
| Provider unreachable / connection reset | `FAILED` ❌ | `UNKNOWN_TERMINAL` | ✅ fixed |
| Unclassified failure inside the submission path | `FAILED` ❌ | `UNKNOWN_TERMINAL` | ✅ fixed |
| Status with no marker (proxy, gateway, old plane) | `FAILED` ❌ | `UNKNOWN_TERMINAL` | ✅ fixed |
| Unparseable error body | `FAILED` ❌ | `UNKNOWN_TERMINAL` | ✅ fixed |
| Bare `429` with no marker | `FAILED` ❌ | `UNKNOWN_TERMINAL` | ✅ fixed |
| Transport timeout / disconnect (no HTTP status at all) | `UNKNOWN_TERMINAL` | `UNKNOWN_TERMINAL` | ✅ unchanged |
| 2xx with no body | `UNKNOWN_TERMINAL` | `UNKNOWN_TERMINAL` | ✅ unchanged |
| 2xx with no execution identity | `UNKNOWN_TERMINAL` (`SUBMIT_ACK_UNUSABLE`) | unchanged | ✅ unchanged |
| Fence lost | `UNKNOWN_TERMINAL` (`SUBMIT_FENCE_LOST`) | unchanged | ✅ unchanged |

**Seven outcomes moved from a definite failure to an ambiguous one. None moved the other way.**

## What is preserved

| Property | How |
| --- | --- |
| **No resubmission, ever** | Nothing in this change retries. A test asserts the provider is contacted exactly once across refusal, ambiguity and unmarked-failure paths. |
| **Write-ahead persistence** | Untouched. The claim still commits before the provider is called. |
| **Fencing** | Untouched. `SUBMIT_FENCE_LOST` still defers rather than overwriting. |
| **Only classified refusal reaches `FAILED`** | Strengthened: the classification is now a positive claim by the AI plane, not an inference from a status. |
| **Contract A** | Untouched. The change is confined to the durable submit path and its client. |
| **Route disabled** | `ramals.contract-b.enabled` remains `false`. |

## Cost of the fix, stated

Failures that *are* provably safe but arrive without a marker — a FastAPI `422` from request
validation, a `401` from workload auth — now become `UNKNOWN_TERMINAL` rather than `FAILED`. Both
indicate a RAMALS bug or misconfiguration rather than normal operation, and an execution an operator
looks at is a defensible response to either.

This is the deliberate direction of the trade: **failing closed costs operator attention; failing
open costs a duplicate provider execution that runs, bills and is never adopted.** It is also
consistent with `UnconfiguredDurableExecutionPort`, which already refuses ambiguously for exactly
this reason.

## Evidence

**Java — `ContractBSubmissionOutcomeTests`** (11 tests, real `HttpServer`, not a mock): explicit
`NOT_CREATED` is a refusal · all six provable refusal codes honoured · **negative control: a 5xx
after create is ambiguous and never `FAILED`** · timeout and unavailable are ambiguous · unmarked
response ambiguous · unparseable body ambiguous · only the exact marker counts · bare `429`
ambiguous · usable acknowledgement accepted · partial acknowledgement unusable but not a refusal ·
empty body ambiguous · **no path submits more than once**.

**Python — `test_durable_routes.py`** (7 new): each of the eight parsed-rejection codes reports
`NOT_CREATED` · both transport codes report `MAY_EXIST` · an unclassified failure is `502` +
`MAY_EXIST` · the capability gate marks its own refusal · every gateway code maps to a disposition
and unknown ones fail closed · a logging failure after create never loses the acknowledgement.

**Cross-language — `ContractBSubmissionMarkerContractTests`** (3): the marker is read out of the
Python source rather than restated, so a drifted spelling fails the build instead of silently
turning every refusal ambiguous; and the ruled-out set is asserted **not** to contain the transport
codes, which is where the defect would reappear one layer deeper.

**Negative controls, each verified to fail when the fix is reverted:**

| Reverted | Result |
| --- | --- |
| `creationRuledOut(...)` → `true` (the old "any status is a refusal") | **6 Java tests fail**, including the named 5xx-after-create control |
| `PROVIDER_TIMEOUT` / `PROVIDER_UNAVAILABLE` added to `_CREATION_RULED_OUT` | **2 Python tests fail** |

## Was a real provider call needed?

**No.** The behaviour under review is how RAMALS classifies a response, and the responses that
matter — a 5xx after create, an unparseable body, a missing marker — cannot be induced reliably
against a real provider and are produced exactly by a local HTTP server. The provider operations
themselves are already qualified (prerequisite 1, W2). No Anthropic call was made for this work.

## Is S2 resolved?

**Yes, for the defect as written.** No response the AI plane or the network can produce reaches
`FAILED` unless the AI plane explicitly states that no provider execution was created, and the
statement is only made where something parsed the request and rejected it.

**One bounded residual remains**, and it is inherent rather than a gap here: if the AI plane process
dies *between* `batches.create` succeeding and its response being written, no marker is emitted. The
platform then classifies it `MAY_EXIST` — the correct answer — and the execution becomes a
lost-acknowledgement orphan recoverable by `custom_id` enumeration (M2-ADR-020). **The mechanism that
covers this case is already built and qualified against the real provider.**

## Recommendation to the accountable owner

The binding condition attached to the criterion 9 approval required S2 to be *resolved and separately
reviewed*. This document is that separate review, and its conclusion is that **the condition is
discharged**.

Route activation remains a separate decision, gated by the Definition of Done and by whatever else
the owner requires. Nothing here authorises it.
