# M2-ADR-016: Provider execution-contract capability profile, and the gate Contract B must pass

- **Status:** Accepted — 2026-08-27. Authorizes design and construction of Contract B; authorizes no
  production route and no durable execution schema (see §6, *What acceptance does and does not
  authorize*).
- **Date:** 2026-08-27 (proposed and accepted the same day; the open question was answered on review
  of PR #161)
- **Amended:** 2026-08-27 — Addendum A records the OpenAI Responses background-mode evaluation that
  the second revisit trigger called for. It adds evidence and reverses no decision: the selected
  Contract B provider path is unchanged, and §2 through §6 stand as written.
- **Relates to:** M2-ADR-001, M2-ADR-003, M2-ADR-008, M2-ADR-011, M2-ADR-012, M2-ADR-013,
  M2-ADR-014, M1-ADR-001, M1-ADR-008, M2-T09, M2-T15.2
- **Sources:** `docs/MVP02/ContractAandB/RAMALS_Contract_A_Single_Submission_Fail_Closed_Implementation.docx`,
  `docs/MVP02/ContractAandB/RAMALS_Contract_B_Durable_Recoverable_AI_Execution_Implementation.docx`
- **Originates here.** The MVP-2 ADR package registers M2-ADR-001 through M2-ADR-015 and does not
  contain this decision. It is numbered in the same sequence so a reader looking for MVP-2 decisions
  finds it, not because the package registers it — the same basis on which M1-ADR-011 was numbered
  into the MVP-1 sequence.

## Context

PR #160 closed Contract A: single-submission, fail-closed diagnostic execution. It is implemented
and qualified. `V035` gives the dispatch fence, `V036` gives `INDETERMINATE` as a first-class
terminal status, and the gateway refuses every retry, fallback and SDK-level retry once a request is
under `SINGLE_SUBMISSION_FAIL_CLOSED`.

The Contract B design document proposes the successor: durable, recoverable AI execution, in which a
worker death after the provider call no longer destroys the outcome. Its preconditions section
states a hard architecture gate — *"Do not implement Contract B against a provider path that lacks
the primitives below"* — and then does not answer whether our provider path has them.

This ADR answers that question, and only that question. It changes no code, no schema, no route and
no Contract A behaviour. It exists because the answer determines whether the remaining Contract B
work is a roadmap or a dead end, and because a capability gate that nobody wrote down is a gate that
gets walked through by whoever is under deadline pressure.

The question is sharper than "does the provider support idempotency". Contract B's guarantee is
*recoverability*, and recovery is a chain: a replacement worker must be able to (a) avoid creating a
second provider execution, (b) find the first one, and (c) read its result. A provider that offers
any two of those three offers none of Contract B.

## Decision

### 1. Contract A and Contract B require different things of a provider

They are not stronger and weaker versions of one requirement. Contract A's guarantees are achievable
*without provider cooperation*, which is precisely why it shipped. Contract B's are not.

| Capability | Contract A needs | Contract B needs |
| --- | --- | --- |
| Replay-safe admission (same key + same payload ⇒ same execution) | No | **Yes — mandatory** |
| Durable provider execution identifier | No | **Yes — mandatory** |
| Status lookup by that identifier | No | **Yes — mandatory** |
| Result retrieval after the calling process is gone | No | **Yes — mandatory** |
| Documented result-retention window | No | **Yes — mandatory** |
| Suppressible SDK-level retry | **Yes** | Yes |
| Cancellation | No | Preferred |
| Authenticated, replay-protected callbacks | No | Only if callbacks are used |

Contract A needs exactly one thing from the provider path: that every retry it did not authorize can
be turned off. LiteLLM supplies that through `num_retries = 0`, and the gateway supplies the rest by
refusing its own retry and fallback. Everything else in Contract A — the fence, the CAS, the
`INDETERMINATE` terminal state — is ours, enforced in PostgreSQL, and owes the provider nothing.

Contract B inverts that. Five of its mandatory rows are properties of the provider's published
contract. We cannot implement them, work around them, or test them into existence.

### 2. The synchronous Messages API cannot support Contract B

Verified against `platform.claude.com/docs` on 2026-08-27:

| Contract B requirement | Messages API | Source |
| --- | --- | --- |
| Replay-safe admission | **Absent.** No `Idempotency-Key` request header is documented, and no idempotent submission semantics of any kind. | [api/messages](https://platform.claude.com/docs/en/api/messages) |
| Durable execution identifier | **Insufficient.** A `request-id` response header exists and is documented for support correlation only. It arrives *on a response we already received*, which is the one situation recovery does not need it. | [api/errors § Request ID](https://platform.claude.com/docs/en/api/errors) |
| Status lookup | **Absent.** No status endpoint for a message. | [api/messages](https://platform.claude.com/docs/en/api/messages) |
| Result retrieval after process death | **Absent.** There is no `GET /v1/messages/{id}`. The API documents `POST /v1/messages` only. | [api/messages](https://platform.claude.com/docs/en/api/messages) |
| Retention window | Not applicable — there is nothing retained to retrieve. | — |

**Four of five mandatory rows fail.** The synchronous Messages API is therefore declared **Contract
B unsupported**, and this is not a temporary state pending implementation effort on our side. No
amount of RAMALS engineering adds a retrieval endpoint to someone else's API.

This is also the evidence for the sentence already in the Contract A document — that it *"does not
claim provider-level exactly-once execution because the current synchronous provider path does not
expose a documented replay-safe submission plus result-retrieval primitive."* That sentence was
correct when written and is now verified rather than asserted.

### 3. The Message Batches API satisfies five of seven, and fails the first one

Verified against `platform.claude.com/docs` on 2026-08-27:

| Contract B requirement | Message Batches API | Detail |
| --- | --- | --- |
| Replay-safe admission | **Absent** | `POST /v1/messages/batches` accepts no idempotency key. A lost acknowledgement leaves us without the batch id. |
| Durable execution identifier | Present | `msgbatch_…`, returned in the create response only. |
| Status lookup | Present | `GET /v1/messages/batches/{id}` → `processing_status` (`in_progress` → `ended`). |
| Result retrieval after process death | Present | Batch-result retrieval with individual results correlated by `custom_id`: `results_url` streams the whole batch as JSONL, and each record carries the client-supplied `custom_id` it was submitted under. Retrieval is addressed by batch id, not by `custom_id`. |
| Retention window | Present | Results available **29 days** after creation. A batch that has not completed within **24 hours** expires. |
| Cancellation | Present | Cancel endpoint; cancelled batches end `ended` with partial results. |
| Callbacks | Absent | No webhooks. Reconciliation is poll-only. |

The one failing row is the first one, and it is the one that cannot be compensated for cleanly.

**What can be built, and what it is honestly called.** `custom_id` is client-supplied and a list
endpoint exists, so a reconciliation protocol is constructible: persist `custom_id = requestId`
before submitting; on a lost acknowledgement never blind-retry the create, but enter a reconciling
state, sweep the batch list over the admission window, and match on `custom_id`.

That protocol is worth building. It is **not** provider idempotency, and this ADR forbids describing
it as such. It is a client-side reconciliation heuristic whose residual duplicate-execution window
is set by the visibility lag between a successful create and that batch appearing in the list — and
Anthropic documents no bound on that lag. The window is therefore **unbounded by contract**, however
small it is in practice.

Two consequences follow, and both are binding on any future Contract B work:

- The Contract B design document's Definition of Done includes *"Lost acknowledgement does not create
  duplicate provider execution."* **That line is not achievable against this provider** and must be
  restated before the DoD is used as an acceptance gate. The achievable form is: *duplicate provider
  execution is detectable from durable usage evidence and bounded by policy.* Detection is real and
  provable — `core.ai_execution` already records usage and cost per request identity, so a
  qualification scenario can prove *whether* one logical request produced exactly one billed
  execution across N induced crashes, and surface it when it did not. Prevention is not available
  and must not be claimed.
- The strongest guarantee Contract B may claim on this provider is **single-intent provider
  submission with detectable duplicate provider execution, and exactly-one authoritative outcome
  adoption in RAMALS.** Each part is load-bearing:
  - *Single-intent provider submission* — one submission is ever intended per request identity, and
    none is issued speculatively. It does not say one is ever *executed*: a lost acknowledgement can
    leave behind a provider execution we did not intend and cannot recall.
  - *Detectable duplicate provider execution* — the honest ceiling on duplicates. They are found in
    durable usage evidence after the fact; they are not prevented before it.
  - *Exactly-one authoritative outcome adoption* — the guarantee that does hold. At most one
    provider result is ever adopted as the RAMALS outcome for a request identity, and adopting it
    commits the execution row and the gate decision together. Duplicate provider executions, where
    they occur, cost money and produce evidence; they never produce a second authoritative outcome.

  **Neither at-most-once nor at-least-once describes provider execution here, and neither may be
  used of it.** At-most-once asserts a duplicate cannot occur, which this section has just said this
  provider cannot promise. At-least-once asserts one always occurs, which is equally untrue —
  admission can fail before any submission is made. Both would be false claims in the one document
  later readers will trust.

  This preserves M2-ADR-003's split exactly: **exactly-once still applies to business effects, which
  we own**; nothing stronger than detection applies to provider execution, which we do not.

### 4. Execution contract is bound to a route, and never silently degrades

An adapter that cannot honour Contract B must **declare Contract B unsupported and fail**. It must
never fall through to a Contract A style call, and it must never redispatch to recover from its own
inability to reconcile.

The failure this forbids is specific. A durable execution that quietly completes as a synchronous
single submission produces a row that *looks* recoverable, carries no provider execution identity,
and will be treated by a later recovery path as retrievable when it is not. That is strictly worse
than refusing: Contract A's honesty about `INDETERMINATE` is its most valuable property, and a silent
degradation launders an unrecoverable execution into one that appears recoverable.

Three rules follow:

1. **The execution contract is a property of the route**, resolved from versioned route configuration
   under M2-ADR-014 — never of a request, a header, or a runtime fallback. One place decides, and the
   decision is reviewable and rollbackable like any other route change.
2. **One request identity is bound to one contract for its whole life**, recorded durably at
   commission time. A request commissioned under B is never completed under A, and the reverse is
   equally forbidden.
3. **An unsupported contract is an error, not a downgrade.** The provider boundary already
   establishes this shape — an adapter's job is to make the call or raise a normalized
   `GatewayError`, never to substitute a different call. Contract B extends that rule rather than
   inventing one.

Contract A's behaviour is unchanged by all three. Its routes stay on Contract A, its state machine is
untouched, and `V035`/`V036` remain the authority for it.

### 5. Historical Contract A rows are never reinterpreted

A pre-Contract-B `IN_FLIGHT` or `DISPATCH_OWNED` row carries no provider execution identity. There is
nothing to look up and nothing to retrieve, so it is not recoverable and must never be migrated into
a recoverable state. `LEGACY_INDETERMINATE` already encodes exactly this for the pre-`V035`
generation, and the same treatment applies here.

Contract B applies only to executions admitted after a Contract-B-capable route is active. This is
not a migration convenience; a row made to look recoverable when it is not would cause a recovery
worker to poll for an execution that no provider has ever heard of.

### 6. A Contract-B `DIAGNOSE` may be asynchronous; a Contract-A `DIAGNOSE` may not

**Decided 2026-08-27, on review of PR #161. This ADR was Proposed pending exactly this question and
carried no other open question; the answer is recorded below and the ADR is now Accepted.**

The question was whether a Contract-B diagnostic may return before its outcome exists. It had to be
answered outside this document because it is a product decision — a learner sees a pending
diagnostic rather than a result — with an architectural consequence, and no architecture document
has standing to accept a product cost.

The facts that forced it, unchanged:

- Diagnostic assessment runs today as `InteractionClass.INTERACTIVE_AI` under a 12-second budget
  (`DiagnosticAssessmentService.DEADLINE_MS`), and M1-ADR-001 makes that deadline binding.
- The Message Batches API — the only Anthropic path that satisfies the retrieval requirements — is
  asynchronous. Most batches complete within an hour; a batch expires at 24 hours.
- The two cannot be reconciled. No configuration, ceiling or timeout makes a batch answer inside an
  interactive deadline.

**The answer is yes, and it is bounded by the six rules below.** Asynchrony is granted to Contract B
alone and is not a platform-wide relaxation of M1-ADR-001. Each rule is binding on every subsequent
Contract B task.

1. **Asynchronous behaviour binds to Contract-B routes only.** It is a property of the execution
   contract resolved from the route, per rule 1 of §4 — never of a request, a header, a caller
   preference or a runtime condition. A route on Contract A cannot become asynchronous by any means
   short of moving it to Contract B, which is a reviewed configuration change.

2. **The learner-facing API must support `PENDING` as a first-class outcome.** A Contract-B
   diagnostic returns a pending state when its result does not yet exist, and the workflow adopts
   the outcome later through reconciliation. `PENDING` is a real state with its own representation,
   not an error, not an empty success, and not a timeout wearing a different name. It carries the
   request identity the eventual outcome will be found under.

3. **The interactive HTTP deadline and the eventual execution SLA are separate budgets, and neither
   bounds the other.** The HTTP deadline continues to govern how long a caller waits for an
   *admission* response and remains binding under M1-ADR-001. The eventual execution SLA governs how
   long the platform may take to produce and adopt the *outcome*, and is bounded by the provider's
   own expiry and retention windows rather than by an interaction class. Reporting one as the other
   — an admission latency presented as a diagnostic latency — would make both unreadable.

4. **No Contract-B request may fall back to Contract A**, silently or otherwise. This restates §4
   rule 3 for the case the asynchronous decision creates: a Contract-B request whose reconciliation
   is unavailable fails as a Contract-B request. It does not become a synchronous single submission,
   because doing so would produce a row that looks recoverable and is not. Asynchrony does not
   introduce a fallback; it removes the excuse for one.

5. **Contract A remains the default and the current execution contract.** Every route is on
   Contract A today and stays there. Contract B is opt-in per route, reviewed like any other route
   change under M2-ADR-014, and Contract A remains correct and supported for routes that never move.
   This decision does not deprecate Contract A and does not schedule its removal.

6. **No Contract-B production route is activated by this decision.** Acceptance authorizes design
   and construction; it does not authorize traffic. A production route may move to Contract B only
   after the qualification and cost evidence that a later task defines, and that activation is a
   separate decision from this one.

**What acceptance does and does not authorize.** Accepting this ADR settles the capability gate and
the asynchronous question. It does **not** authorize building the durable execution ledger, because
two prerequisites named in the Consequences below are still open and each needs its own ADR: where
Contract B durable state lives, and whether model output may be stored at all. A reader who takes
"Accepted" as licence to write a migration has read this section without reading that one.

**Decision owner.** Recorded from the review of PR #161 by the repository owner. The individual
accountable name should be substituted here if this ADR is cited in an external approval record.

## Alternatives rejected

- **Treat the `request-id` response header as the durable execution identifier.** It is documented
  for support correlation and has no lookup endpoint behind it. It is also only ever observed on a
  response that was successfully received — the exact case where recovery is unnecessary. Building
  recovery on it would produce a design that works in every test and fails in every real incident.
- **Retry the batch create on a lost acknowledgement.** The straightforward reading of "the call
  failed, call it again", and the one that spends money twice. A create whose acknowledgement was
  lost may well have succeeded; retrying it is the duplicate-execution bug, not the fix for it.
- **Declare the reconciliation sweep equivalent to provider idempotency.** It is not, and recording
  it as such would put a false guarantee into the one document later readers will trust. The
  distinction is small in practice and total in principle, which is exactly the kind that erodes when
  it is not written down.
- **Let a Contract-B adapter fall back to Contract A when reconciliation is unavailable.** Produces
  an execution that appears recoverable and is not. See rule 3 above.
- **Settle the asynchronous question by architectural reasoning alone.** It has a product cost — a
  learner sees a pending diagnostic rather than a result — that no architecture document has standing
  to accept on its own. §6 records an answer given by the decision owner on review; it does not
  derive one. Had the answer been no, §2 and §3 would be unchanged and Contract B would simply have
  stayed unreachable on this provider.
- **Grant asynchrony platform-wide rather than to Contract-B routes.** Simpler to state, and it would
  quietly relax M1-ADR-001 for every interaction class that never asked for it. §6 rule 1 confines
  the grant to the routes that need it.
- **Implement Contract B against the synchronous Messages API anyway, accepting partial coverage.**
  Contract B's value is the recovery path; a Contract B with no result retrieval is Contract A plus a
  larger schema and a false name.

## Consequences

- **The synchronous Messages API is declared Contract B unsupported.** This is a durable property of
  that API as documented on 2026-08-27, not a backlog item.
- **Contract A remains the only implemented execution contract**, and remains correct for every
  route. Nothing in this ADR asks it to change.
- **Any future Contract B work inherits the honesty constraint above.** Neither "exactly-once
  provider execution" nor "at-most-once provider execution" is claimable. What is claimable, and
  only on a path that passes the mandatory rows in §1, is **single-intent provider submission with
  detectable duplicate provider execution, and exactly-one authoritative outcome adoption in
  RAMALS**.
- **Any future Contract B durable state is owned by Spring/PostgreSQL under Flyway.** The Contract B
  design document places a durable execution ledger inside RAMALS-AI, which conflicts with
  M2-ADR-012's state ownership and with the AI plane's deliberate exclusion of every PostgreSQL
  driver — an exclusion asserted by `ramals-ai/tests/unit/test_no_database_access.py`, not merely
  documented. Resolving that conflict in favour of the AI plane would require reversing M2-ADR-008
  and M2-ADR-012. This ADR records the conflict; it does not resolve it, because no ledger is being
  built yet.
- **A separate ADR is required before any Contract B result table exists.** Recoverability requires
  storing model output, and `V035` states that prompts, context and model output are structurally
  impossible to store in the dispatch ledger. That invariant and Contract B's result table cannot
  both stand unqualified. This is a genuine conflict of principles, not an oversight, and it needs
  its own decision with a retention, encryption and purge policy.
- **The Contract B design document's Definition of Done requires amendment** before it is used as an
  acceptance gate — specifically the lost-acknowledgement line identified in §3. **Done 2026-08-27:
  the [amended Definition of Done](../release/mvp2-contract-b-definition-of-done.md) supersedes §18 for acceptance purposes.** Amending it surfaced four further criteria
  the accepted decisions had already superseded, not just the one this ADR named.
- **The provider capability profile is versioned and re-verified.** The tables in §2 and §3 are
  point-in-time readings of a third-party contract. A provider that later publishes replay-safe
  admission changes this decision, and that is a revisit trigger rather than a silent improvement: no
  route may move to Contract B on the strength of an undocumented behaviour observed in testing.

## Verification

This ADR is documentation and carries no implementation. Its claims are verifiable as follows:

- **The §2 and §3 tables** — re-fetch the cited documentation URLs and confirm each row. Any row that
  has changed invalidates that row of the decision and triggers the revisit path below.
- **§1's Contract A row** — `num_retries = 0` under `single_submission` in the LiteLLM adapter, the
  gateway's refusal of retry and fallback under `SINGLE_SUBMISSION_FAIL_CLOSED`, and the graph
  runtime's refusal of a repair cycle once a provider submission has occurred, are all covered by
  existing tests merged with PR #160.
- **§4, §5 and §6** — enforced by no test today, because no Contract B code exists. When it does,
  each rule requires a standing test rather than a review convention: §4's three rules and §6's
  rules 1, 4 and 5 are the specification for those tests. Rule 4 in particular must be a test and
  not a code review, because a fallback to Contract A is the kind of change that gets added under
  incident pressure and looks like a fix.
- **§6 rule 6** — verified by every route remaining on Contract A. A route table in which any route
  resolves to Contract B contradicts this ADR until the separate activation decision it names is
  taken and recorded.
- **The status transition itself** — this ADR was Proposed pending one question and carried no
  other. Acceptance is verifiable by that question being answered in §6 with its date and the review
  it came from. A future reader who finds an Accepted ADR whose §6 records no answer should treat
  the acceptance as invalid rather than the question as moot.

## Revisit triggers

- Anthropic publishes documented replay-safe admission for a synchronous path.
- A different approved provider on an existing route is verified against the mandatory rows in §1.
  The route table already approves an alternate binding, and this ADR makes no claim about any
  provider it has not verified. **Fired 2026-08-27 for OpenAI — see Addendum A.** It remains live
  for any provider not yet evaluated there.
- RAMALS accepts operating a durable intermediary that supplies admission idempotency, which moves
  the guarantee inside our own failure domain and makes that intermediary's durability the new gate.
- The asynchronous grant in §6 is withdrawn, narrowed, or extended beyond Contract-B routes. Any of
  the three changes what a route may do and needs a new decision, not an amendment here.
- A Contract-B production route is proposed for activation. §6 rule 6 makes that a separate decision;
  this ADR is its precondition, not its authority.

## Addendum A — OpenAI Responses background mode, evaluated 2026-08-27

Recorded because the second revisit trigger above fired: `gpt-4.1-2025-04-14` is already an
approved alternate binding in the route table, so "why not OpenAI, it is already approved?" is a
question this ADR will be asked. It is answered here once, with evidence, rather than re-derived.

**This addendum adds evidence and reverses nothing.** §2 through §6 stand as written, and the
selected Contract B provider path remains the Anthropic Message Batches API.

Verified against `developers.openai.com` documentation on 2026-08-27.

### Capability matrix

| Requirement | OpenAI Responses (`background: true`) | Anthropic Message Batches |
| --- | --- | --- |
| Durable execution identity | ✅ `resp_…` — **only in the create acknowledgement** | ✅ `msgbatch_…` — only in the create acknowledgement |
| Asynchronous execution | ✅ `queued` → `in_progress` → terminal | ✅ batch-shaped |
| Status lookup | ✅ `GET /v1/responses/{id}` | ✅ `processing_status` by batch id |
| Result retrieval after process death | ⚠️ conditional on `store` | ✅ unconditional, via `results_url` |
| Cancellation | ✅ documented idempotent | ✅ |
| Result retention | ⚠️ ≥30 days when `store=true`; **roughly 10 minutes when `store` is false or omitted**, which is how documented background requests from zero-data-retention projects run | ✅ 29 days; 24-hour processing expiry |
| **Replay-safe admission** | ❌ **not documented** | ❌ **not documented** |
| Lookup by caller request identity | ❌ caller-supplied `metadata` **is** accepted (16 pairs), but no response enumeration or search endpoint was found, so it cannot be used to discover an orphan | ⚠️ partial — `custom_id` per record, plus batch enumeration |
| Cost | Standard pricing | 50% discount |
| Latency | Seconds to minutes; no documented SLA | Most within 1 hour; 24-hour ceiling |
| Fit for one learner `DIAGNOSE` | ✅ built for exactly this shape | ⚠️ a batch of one, at batch latency |

### Neither provider offers documented replay-safe admission

OpenAI documents an `Idempotency-Key` header on other product surfaces — Agentic Commerce and
Workspace Agent triggers, with a `409 idempotency_conflict` on body mismatch. **It is not
documented for `POST /v1/responses`**, and this ADR does not generalise it there. A header
documented on a commerce endpoint is not a guarantee on the inference endpoint, and §3's rule
applies unchanged: an undocumented behaviour is not a contract, however plausible.

So the §1 mandatory row that Message Batches fails is failed by OpenAI too. Nothing in this
addendum weakens the conclusion that **RAMALS cannot claim provider-level exactly-once or
replay-safe admission on any evaluated path.**

### The discriminator is what survives a lost acknowledgement

Both providers return their durable execution identity *only* in the create acknowledgement. The
question is what a replacement worker can do when that acknowledgement never arrives.

**OpenAI: nothing usable.** The request *can* carry a caller-supplied correlation value —
`POST /v1/responses` accepts a `metadata` object of up to 16 key-value pairs — so the orphan is
labelled. It is simply not findable: the Responses resource offers `create`, `retrieve`, `delete`,
`cancel`, `stream`, `parse` and `compact`, and **no enumeration or search**. A response can be
fetched only by the `resp_…` identifier that the lost acknowledgement carried away.

Labelling without enumeration buys nothing here. There is no idempotency key to replay and no way
to ask *"which response carries my requestId?"*, so the execution runs, bills, and is
**permanently unreachable**. A resubmission produces a duplicate that cannot be detected
afterwards, because nothing can be queried to detect it.

**Anthropic: ambiguous, but workable.** `custom_id` is client-supplied and travels with each record
in the batch results, and batches can be enumerated. A replacement worker can sweep the batch list
over the admission window and match on `custom_id`. The window remains **unbounded by contract** —
the create-to-visibility lag is undocumented, exactly as §3 records — so this is a client-side
reconciliation heuristic and never provider idempotency. But recovery is possible and duplicates
are **detectable**.

### Why detectability decides it

Contract B's Definition of Done cannot promise that a lost acknowledgement creates no duplicate
(§3). What it can promise is that a duplicate is *detected from durable usage evidence*. That
restatement is only honest if detection is actually achievable.

On OpenAI it is not. The T15 crash matrix requires a cost-evidence scenario proving that one
logical request produced exactly one billed provider execution across induced faults — and on a
path where executions cannot be enumerated, **that scenario cannot be built**. It is not enough
that each execution carries a `metadata` label; the scenario has to *find* every execution
attributable to one request identity, and there is no query that returns them. M2-ADR-017's audit
requirements have the same problem: an execution that can only be reached by an identifier nobody
kept cannot be audited.

A guarantee that cannot be qualified is not a guarantee, and Contract A's completed qualification
is the standard Contract B is held to.

### What OpenAI wins, and why it does not change the decision

OpenAI background mode is the better fit for the *use case*. It is designed for a single
long-running request rather than a batch of one; its latency is seconds to minutes against
Anthropic's hour; its operational surface is one identifier and one poll; and the route table
already approves the model, so no new vendor governance is required. Had recoverability been
equal, it would win.

It is not equal. Two further risks compound the discriminator above:

- **Retention depends on organisation configuration as well as on the request.** Background mode is
  documented as available to zero-data-retention projects, whose background requests run with
  `store=false` and whose response data is held for roughly 10 minutes to support polling. A
  request cannot raise that: under zero data retention `store` is treated as false even when the
  request sets it true. So the recovery window is 30 days or ten minutes depending on a setting
  outside the codebase, and Contract B recoverability could change without a code change or a
  deployment. Worth stating precisely rather than as a coercion: it is documented behaviour, not a
  silent override, and background execution itself remains supported.
- **`store=true` sits against M2-ADR-017's posture**, which keeps model output only until adoption.
  Requiring the *provider* to retain learner-derived output for 30 days to enable our recovery is
  the opposite trade.

### Decision

**The Anthropic Message Batches API remains the selected Contract B provider path.** OpenAI
Responses background mode is recorded as evaluated and not selected, for the reason above rather
than for capability breadth.

One consequence should travel with this choice rather than be discovered later: Anthropic's
roughly one-hour latency makes the `PENDING` requirement of §6 rule 2 load-bearing rather than
theoretical. OpenAI background mode would have made the asynchrony nearly invisible to a learner.
Anthropic will not, and that cost belongs in front of the product owner when the first Contract-B
`DIAGNOSE` is proposed for a route.
