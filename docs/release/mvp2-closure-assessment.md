# MVP-2 closure assessment

- **Assessed:** 2026-08-28 at `bc4ed21` (after `#182`).
  **Reassessed 2026-08-29 at `1ffe322`** (after `#184`–`#188`), and again after the criterion 9
  artefacts were written.
- **Verdict:** **CLOSED — PASS_WITH_ACCEPTED_DEBT.** All nine Contract B Definition-of-Done criteria
  are satisfied: seven PASS and two carried as explicit, bounded, named debt. No criterion was
  amended, reinterpreted or weakened to reach this.
- **Movement:** 4 PASS · 1 ACCEPTED_DEBT · 4 BLOCKED (`bc4ed21`) →
  **7 PASS · 2 ACCEPTED_DEBT · 0 BLOCKED** (`#189`). Three blockers closed against real-provider
  evidence; the fourth closed by the accountable owner's approval.
- **Closure does not authorize route activation.** `ramals.contract-b.enabled` stays `false`, and the
  approval attaches a binding condition: **residual S2 must be resolved and separately reviewed
  before the route may be activated in any environment.**
- **Scope:** the whole of MVP-2, assessed against the amended
  [Contract B Definition of Done](mvp2-contract-b-definition-of-done.md), M2-ADR-016 through
  M2-ADR-020, and the evidence produced by `#170` through `#188`.
- **Authorizes:** nothing. No route changes, no descoping, no criterion amendment.

## The verdict in one paragraph

*(Original assessment, kept: three of the four blockers below are now closed. See
[Reassessment](#reassessment-2026-08-29-at-1ffe322).)*

Everything in MVP-2 except Contract B is complete and evidenced. Contract B has a working durable
lifecycle, encrypted persistence, a purge mechanism and a crash-recovery qualification — and it is
still four criteria short. Three of those four trace to one missing mechanism: **Anthropic batch
enumeration matched on `custom_id`**. Without it a duplicate provider execution created by a lost
acknowledgement is invisible, so it cannot be detected (criterion 3), its cost cannot be accounted
(criterion 8), and the real-provider crash matrix that would exercise the window (criterion 7) has
nothing to prove recovery *against*. The fourth, runbooks and approval (criterion 9), has not been
started.

**MVP-2 could close today only by descoping Contract B.** That is a product and governance decision,
not an engineering one, and this document does not make it. What it does is state precisely what
closing *with* Contract B in scope would cost.

## Reassessment 2026-08-29, at `1ffe322` — and closure at `#189`

**All nine criteria are now satisfied.**

`#184`–`#188` closed criteria 3, 7 and 8, all three against real-provider evidence rather than
argument. `#189` wrote the criterion 9 artefacts — [runbook](../architecture/contract-b-operational-runbook.md),
[security review](mvp2-contract-b-security-review.md),
[performance characterization](mvp2-contract-b-performance-characterization.md) — and the
**[approval record](mvp2-contract-b-approval.md) is signed**: approved 2026-08-29 by the Platform
Data Owner, Sunil Pandey, for the RAMALS MVP / research environment only.

Criterion 9 says *approved*, not *documented*, and the distinction was held: the artefacts were
prepared and left unsigned, and the approval was given separately by the accountable owner after
they existed to be read. That is why criterion 9 is `PASS` rather than something softer.

Two defects found after the first assessment are worth recording because they were invisible to a
green suite and would each have blocked Contract B completely in production: reconciliation sent an
empty `X-Interaction-ID` and was refused with 400 (**D1**), and the durable transport offered an
HTTP/2 upgrade uvicorn refuses, so no submission could be accepted (**D2**). Both are fixed in
`#187` with HTTP-boundary regression tests. They are the reason the first real end-to-end run
mattered more than the qualification it was nominally performing.

### Reassessed matrix

| # | Criterion | `bc4ed21` | `1ffe322` | What changed |
| --- | --- | --- | --- | --- |
| 1 | Result retrieval and status lookup proven | PASS | **PASS** | — |
| 2 | At most one *adopted* provider execution | PASS | **PASS** | Strengthened: a real `MULTIPLE` was refused adoption (W2 P4) |
| 3 | Lost ack reconciled; duplicate **detected and auditable** | BLOCKED | **PASS** | `#184` enumeration + `V038`; W2 P4 detected two real batches under one `custom_id`, recorded both with usage, adopted neither |
| 4 | Terminal result retrievable after process death | PASS | **PASS** | — |
| 5 | Spring/PostgreSQL persists before adoption | PASS | **PASS** | — |
| 6 | Workers durable, fenced, **observable** | ACCEPTED_DEBT | **ACCEPTED_DEBT** | The scheduled path has now run against a real provider plane; metrics and alerting still absent (W4) |
| 7 | Crash matrix at every boundary, **incl. lost-ack window** | BLOCKED | **ACCEPTED_DEBT** | The window the criterion names is real-provider proven three times (W2 P2 twice, plus an end-to-end orphan recovery through production wiring). Nine kill points remain deterministic-only — see below |
| 8 | Cost evidence accounts for **every** execution | BLOCKED | **PASS** | `V038` observations carry identity, outcome and usage for every discovered execution, adopted or not |
| 9 | Security, performance, operational runbooks **approved** | BLOCKED | **PASS** | Artefacts written (`#189`); **approved 2026-08-29** by the Platform Data Owner for the MVP/research scope, with a binding condition on route activation |

**Score: 7 PASS · 2 ACCEPTED_DEBT · 0 BLOCKED.**

### Why criterion 7 is ACCEPTED_DEBT and not PASS

The DoD requires qualification to *"explicitly exercise the lost-acknowledgement window"* and that is
done. The bounded, named shortfall is that the other nine kill points remain fake-based. They are
carried rather than blocking because the risk a fake carries is that it **models the provider
wrongly**, and that risk is now bounded by where it can apply:

- **K1, K7–K10** involve **no external side effect at all** — admission, crypto, database writes, the
  adoption transaction. They already run against real PostgreSQL. A real provider adds nothing.
- **K4–K6** depend on provider *operations* — status lookup and result retrieval by known identity —
  that are independently real-proven by prerequisite 1 and W2 P1.
- **K2–K3**, the lost-acknowledgement window, are the ones that genuinely needed a real provider, and
  they have it.

The specific channel by which a fake previously hid a defect — disagreeing with the adapter's state
vocabulary, which is how W2 defect 1 shipped — is now closed structurally by
`ContractBProviderStateVocabularyContractTests`, which reads the vocabulary out of the Python source.

### Why P5 is not required for closure

**No criterion asks for it.** Criterion 4 is the only one naming process death, and it is already
PASS on real cross-process evidence from prerequisite 1 — a result retrieved from a different process
about 45 minutes after the submitting process ended. Criterion 7 names *external-side-effect
boundaries*, not OS-process granularity, and its "state reconstructable from PostgreSQL" invariant is
proven with fresh object graphs and now by a real end-to-end run whose worker held no prior state.

P5 is assurance depth. It belongs with gap 6 (cross-process concurrency) in the AWS multi-replica
phase, and carrying it weakens nothing.

### Criterion 9 — how it was satisfied

The artefacts were written first and the approval record was deliberately left **unsigned**, because
an engineer can write a runbook and cannot approve it on the owner's behalf. Recording an approval
nobody gave would have converted a governance gap into a false governance record — worse than the
gap, and precisely what the Definition of Done's *"not accepted on argument"* rule exists to prevent.
This also ruled out `ACCEPTED_DEBT` for criterion 9: this assessment's own rule bars debt for a
requirement that is simply absent, and the entire content of the criterion is that an accountable
person said yes.

The approval was then given by the accountable owner, in their own words, and transcribed into
[`mvp2-contract-b-approval.md`](mvp2-contract-b-approval.md) without alteration.

**It carries a binding condition**, which is part of the approval and not commentary on it:

> **S2 must be resolved and separately reviewed before `ramals.contract-b.enabled` may be activated
> in any environment. This approval does not authorize Contract-B route activation or production
> deployment.**

S2 is the finding that the submission path treats *any* status the AI plane chooses as proof nothing
was created — latent while the route is off and every submission is supervised, load-bearing the
moment learner traffic arrives unattended. The condition narrows an authority criterion 9 never
granted, rather than qualifying the criterion.

## MVP-2 verdict

**PASS_WITH_ACCEPTED_DEBT.**

Seven criteria PASS. Two are carried as explicit debt, both bounded and named, neither affecting
correctness:

| Criterion | Debt | Why it is appropriate to carry |
| --- | --- | --- |
| **6** — observability | No metrics, no alerting (**W4**) | The append-only transition ledger gives full forensic observability and the runbook's triage section is the manual substitute. Operational signal is required before AWS, not before closure. |
| **7** — crash matrix | Nine kill points remain deterministic-only | The window the criterion names is real-provider proven three times. Five of the nine involve no external side effect at all; three depend on provider operations already real-proven; the fake's provider-vocabulary drift is now structurally guarded. |

Plus the residuals accepted in the approval record (S1, S2, S3/P5, P1, P2, P4, P6/P7), which are
recorded as carried rather than resolved.

**No criterion was amended, reinterpreted, or weakened.** Criteria 3 and 8 were closed by building
the mechanism they required and proving it against the real provider. Criterion 7 moved to bounded
debt on evidence, not on argument. Criterion 9 was closed by an actual human approval, not by
redefining what approval means.

**What closure does not authorize:** route activation (blocked by S2's condition and by the DoD's own
requirement), production deployment (the approval's scope limitation is binding), W4, P5, or AWS
work.

## Closure matrix — Contract B Definition of Done

> **Superseded for criteria 3, 7, 8 and the score.** The table below records the assessment at
> `bc4ed21` and is kept because it is what the follow-up work was planned against. The current
> statuses are in the [reassessment](#reassessment-2026-08-29-at-1ffe322) above.

| # | Criterion | Evidence | Status | Rationale | Follow-up |
| --- | --- | --- | --- | --- | --- |
| 1 | Provider contract independently proves **result retrieval and status lookup** | [Prerequisite 1 qualification](mvp2-contract-b-prerequisite-1-qualification.md) — real Anthropic, `msgbatch_011tPDJFFZbdcr2zR1ibX9DR`, 58 status reads from a separate process, result retrieved ~45 min later | **PASS** | Proven against the real provider on the proposed route, not inferred from documentation. Replay-safe submission is explicitly not claimed and is not required. | — |
| 2 | Every logical request maps to **at most one adopted** provider execution | `ContractBAdoption`; `core.adopt_ai_execution_result`; V037 unique index on `provider_execution_id`; `#181`/`#182` adoption and idempotency tests | **PASS** | Adoption is atomic and idempotent, and the unique index prevents two requests claiming one execution. A lost-acknowledgement orphan can never be adopted, because RAMALS holds no identity with which to retrieve it. | — |
| 3 | A lost acknowledgement is **reconciled, and any duplicate provider execution is detected and auditable** | `#182` K2/K3 — reaches `UNKNOWN_TERMINAL` without resubmission | **BLOCKED** | The first half holds: the DoD's own reconciliation rules sanction an explicit terminal state, and `#182` proves it. The second half does not. RAMALS never learns the orphan exists, so a duplicate is neither detected nor auditable. This criterion was **already amended once** to drop prevention; dropping detection as well would leave nothing. | **W1** |
| 4 | Provider terminal result remains retrievable **after process death** | Prerequisite 1 — retrieved from a different process 45 minutes after the submitting process ended; `#182` K5–K8 | **PASS** | Both halves are evidenced: the provider retains and serves the result, and RAMALS reconstructs the retrieval from PostgreSQL alone. | — |
| 5 | **Spring/PostgreSQL persists the result before adoption**; RAMALS-AI stateless | `V037`; `ContractBResultStore`; `#181` durable router holding nothing; `test_no_database_access.py` | **PASS** | The AI plane has no driver, no store and no memory between calls. Every durable fact is written by the platform. | — |
| 6 | Reconciliation workers are **durable, fenced and observable** | `ai_reconciliation_work` leases; `submit_fence` CAS; `core.ai_execution_transition`; `#182` sweep tests | **ACCEPTED_DEBT** | Durable and fenced are proven. Observable is **partially** met: the append-only transition ledger records every state change with actor, fence and reason, which is strong forensic observability — but there are **no metrics and no alerting**, and the scheduled path has never run in a deployed environment. Adequate for a research environment where the ledger is queryable; not adequate for operations. | **W4** |
| 7 | Crash matrix proves recovery at every external-side-effect boundary, **including the lost-acknowledgement window** | [Crash qualification](mvp2-contract-b-crash-qualification.md) — 10 kill points, 16 tests, all PASS | **BLOCKED** | The matrix exists, is thorough, and found three real defects. It runs against a **scripted fake**. The DoD holds this evidence to the S1–S4 standard — a real deployment, a real fault, a durable evidence bundle — and says Contract B "is not accepted on argument". A fake-based matrix is nearer to argument than to qualification. | **W2** |
| 8 | **Cost evidence accounts for every provider execution** attributable to one logical request, and surfaces any duplicate | `ai_provider_execution` usage and cost columns, written from the retrieved record | **BLOCKED** | Cost is recorded for every execution RAMALS *knows about*. An orphan created by a lost acknowledgement runs and bills with no row, so the accounting is incomplete by exactly the amount that matters. Same root cause as criterion 3. | **W1** |
| 9 | Security, performance and operational **runbooks are approved** | None | **BLOCKED** | Not started. No Contract B runbook exists, and no security or performance review of the Contract B path has been performed. `docs/architecture/observability-runbook.md` predates Contract B and does not cover it. | **W3** |

**Score: 4 PASS · 1 ACCEPTED_DEBT · 4 BLOCKED** — at `bc4ed21`. Superseded; see the reassessment above.

## Closure matrix — the rest of MVP-2

| Area | Evidence | Status |
| --- | --- | --- |
| T02 transactional outbox | [evidence](mvp2-t02-transactional-outbox.md) | **PASS** |
| T03 durable dispatcher | [evidence](mvp2-t03-durable-dispatcher.md) | **PASS** |
| T04 AI provenance v2 | [evidence](mvp2-t04-ai-provenance-v2.md) | **PASS** |
| T05 grounded context contract | [evidence](mvp2-t05-grounded-context-contract.md) | **PASS** |
| T06/T07 grounding retrieval and validation | [evidence](mvp2-t06-t07-grounding-retrieval-validation.md) | **PASS** |
| T08 diagnostic agent | [evidence](mvp2-t08-diagnostic-agent.md) | **PASS** |
| T09 diagnostic assessment gate | [evidence](mvp2-t09-diagnostic-assessment-gate.md) | **PASS** |
| T10 diagnostic end-to-end evaluation | [evidence](mvp2-t10-diagnostic-e2e-evaluation.md) | **PASS** |
| T11 assessment evaluation agent | [evidence](mvp2-t11-assessment-evaluation-agent.md) | **PASS** |
| T12 evaluation proposal gate | [evidence](mvp2-t12-evaluation-proposal-gate.md) | **PASS** |
| T13 learner evaluation feedback | [evidence](mvp2-t13-learner-evaluation-feedback.md) | **PASS** |
| T14 controlled orchestration | [evidence](mvp2-t14-controlled-orchestration.md) | **PASS** |
| T15 Contract A single-submission fail-closed execution | S1–S4 all PASS on the frozen candidate; `#163`–`#166` | **PASS** |
| TD-M2-SEC-01 rate-limit trust boundary | [debt register](mvp2-technical-debt.md) | **ACCEPTED_DEBT** — recorded, explicitly deferred, not represented as fixed |

Contract A is complete and qualified. **Nothing outside Contract B blocks closure.**

## The six named gaps, classified

The question for each is not "is this bad?" but "does an unmet DoD criterion depend on it?"

| # | Gap | Classification | Why |
| --- | --- | --- | --- |
| 1 | Lost acknowledgement is **recorded** as `UNKNOWN_TERMINAL`, not recovered | **(2) Bounded accepted debt** | The DoD sanctions this outcome in terms: reconciliation must prove it "either recovers the original provider execution **or** reaches an explicit terminal state, and in neither case silently submits a replacement." An explicit terminal state is the second branch, and `#182` proves it. The cost is a **provider execution that runs, bills and is never adopted** — bounded by the 24-hour batch expiry and by the volume of a research environment. Not a blocker on its own. |
| 2 | **Duplicate after lost acknowledgement is not detectable** | **(1) Genuine MVP-2 blocker** | Criterion 3 requires detection and auditability; criterion 8 requires the cost to be accounted. Both are unmet, and criterion 3 has already been amended once to drop *prevention*. Dropping *detection* would empty it. This is the gap that blocks closure. |
| 3 | **No Anthropic enumeration / `custom_id` reconciliation** | **(1) Genuine MVP-2 blocker** | Not a separate gap so much as the mechanism gap 2 needs. `client.messages.batches.list()` exists and M2-ADR-016 Addendum A records it; nothing in RAMALS calls it. It is also what would give criterion 7's real-provider matrix something to prove recovery against. |
| 4 | **Real-provider timeout / partial-response not crash-qualified** | **(1) Genuine MVP-2 blocker** | Criterion 7 as written, at the S1–S4 standard the DoD sets. `#182` is a real and necessary step and is not that. |
| 5 | **Cancellation unqualified** | **(3) Production-hardening debt → AWS qualification** | No DoD criterion requires cancellation. M2-ADR-016 lists it as a non-mandatory capability row, and prerequisite 1 recorded it as NOT ATTEMPTED rather than claiming it. The Python adapter implements it; no lifecycle path calls it, so nothing depends on behaviour that was never proven. |
| 6 | **True cross-process concurrent reconciliation not exercised** | **(3) Production-hardening debt → AWS qualification** | Criterion 6 asks for durable and fenced, both proven by CAS and lease tests. Genuine simultaneity is a scale property that a single-instance research environment cannot produce and does not encounter. It belongs with the AWS multi-replica qualification. |

**Three gaps block closure and they are one problem**: gaps 2, 3 and 4 all resolve through enumeration
plus a real-provider run. Gaps 1, 5 and 6 are correctly carried.

## Minimum remaining work before closure

> **W1 and W2 are complete; W3's artefacts are written and await signature; W4 remains debt.**
> Delivery record:
>
> | | Delivered by | Evidence |
> | --- | --- | --- |
> | **W1** | `#184` | M2-ADR-020, `V038`, `custom_id` enumeration |
> | **W2** | `#185`, `#186`, `#188` | [W2 real-provider qualification](mvp2-contract-b-w2-real-provider-qualification.md), [defect-3 qualification](mvp2-contract-b-w2-defect3-qualification.md) — five defects found and fixed, then PASS |
> | **W3** | `#189` | [runbook](../architecture/contract-b-operational-runbook.md), [security review](mvp2-contract-b-security-review.md), [performance characterization](mvp2-contract-b-performance-characterization.md), and the signed [approval record](mvp2-contract-b-approval.md) |
> | **W4** | not started | Accepted debt under criterion 6 |
>
> The original plan is kept below as written.

Ordered by dependency. Nothing here is optional, and nothing outside it is required.

### W1 — Anthropic batch enumeration and `custom_id` reconciliation
*Unblocks criteria 3 and 8. Prerequisite for W2.*

Enumerate provider batches over the admission window and match on the server-derived `custom_id`,
per the DoD's reconciliation requirements: *"never by position, never by timestamp proximity, and
never by a value a caller supplied."* Record **every** provider execution attributable to a request
identity, with its usage and cost, so a duplicate appears in the evidence rather than being inferred
from a discrepancy later.

Two outcomes must be distinguishable and both recorded: an orphan recovered (its identity adopted
into the durable row), and an orphan found *alongside* an already-adopted execution — which is the
duplicate criterion 3 exists to surface.

This is new mechanism, and it needs a decision recorded before it is built: enumeration is a paged,
rate-limited call over a window, and what the window is, how often it runs, and what happens when it
finds two live executions for one request are all unspecified. **An ADR precedes the code.**

### W2 — Real-provider crash qualification, including an induced lost acknowledgement
*Unblocks criterion 7. Depends on W1.*

The S1–S4 analogue: a deployed candidate, a real Anthropic batch, a genuinely interrupted
acknowledgement, and a durable evidence bundle recording the provider execution identity or
identities observed, the usage and cost of each, and the reconciliation path taken. Must also cover
a real timeout and a real partial response.

Cost is bounded and small — batches of one or two minimal requests — but it is not zero, and it
needs the same explicit spend authorisation prerequisite 1 was given.

### W3 — Contract B runbooks and approval
*Unblocks criterion 9. Independent of W1 and W2; can proceed in parallel.*

Operational runbook (what an operator does when an execution is `UNKNOWN_TERMINAL`, when the purge
fails, when a key is unavailable), security review of the Contract B path, and performance
characterisation. Approved by the named accountable owner, as M2-ADR-018's governance section
required for classification.

### W4 — Reconciliation observability *(accepted debt, not a blocker)*
Metrics and alerting for the worker: executions swept, orphans re-queued, lost acknowledgements
recorded, purge failures. The transition ledger already carries the forensic record; what is missing
is the operational signal. Required before AWS, not before closure.

## What must not happen

- **No DoD criterion may be amended to reach closure.** Criteria 3 and 8 were amended once already,
  against evidence about the provider's actual capabilities. Amending them again against evidence
  about RAMALS' own incompleteness would be a different act wearing the same clothes.
- **No route may be activated.** Unchanged by closure, and now doubly binding: the DoD permits
  activation only after qualification, and the criterion 9 approval attaches an explicit condition
  that **S2 must be resolved and separately reviewed first**, in any environment.
- **`#182` must not be cited as the crash matrix.** It is a fake-based recovery qualification, it
  says so, and criterion 7's real-provider evidence is the W2 documents, not `#182`.
- **`PASS_WITH_ACCEPTED_DEBT` must not be read as `PASS`.** Two criteria are carried as debt and the
  approval carries seven named residuals. Closure records what was accepted, not what was finished.

## Revisit triggers

- ~~W1 lands, at which point criteria 3 and 8 should be re-assessed rather than assumed.~~
  **Fired 2026-08-29.** W1 landed in `#184` and criteria 3 and 8 were re-assessed against
  real-provider evidence rather than assumed. Both are now PASS.
- **S2 is resolved**, at which point the route-activation condition attached to the criterion 9
  approval is discharged — by a separate review, not by this document.
- **The Contract B path changes materially** — lifecycle, persistence model, access matrix, retention
  rules or reconciliation mechanism — at which point the criterion 9 approval is invalidated and
  requires re-approval, and this closure must be re-assessed rather than assumed to carry forward.
- **A second reconciliation worker is deployed**, which invalidates the per-process assumptions
  behind P1 and the performance characterization.
- Contract B is descoped from MVP-2 by a recorded product decision, at which point this assessment
  reduces to the non-Contract-B matrix, which passes.
- The provider publishes replay-safe admission, which would reopen M2-ADR-016 and change what
  criterion 3 can claim.
