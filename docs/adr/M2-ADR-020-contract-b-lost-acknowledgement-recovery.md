# M2-ADR-020: Contract B lost-acknowledgement recovery by `custom_id` enumeration

- **Status:** Accepted — 2026-08-28. **Amended — 2026-08-29** (§2, §3, new §7) after the W2
  real-provider qualification found that the bounds below govern the size of one search and say
  nothing about the *rate* of repeated searches. See [Amendment 1](#amendment-1--request-rate-2026-08-29).
- **Decides:** the search window, the pagination and cost bounds, the request-rate discipline, the
  zero/one/multiple-match semantics, and the duplicate audit semantics for recovering a provider
  execution whose acknowledgement was lost.
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
| `ZERO` | Every candidate in the window has been inspected — in this search or in an earlier search for this request whose negative result is durably recorded under §3.1 — and none carried the `custom_id`. |
| `ONE` | Exactly one provider execution carried it, and the search was otherwise complete. |
| `MULTIPLE` | More than one did. |
| `INCONCLUSIVE` | Some candidate could not be inspected — still processing, results unreadable, a bound was hit, or the pass's inspection budget was spent before the window was covered. |

**Coverage is cumulative across searches for one request; it is not re-established from scratch each
time.** That is what makes §3.2's budget safe rather than a livelock, and it is sound only because a
recorded negative is permanent — see §3.1.

`MULTIPLE` takes precedence over every other outcome, including over an incomplete search: two is
already more than one, and no amount of further looking can reduce it.

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

These bound the size of **one** search. §3.1 and §3.2 bound the cost of **repeating** it, which is
the part the original version of this ADR left unbounded.

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

> **Correction, 2026-08-29.** The `before_id` cursor described above was wrong in practice and is no
> longer used. `before_id` returns the page *immediately before* an object, which under newest-first
> ordering walks toward **newer** items; the search re-inspected batches it had already seen and
> reported a false `MULTIPLE` for a single real execution. The SDK's own auto-pagination is used
> instead, and matches are keyed by batch id so a repeated listing cannot manufacture a duplicate.
> Newest-first remains correct; only the manual cursor was wrong.

#### 3.1 The durable negative memo

**When a batch has ended and its results have been streamed to completion without carrying the
`custom_id`, that fact is recorded durably and the batch is never opened again for that request.**

The cost this removes is the dominant one. Correlation costs one results fetch per candidate, the
search holds no state between attempts, and an `INCONCLUSIVE` search is retried — so a window of
forty-five candidates was re-opening the same forty-five batches every retry until the horizon. That
is what breached the provider's request-rate limit during W2.

Recording the negative is sound because **an ended batch's results are immutable**. A batch that did
not carry the key when fully read will never carry it later, so skipping it is not a shorter search;
it is the same search, not repeated. This is the one and only reason the memo does not weaken §2, and
it is why the precondition is narrow:

**A negative may be recorded only for a batch that had ended and whose results stream was read to
completion.** Never for a candidate that was still processing, whose results were unreadable, whose
stream failed part-way, or that was skipped because a bound or budget was reached. Those remain
*uninspectable* and must be retried, exactly as before. Memoising any of them would fail open in the
precise way §2 exists to prevent: it would let a search report `ZERO` over a candidate nobody ever
read, and a false `ZERO` is terminal.

**The memo is not evidence and must never be confused with it.** It holds no usage, no cost and no
outcome, and it never enters `core.ai_provider_execution_observation`. An observation means *"a
provider execution attributable to this request"* and feeds Definition-of-Done criterion 8; a memo
entry means *"this batch is not this request's"*. Recording the latter as the former would corrupt
the cost evidence with executions that belong to other requests. They live in separate tables, added
by `V038` and `V039` respectively, and the memo is disposable — it may be deleted at any time, at the
cost of one repeated inspection and nothing else.

#### 3.2 The inspection budget is per reconciliation pass, not per search

**A reconciliation pass carries a total inspection budget, spent across every orphan it handles.**
Default **15**. The budget is delivered to a search as its inspection bound, so a pass that has
already spent its budget performs no further enumeration and its remaining orphans wait for the next
pass.

Per pass rather than per search because per-search bounds do not compose: a pass leasing twenty
orphans at fifty inspections each authorises a thousand provider calls, which is the same unbounded
behaviour one search's bound was meant to prevent.

**Budget exhaustion yields `INCONCLUSIVE`, never `ZERO` and never `ONE`** — the same rule as any
other bound, for the same reason. `MULTIPLE` still takes precedence: a duplicate already proven is
not unproven by running out of budget.

**15 is a load-control default, not a proof.** It is not a guarantee that the provider's
organization-wide limit cannot be exceeded, and must never be described as one. Other traffic shares
that limit — Contract A submissions, other workspaces, other processes — so §7 still applies and a
429 remains possible however this is tuned. The number is chosen to leave headroom, not to be a
ceiling: at a thirty-second cadence it is roughly thirty inspections a minute against an observed
limit of fifty requests a minute.

**The budget and the memo are one mechanism and must ship together.** A budget without the memo does
not slow a search down, it prevents it from ever finishing: the same first fifteen candidates would
be re-inspected every pass and the window would never be covered, turning a recoverable execution
into a horizon-exhausted one twenty-six hours later. The memo is what lets a bounded pass *resume*
rather than restart.

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

### 7. Request-rate discipline

Enumeration reads a shared, rate-limited provider. §3 bounds what one search spends; this section
bounds how insistently a failing one is repeated.

**A rate limit is classified distinctly from an outage.** They call for opposite responses — an
outage may be retried on the same cadence, a rate limit must not be — and until this amendment both
arrived at the recovery path as an indistinguishable failure and were retried identically. The
provider's 429 is carried as `PROVIDER_RATE_LIMITED` and surfaced across the AI-plane boundary as
HTTP 429, not as a 500. A 500 says "this service is broken"; the truthful answer is "you are asking
too quickly", and the two send an operator to completely different places.

**`Retry-After` is honoured when the provider supplies it**, clamped to the backoff bounds below. The
provider knows when it will serve again and RAMALS does not.

**Otherwise retries back off exponentially with jitter**, computed from the attempt count already
held in `core.ai_reconciliation_work`. Jitter because a fleet that backs off in lockstep re-converges
on the provider at the same instant, which is a slower way to be rate-limited.

**The backoff is capped.** An uncapped exponential quietly abandons the execution: doubling from
thirty seconds reaches the twenty-six-hour horizon in about a dozen attempts, so the search would
stop being retried long before the horizon that is supposed to end it. The cap keeps recovery
meaningful for the whole window.

**A pass that is rate-limited stops.** The limit is organization-wide, so continuing to the next
orphan in the same pass is asking the same question of the same exhausted quota — it cannot succeed
and it delays recovery for everyone. The pass ends and the next one starts after the backoff.

**Fixed short-interval polling is not a solution and must not become one.** The seventy-five-second
pacing used during W2 qualification was a harness workaround that made one run complete. It bounds
nothing: it does not scale with the number of orphans, does not respond to the provider, and stops
working the moment a pass holds more than one orphan.

**The budget is per process.** Two reconciliation workers are two budgets and roughly twice the
request rate. This is stated rather than solved: a genuinely global budget needs shared coordination
state, which is disproportionate to MVP-2 and would be a distributed rate limiter rather than a
recovery mechanism. Deploying more than one reconciliation worker is a revisit trigger.

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
- **Narrowing the correlation window to reduce cost.** Directly trades the guarantee the window
  exists to provide. The skew is what makes the orphan certain to be inside the searched range;
  narrowing it buys API calls with the risk of a false `ZERO`, which is *terminal* and silently
  converts an undetected duplicate into a closed case. Rejected on the same principle as treating an
  uninspectable candidate as a non-match: both are cheap readings that fail open.
- **A dynamic window that starts narrow and widens on retry.** Implementable, but it must never
  report `ZERO` before the full window has been covered — which needs exactly the cumulative-coverage
  bookkeeping §3.1 already provides, for less benefit and one more way to conclude too early.
- **A global or distributed request-rate limiter.** A shared token bucket across a stateless AI plane
  needs coordination state and becomes infrastructure with its own failure modes. The per-pass budget
  is a single integer that bounds the same thing for the deployment MVP-2 actually has, and §7 states
  the limit of that honestly rather than implying a guarantee.
- **Memoising uninspectable candidates.** The cheapest possible memo and a fail-open: it would let a
  later search claim complete coverage of a batch nobody ever read.
- **Recording negatives in `core.ai_provider_execution_observation`.** Would put executions belonging
  to *other* requests into the table that answers "what did this request cost", corrupting criterion
  8's evidence to save one table.

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
- **More than one reconciliation worker is deployed.** The per-pass budget is per process, so the
  aggregate request rate multiplies and §7's honesty about that becomes a live problem.
- **The number of batches created between a submission and its recovery routinely approaches the page
  bound.** Listing walks from newest and counts everything it passes, so a busy workspace pays a
  listing cost the memo does not reduce — §3.1 removes repeated *inspection*, not repeated *listing*.
- **The observed provider request-rate limit changes**, which would invalidate the sizing behind
  §3.2's default rather than the mechanism itself.

## Amendment 1 — request rate (2026-08-29)

W2 qualified this mechanism against the real Anthropic API and found five defects. Four were coding
errors and were fixed. The fifth was this ADR's:

```
anthropic.RateLimitError: 429 — exceeds your organization's rate limit of
50 requests per minute (limit_type: Message Batches API)
```

§3 bounded pages and inspections **per search** and said nothing about repeating a search. Because a
search held no state, every retry repaid its full cost: one orphan in a forty-five-candidate window
cost about forty-six calls per attempt and was retried every thirty seconds — roughly ninety calls a
minute against a fifty-a-minute limit, from a single lost acknowledgement. A full pass of twenty
orphans authorised around nine hundred.

The amendment adds §3.1 (durable negative memo), §3.2 (per-pass inspection budget) and §7
(request-rate discipline), and restates `ZERO` in §2 as cumulative coverage. **No correctness
guarantee is relaxed:** the window is unchanged at ±1 hour, the four outcomes keep their meanings,
`MULTIPLE` keeps its precedence, an uninspectable candidate is still never a non-match, and no
recovery path submits. The Definition of Done is unchanged and no criterion becomes easier to
satisfy.
