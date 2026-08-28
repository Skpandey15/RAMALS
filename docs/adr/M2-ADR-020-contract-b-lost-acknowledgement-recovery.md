# M2-ADR-020: Contract B lost-acknowledgement recovery by `custom_id` enumeration

- **Status:** Accepted — 2026-08-28.
- **Decides:** the search window, the pagination and cost bounds, the zero/one/multiple-match
  semantics, and the duplicate audit semantics for recovering a provider execution whose
  acknowledgement was lost.
- **Relates to:** M2-ADR-016 §3 (correlation by `custom_id`, never by position), M2-ADR-017 §4/§5,
  the [Contract B Definition of Done](../release/mvp2-contract-b-definition-of-done.md) criteria 3
  and 8, and the [MVP-2 closure assessment](../release/mvp2-closure-assessment.md) work item W1.
- **Does not authorize:** route activation, real-provider qualification, or any claim that criteria 3
  and 8 are satisfied. It specifies the mechanism; the mechanism must then be built and qualified.

## Context

A Contract B execution can reach `SUBMITTED` with no `provider_execution_id`: the write-ahead claim
committed, the provider was called, and the acknowledgement never arrived. RAMALS holds a durable row
saying "sent" and no name for what was sent.

Until now that was terminal. `#182` proved it reaches `UNKNOWN_TERMINAL` without resubmitting, which
is honest and is what M2-ADR-016 permits — but it leaves a provider execution that runs, bills and is
never adopted, and it leaves Definition-of-Done criteria 3 and 8 unsatisfiable, because a duplicate
RAMALS never learns about cannot be detected or costed.

Anthropic supports workspace-level enumeration through `GET /v1/messages/batches`. That is the
capability this ADR builds on. It is not a new provider mechanism and nothing here infers one.

### The constraint that shapes everything

**Batch list metadata does not contain `custom_id`.** Verified against `anthropic==1.1.0` rather than
assumed:

```
MessageBatch fields:              archived_at, cancel_initiated_at, created_at, ended_at,
                                  expires_at, id, processing_status, request_counts, results_url, type
MessageBatchIndividualResponse:   custom_id, result
```

So a batch cannot be correlated from the listing. Correlation requires **fetching that batch's
results and finding the `custom_id` in them** — which is a second network call per candidate, over a
JSONL stream, and is why this ADR spends most of its length on bounds.

It also means **a batch that has not ended cannot be correlated at all**: `results_url` is null while
`processing_status` is `in_progress`, so there is nothing to read. That single fact drives the
match semantics below, because "not matched" and "not yet inspectable" are different answers and
conflating them fails open.

## Decision

### 1. The search window is anchored on RAMALS' own durable `submitted_at`

The window is `[submitted_at − skew, submitted_at + skew]`, default **skew 1 hour**.

Anchored on RAMALS' record rather than on a scan of recent batches, because `submitted_at` is
durable, is written before the provider is called, and is the one timestamp that is definitely
correct about when the lost call happened. A window derived from "recent batches" would widen with
traffic and would make the search cost depend on unrelated activity.

The skew exists for clock difference between RAMALS and the provider and for the gap between the
write-ahead claim and the request actually landing. One hour is far wider than either and still
bounds the candidate set tightly.

**Batches outside the window are not inspected.** A batch created two days from the submission is not
this request's, and inspecting it would cost a results fetch to learn nothing.

### 2. The search horizon — when zero may be believed

**Zero matches is only conclusive when every candidate in the window was actually inspected.**

A candidate still `in_progress` has no results to read. Reporting the search as ZERO while such a
candidate exists would assert that no orphan exists at the exact moment one is most likely running.
So the search reports four outcomes, not three:

| Outcome | Meaning |
| --- | --- |
| `ZERO` | Every candidate in the window was inspected; none carried the `custom_id`. |
| `ONE` | Exactly one provider execution carried it. |
| `MULTIPLE` | More than one did. |
| `INCONCLUSIVE` | Some candidate could not be inspected — still processing, results unreadable, or a bound was hit. |

`INCONCLUSIVE` is **not** an error and **not** a terminal answer. It means try again later.

**The horizon is 26 hours from `submitted_at`** — the provider's 24-hour processing deadline plus a
two-hour margin. Past it, every batch created in the window has necessarily ended, so a candidate
that is still uninspectable is unreadable for some other reason and waiting longer will not help.

- `ZERO` at any time → the execution becomes `UNKNOWN_TERMINAL`. Nothing was found and nothing more
  can be found.
- `INCONCLUSIVE` before the horizon → stay non-terminal, retry.
- `INCONCLUSIVE` at or past the horizon → `UNKNOWN_TERMINAL`, recorded as horizon-exhausted rather
  than as zero. The distinction is kept in the ledger because the two describe different worlds: one
  where we looked and there was nothing, and one where we looked and could not see.

### 3. Pagination and cost bounds

Enumeration is paged and every inspection is a network call, so an unbounded search is a way to spend
an afternoon and a rate limit on one lost acknowledgement.

| Bound | Default | Why |
| --- | --- | --- |
| Page size | 100 | The provider's maximum; fewer pages for the same coverage. |
| Maximum pages per search | 10 | 1000 batches, far beyond what a one-hour window can hold in a research environment. |
| Maximum result inspections per search | 50 | The expensive call. A window containing more than 50 candidates means the window or the traffic assumption is wrong, and the honest answer is `INCONCLUSIVE`, not a longer search. |

**Hitting a bound yields `INCONCLUSIVE`, never `ZERO`.** A truncated search that reported zero would
be asserting a conclusion it did not reach. The result records which bound was hit.

Pagination walks **backwards from newest** using `before_id`, and stops as soon as a page's batches
are all older than the window. Newest-first because a lost acknowledgement is recovered soon after it
happens; a search that started at the beginning of the workspace's history would page through every
batch ever created to reach the relevant hour.

### 4. Zero / one / multiple semantics

**Exactly one match is adopted, fenced.** The recovered `provider_execution_id` is written to the
durable row under the same fence discipline as an ordinary submission, so a worker whose fence has
been superseded cannot write a recovered identity over another worker's. Normal reconciliation then
resumes: the execution is no longer an orphan, it has a name, and it is polled like any other.

**Multiple matches are never resolved by choosing.** More than one provider execution carrying one
`custom_id` is precisely the duplicate that criterion 3 exists to surface. RAMALS records every one
of them, marks the execution as carrying a duplicate-provider condition, and adopts none.

Choosing would be the tempting error and it is unsafe in both directions: adopting the first would
attribute a learner's diagnosis to an arbitrary execution, and adopting the newest would silently
prefer a duplicate over the original. There is no rule that makes the choice correct, because the
information needed to make it does not exist on this side.

An execution with a duplicate condition becomes `UNKNOWN_TERMINAL` and requires an operator. That is
a deliberate refusal to automate a decision nobody has specified.

### 5. Duplicate audit semantics — every discovered execution is recorded

**Every provider execution discovered for a request is recorded, with its identity, outcome and
usage, whether or not it is adopted.** One row per discovery in a dedicated table, added by `V038`.

Recorded rather than merely counted, because criterion 8 asks for cost evidence that *accounts for
every provider execution attributable to one logical request*. A duplicate that is known to exist but
whose tokens are not recorded is a duplicate that is visible in a log and invisible in the bill.

The observation table is deliberately separate from `core.ai_provider_execution`:

- that table is one row per RAMALS request, keyed on `request_id`, with a unique index on
  `provider_execution_id` — by construction it cannot hold two executions for one request, and
  widening it to allow that would destroy the invariant that makes duplicates detectable at all;
- an observation is a different fact with a different lifecycle. It says "at this time, this provider
  execution was found carrying this request's `custom_id`", and it stays true afterwards.

Observations hold identifiers, counts and timestamps only. No model output, so the `V023` structural
guarantee extends to them unchanged.

### 6. Reconciliation still never submits

Nothing in this ADR gives a recovery path a reason to call the provider's create endpoint. Enumeration
reads; adoption writes to RAMALS' own row. The rule from M2-ADR-016 §4 is unchanged and this
mechanism exists precisely so that a lost acknowledgement has an answer that is not a resubmission.

## Alternatives rejected

- **Correlating from batch list metadata.** Impossible, not merely unwise: the listing carries no
  `custom_id`. Any scheme built on metadata would be matching on creation time, which is guessing
  with extra steps and would attribute one learner's execution to another learner's request.
- **Treating an uninspectable candidate as a non-match.** The cheapest reading and a fail-open: it
  reports "no orphan" at the moment an orphan is most likely to be running.
- **Adopting the first or the newest of several matches.** Automates a decision no ADR has made, and
  is wrong in both directions.
- **Scanning all batches without a window.** Cost grows with unrelated workspace activity, and the
  extra coverage buys nothing — a batch created a week away from the submission is not the one.
- **Widening `core.ai_provider_execution` to hold several executions per request.** Would remove the
  unique index that makes a duplicate detectable, in the name of recording duplicates.
- **Recording duplicates only in the transition ledger.** Auditable but not costed: `reason` is a
  64-character code, not a place for usage, and criterion 8 asks for the tokens.

## Consequences

- **Criteria 3 and 8 become satisfiable**, which they were not. They are not thereby satisfied:
  this ADR specifies a mechanism, and the mechanism must be built and then qualified against a real
  provider before either criterion may be claimed.
- **A lost acknowledgement gains a second possible ending.** It was always `UNKNOWN_TERMINAL`; now it
  may instead be recovered and completed. `UNKNOWN_TERMINAL` remains the outcome whenever the search
  is conclusive-and-empty, exhausted, or ambiguous.
- **Reconciliation gains a network cost per orphan**, bounded by §3 and paid only for executions that
  actually lost an acknowledgement.
- **An operator gains a new decision to make** when a duplicate is found. That is the point: the
  alternative was not having the information.
- **The duplicate case is unqualified against a real provider.** Producing two genuine Anthropic
  batches carrying one `custom_id` requires inducing the failure this mechanism exists to detect,
  which is W2's work and is not attempted here.

## Revisit triggers

- Anthropic publishes request-level `custom_id` in batch list metadata, which would remove the
  results-fetch cost and most of §3.
- Anthropic publishes replay-safe admission, which would make the lost-acknowledgement window
  preventable rather than merely recoverable, and would reopen M2-ADR-016.
- The observed candidate count in a one-hour window approaches the inspection bound, which means the
  window or the bound no longer matches the traffic.
- A rule for choosing among multiple matches is ever proposed — it would need its own ADR, and would
  have to explain what makes the choice correct.
