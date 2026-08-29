# Contract B — W2 real-provider crash/recovery qualification

- **Status:** **P1–P4 PASS against the real Anthropic API. P5 partial.** One open finding
  (defect 3, request-rate) is unresolved and is design work rather than a patch.
- **Performed:** 2026-08-28, against the real Anthropic Message Batches API in a non-production
  environment, on the merged W1 implementation (`#184`).
- **Authorises:** nothing. No route is activated, no Definition-of-Done criterion is claimed
  satisfied, and the [closure assessment](mvp2-closure-assessment.md) verdict is unchanged.
- **Spend:** ~13 real batches, **≈ $0.0005**, against an approved $1.00 ceiling and a $0.10 abort
  threshold. Cost was never the constraint.

## Why this document leads with the defects

W2 found **five real defects** in code that had passed 51 green tests and shipped to `main`. Three of
them meant Contract B **could not have completed a single real execution**, and one made the
lost-acknowledgement recovery actively harmful. The phases passing matters less than that.

The closure assessment predicted that a fake-based qualification could not substitute for a real
provider. That prediction is now evidenced rather than argued.

## Phase results

| Phase | Result | Evidence |
| --- | --- | --- |
| **P1** — normal submission → terminal result | **PASS** | `msgbatch_01TkRScY9F8FpfMr1diCPNtP` · `SUCCEEDED` · 228 in / 198 out · ciphertext stored, 379 bytes, `contract-b-key-v1`, `diagnostic-proposal.v1` |
| **P2** — induced lost acknowledgement → recovery | **PASS** | `msgbatch_014sksb4trQcb2fXtuB8ArvC` recovered by `custom_id` enumeration; `identityMatches: true`; **one** provider submission |
| **P2a** — `INCONCLUSIVE` while a candidate cannot be inspected | **PASS** | Early search: 17 listed, 12 inspected, **1 uninspectable** → `INCONCLUSIVE`, not `ZERO` |
| **P3** — unreachable plane, real timeout | **PASS** | Both `UNKNOWN_TERMINAL`, no provider identity, **no resubmission**; acceptance never inferred from a timeout |
| **P4** — `MULTIPLE` from two induced duplicates | **PASS** | `msgbatch_01QVM9KynQZvBqmuSvdyGq8R` + `msgbatch_0151yA54AQ5qhbxGTHGG5Eq5` both recorded as observations, **neither adopted**, `DUPLICATE_PROVIDER_EXECUTION`, `UNKNOWN_TERMINAL` |
| **P5** — restart across process boundary | **PARTIAL** | Recovery is proven from PostgreSQL with entirely fresh repositories, stores, adoption boundaries and services. It was **not** run across a separate OS process. |

### P2 in full — the lost-acknowledgement chain

The transition ledger, verbatim:

```
ADMITTED            (ADMITTED)
EVENT               (OBSERVED_msgbatch_014sksb4trQcb2fXtuB8ArvC)
SUBMITTED           (IDENTITY_RECOVERED)
RECONCILING         (RECONCILE_STARTED)
EVENT               (RESULT_STORED)
SUCCEEDED           (PROVIDER_ENDED)
```

The batch id was captured **out of band** by the harness before the acknowledgement was discarded, so
"enumeration found the right batch" is a checked claim rather than a plausible one. RAMALS never held
that id until enumeration recovered it.

### P4 in full — the duplicate refusal

```
ADMITTED            (ADMITTED)
EVENT               (OBSERVED_msgbatch_0151yA54AQ5qhbxGTHGG5Eq5)
EVENT               (OBSERVED_msgbatch_01QVM9KynQZvBqmuSvdyGq8R)
EVENT               (DUPLICATE_PROVIDER_EXECUTION)
UNKNOWN_TERMINAL    (DUPLICATE_PROVIDER_EXECUTION)
```

`provider_execution_id` remains null: **neither execution was adopted**. Both are recorded as
observations with their usage, so the duplicate is auditable and costed rather than merely noticed.
An operator is required, which is the deliberate refusal to automate a choice no ADR has specified.

**The duplicate condition in P4 is deliberately induced** — the harness creates two batches under one
`custom_id` on purpose. It is **not** a duplicate arising from a real retry or a real lost
acknowledgement, and must never be represented as one.

## The five defects

Each was invisible to the fake-based suite, and in every case the fake agreed with an assumption
rather than with the implementation.

### 1. The lifecycle did not recognise the state its own adapter emits

The Python adapter maps `processing_status: "ended"` to **`RESULT_AVAILABLE`**. The Java lifecycle
matched `SUCCEEDED`, `ENDED`, `COMPLETED`. No overlap — so a finished batch was never recognised and
every execution polled forever.

Missed because `FakeDurableExecutionPort.succeedsWith()` set `"SUCCEEDED"`, a word the adapter never
produces. Fixed, the fake pinned to the adapter's vocabulary, and guarded by
`ContractBProviderStateVocabularyContractTests`, which reads `_STATE_BY_PROCESSING_STATUS` out of the
Python source and fails if any state is neither an explicit terminal case nor deliberately listed as
non-terminal.

### 2. `custom_id` diverged from the idempotency key

The adapter submits `custom_id = idempotency_key`; the platform stored a **separate** `custom_id` and
correlated on that. Proven directly: the platform's value returned HTTP 500, the adapter's returned
the record.

The Definition of Done already settles this — *"the `custom_id` submitted with the batch is the
server-derived idempotency key"* — so the platform was violating its own definition. The separate
parameter is removed; `custom_id` is now the idempotency key by construction.

### 3. Enumeration exceeds the provider's request-rate limit

```
anthropic.RateLimitError: 429 — exceeds your organization's rate limit of
50 requests per minute (limit_type: Message Batches API)
```

Each search costs roughly `1 + N` API calls, and the search is stateless, so every poll repays the
full inspection cost. With ~45 batches in a one-hour window that is ~36 calls per search; two searches
a minute breaches the limit.

**This is a genuine gap in M2-ADR-020 and is NOT fixed.** That ADR bounds *pages and inspections per
search* but never *request rate across repeated searches*. It is the concrete instance of the risk
recorded in `#184`: the bounds had never met real traffic, and they do not survive it.

### 4. Pagination re-inspected batches and reported a duplicate that did not exist

`SyncPage` **auto-paginates on iteration**, and `before_id` returns the page *immediately before* an
object — with newest-first ordering that walks toward **newer** items. The manual cursor therefore
re-walked ground already covered, the same batch entered the match list twice, and the search
reported `MULTIPLE` for a single real execution.

Ground truth, checked against Anthropic directly: exactly one batch carried the key, one occurrence.

Under M2-ADR-020 a `MULTIPLE` verdict refuses adoption. So the feature built to recover lost
acknowledgements was **stranding recoverable executions and raising false duplicate alarms** — worse
than not having it. The manual cursor is deleted in favour of the SDK's own pagination, and matches
are keyed by batch id so a repeated listing cannot manufacture a duplicate.

### 5. `ONE` was returned from an incomplete search

M2-ADR-020 §2 says an unfinished search cannot conclude. That rule was applied to `ZERO` and **not to
`ONE`** — yet an uninspected candidate may equally be a *second* execution carrying the same key.

Found by P4: two batches were induced under one `custom_id`, the second was still `in_progress` when
the search ran, and the search said `ONE`. The lifecycle adopted the first and the duplicate went
unreported — precisely the outcome §4 forbids.

Now: `MULTIPLE` wins outright (two is already more than one); an incomplete search is `INCONCLUSIVE`
regardless of finding one; `ONE` requires a **complete** search.

## Definition-of-Done criteria — what is and is not evidenced

| Criterion | Status after W2 so far |
| --- | --- |
| **3** — duplicate detected and auditable | **EVIDENCED against a real provider** (P4): two real batches under one `custom_id` were both detected, both recorded with usage, and neither adopted. The duplicate was *deliberately induced*, not produced by a real lost acknowledgement — so the detection mechanism is proven, while its behaviour under a naturally-arising duplicate remains unobserved. |
| **7** — crash matrix including the lost-acknowledgement window | **PARTIAL.** The lost-acknowledgement window is now proven against a real provider (P2), twice. The other nine kill points of `#182` were **not** re-run against a real provider. |
| **8** — cost evidence for every discovered execution | **EVIDENCED.** Observations carry identity, outcome and usage for every discovered execution: P2's recovered execution (228 in / 198 out) and both of P4's duplicates, including the one deliberately not adopted. |

**No criterion is claimed satisfied, and the Definition of Done is unchanged.**

## What remains

1. **Resolve defect 3 (rate limit).** Needs an M2-ADR-020 amendment: request-rate budget, reuse of
   inspection results across attempts, or a narrower default window. This is design work, not a
   patch, and it gates any realistic multi-orphan operation.
2. **P5 across a real OS process boundary**, rather than fresh object graphs in one JVM.
3. **The remaining nine kill points against a real provider**, if criterion 7 is to be claimed in
   full rather than for the lost-acknowledgement window alone.

## Method notes

**The harness is deliberately not committed.** It spends real money, and a throwaway that spends
money should not sit in a repository where it can be run by accident. It was gated on
`RAMALS_W2_QUALIFICATION=true` and deleted after the run.

**No secrets and no model output are recorded here.** The key was verified with a zero-cost
`models.list` call, injected into the process environment, never displayed and never written. Evidence
holds batch identifiers, correlation keys, timestamps, transitions and token counts only.

**The prompt is a fixture.** The model is asked to emit a fixed `diagnostic-proposal.v1` document
rather than to perform a diagnosis: what is under qualification is the durable path, not the agent.
An earlier attempt asked for the word "ok", which the store correctly refused as non-conforming —
that refusal is itself real-provider evidence of the §10 schema fail-closed rule, and is kept.

**Batch turnaround is ~2.5 minutes**, not the ~45 observed in prerequisite 1. The long waits during
this qualification were defects 1 and 4, not the provider.
