# Contract B prerequisite 1 — provider capability qualification evidence

- **Result:** **PASS** — M2-ADR-017 §6 prerequisite 1 is **SATISFIED for the RAMALS MVP/research
  environment**.
- **Performed:** 2026-08-27, against the real Anthropic API in a non-production environment.
- **Satisfies:** M2-ADR-017 §6 prerequisite 1 — *"provider capability proven in a non-production
  environment, against the M2-ADR-016 mandatory rows, on the specific route proposed for Contract
  B."*
- **Authorizes:** nothing further. `V037` remains subject to M2-ADR-018's engineering acceptance
  criteria, and no route is Contract B.

## Run identity

| | |
| --- | --- |
| Provider path | Anthropic **Message Batches** |
| Adapter under test | **PR #170** (`892be37`) — `AnthropicBatchesProvider` |
| Source HEAD | **`26d32f5`** |
| SDK | **`anthropic==1.1.0`** (the pinned version, in a clean venv outside the repository) |
| Model | **`claude-sonnet-5`** (ADR-approved) |
| SDK retries | **`max_retries=0`**, asserted before the call rather than assumed |
| Max output tokens | 16 |
| Durable execution ID | **`msgbatch_011tPDJFFZbdcr2zR1ibX9DR`** |
| Provider submissions | **exactly one** |
| Approximate cost | **~$0.000036** (16 input / 4 output tokens) |

## What was proven

| # | Capability | Verdict |
| --- | --- | --- |
| 1 | Batch creation succeeds | ✅ PASS |
| 2 | Native `msgbatch_…` identity returned | ✅ PASS |
| 3 | Submitted `custom_id` survives provider execution | ✅ PASS |
| 4 | Status retrievable **using only the durable batch ID** | ✅ PASS — **58 observations from a separate process** |
| 5 | Terminal result retrievable after the create call ended | ✅ PASS — retrieved ~45 minutes later, different process |
| 6 | Result correlates by `custom_id` | ✅ PASS — returned value identical to submitted |
| 7 | Native lifecycle / count / timestamp metadata preserved | ✅ PASS |
| 8 | Exactly one provider submission | ✅ PASS |
| 9 | No SDK retry, fallback or hidden resubmission | ✅ PASS |
| 10 | **Cancellation** | ⏭️ **NOT ATTEMPTED — must not be represented as proven** |

**Lifecycle observed:** `in_progress` → `ended`, across 58 status reads.
**Final request counts:** `processing 0 · succeeded 1 · errored 0 · canceled 0 · expired 0` — a
total of **1** request, of which **1 succeeded**.
**Result:** `succeeded`, with a provider message identity and usage of 16 input / 4 output / 0
cached tokens.

The status and result reads were performed by a **process with no connection to the one that
created the batch**, reconstructing everything from the durable identity alone. That is the
property Contract B recovery actually depends on, and it is why the retrieval was deliberately run
separately rather than in the same process.

**No retry, fallback or resubmission occurred at any point** — including after the first polling
attempt timed out at 15 minutes with the batch still `in_progress`. That timeout was resolved by
**continuing to poll the same batch**, which is retrieval, not resubmission. Resubmitting would
have created a second logical execution on a provider that offers no idempotency key, and would
have invalidated the qualification it was meant to complete.

## Latency observation

The batch reached a terminal state **approximately 44.9 minutes** after submission, for a single
request with a 16-token input and a 16-token output ceiling.

**This is a single observation and is not a provider SLA.** It is not a measured average, a
percentile, or a commitment, and it must not be cited as any of those. Anthropic documents that
most batches complete within an hour, with a 24-hour ceiling; this run sits inside that envelope
and says nothing more general than that.

**What it does establish** is that M2-ADR-016 §6 rule 2 is load-bearing rather than theoretical. A
Contract-B `DIAGNOSE` cannot be synchronous, and the `PENDING` requirement is a real product cost —
a learner will wait, and the platform must be able to say so. When the first Contract-B route is
proposed, this number belongs in front of whoever owns the learner experience, presented as one
observation rather than as a guarantee.

## Three things this evidence does not establish

Kept apart deliberately, because they are easy to collapse into one another and the collapse is
always in the optimistic direction.

**1. Provider capability is qualified. Provider *exactly-once admission* is not.** Anthropic
documents **no replay-safe admission or idempotency guarantee** on batch creation, and this run
does nothing to change that — a successful create acknowledgement was received, so the ambiguous
window was never entered. M2-ADR-016 §3 and the [amended Definition of
Done](mvp2-contract-b-definition-of-done.md) stand unchanged: RAMALS claims **single-intent
provider submission with detectable duplicate provider execution and exactly-one authoritative
outcome adoption**, and never provider-level exactly-once.

**2. Cancellation remains unqualified.** It was not attempted. A separate minimal batch would have
been required, and the qualification batch reached a terminal state before it could have been
safely cancelled. Manufacturing a cancellation result from a completed batch would have been
fabricated evidence. The `cancellation: true` row in the adapter's capability declaration therefore
rests on the published provider contract and the SDK surface, **not on a qualified observation**,
and closing it needs its own run.

**3. The lost-acknowledgement window is unexercised.** This run had a clean create. The
reconciliation sweep that compensates for absent replay-safe admission — enumerate batches, match
on `custom_id` — is designed and specified but has not been exercised against a real ambiguous
create. That belongs to the Contract B T15 crash matrix, not here.

## Evidence handling

The full machine-readable record is `qualification-evidence.json`, retained outside the repository
alongside the throwaway submit and poll scripts. Only this summary is committed, following the
precedent set for T15 evidence bundles, which are likewise retained on disk rather than tracked.

**No credential or key material appears in any committed artifact.** The API key was read from the
user-scope environment, injected into a subprocess, and never printed, logged or written to a file;
the evidence record was scanned for credential patterns before this document was written, and
matched none.

The batch remains retrievable from the provider until its documented expiry, so the observations
above can be independently re-checked against the same durable identity within that window.

## Status after this run

| Prerequisite | MVP / research | Production |
| --- | --- | --- |
| 1 — provider capability proven in non-production | ✅ **Satisfied** | Not assessed |
| 2 — Contract B DoD amended | ✅ Satisfied | ✅ Satisfied |
| 3 — data-classification sign-off | ✅ Satisfied | ❌ Requires reassignment |
| 4 — encryption mechanism and custody | ✅ Satisfied | ❌ Custody open |
| 5 — purge mechanism testable | ❌ Open — specified, not built | ❌ Open |

`V037` remains blocked on prerequisite 5 and on M2-ADR-018's engineering acceptance criteria. No
route is Contract B, no durable execution state exists, and this run authorizes neither.
