# M2-T14 — Controlled multi-agent orchestration

> **Status: `IMPLEMENTED — NOT ACCEPTED — NOT ACTIVATABLE`**
>
> The orchestration described below is implemented, reviewed and remediated. It is **not** accepted
> for production use, and it must not be activated until the three prerequisites in
> [Activation prerequisites](#activation-prerequisites--mandatory-and-currently-open) are closed.
>
> **It is safe today because it is inert**, for three specific reasons:
>
> - `LearningWorkflowOrchestrator.trigger()` has no production caller;
> - production must explicitly set `ramals.orchestration.enabled` to `false` until activation;
> - therefore no production workflow currently depends on abandoned-claim recovery.
>
> That is the entire basis on which the open durability gap is tolerable. It is not tolerable once
> anything triggers a workflow.


## Scope

Composes evaluation, mastery, diagnosis and adaptation as one explicit, deterministic workflow. It
is the first component that turns an accepted M2-T12 gate decision into authoritative learner state,
and the first that runs more than one agent for a single learner event.

It does not add an agent, a queue technology, or a new proposal contract. The AI plane is unchanged.

## Where the composition lives, and why

The spine is Spring-owned. LangGraph continues to govern the execution flow *inside* one agent run,
bounded by the ceilings in `ramals_ai.graph.limits`; it holds no workflow milestone. That split is
the task's guardrail, and it is also the only arrangement in which the acceptance criteria are
checkable: a milestone kept in an agent checkpoint is not observable to the deterministic services
that own the learner's state, and cannot be joined to the evidence ledger by an auditor.

Two tables carry the composition:

| Table | Holds |
| --- | --- |
| `core.learning_workflow_run` | One run per gated evaluation: authoritative identifiers, status, current step, absolute deadline, terminal reason |
| `core.learning_workflow_step` | One row per step per run: status, attempt count, stable reason code, and the request identity that joins to `core.ai_execution` |

## The four steps

| # | Step | Kind | Effect |
| --- | --- | --- | --- |
| 0 | `RECORD_EVALUATION_EVIDENCE` | Deterministic | Appends `EVALUATION` evidence, idempotent on the evaluation's request identity |
| 1 | `RECOMPUTE_MASTERY` | Deterministic | Recomputes the mastery snapshot and decides diagnosis eligibility from it |
| 2 | `DIAGNOSE` | Agent | Runs the M2-T09 diagnostic path and its gate |
| 3 | `ADAPT` | Agent | Records the recommendation decision, which enqueues adaptation work on the M2-T02 outbox |

The deterministic steps come first deliberately. Authoritative state is settled by Spring before any
model is consulted, so a slow or failing agent can never leave the evidence ledger half-written.

`ADAPT` completes when the hand-off to the outbox is durable, not when the adaptation agent replies.
The M2-T03 dispatcher already owns delivery, retry and terminal failure for that call; giving the
same agent call a second retry budget inside the workflow would mean two bounds on one operation,
and the looser one always wins.

## How each acceptance criterion is met

**Deterministic trigger and authority boundaries.** `LearningWorkflowPolicy` is pure, versioned
(`WORKFLOW_POLICY_V1`) and consults no model and no clock. Only an `ACCEPTED` gate decision may start
a run; a `REJECTED` or `MANUAL_REVIEW` evaluation is content the deterministic gate declined, and
seeding evidence from it would route rejected model output into authoritative state through the side
door M2-T12 exists to close.

**No unbounded agent loop.** Three independent bounds, any one sufficient:

- The step table holds at most one row per step per run, so a repeated or malformed agent result
  advances an existing step instead of appending a new one. This is a schema constraint, not a
  convention.
- `MAX_STEP_ATTEMPTS` bounds retries per step, on attempt count alone. Making the bound depend on a
  "transient" classification is how a bound stops being one, the first time a provider reports a
  permanent fault as a timeout.
- `RUN_DEADLINE` is an absolute wall-clock budget that outranks every per-step allowance.

Additionally, `advance` performs at most one step per call and never recurses, and an ArchUnit rule
forbids any agent adapter package from depending on `orchestration`, so no agent can start or
advance a workflow.

**Every step separately observable, retryable and attributable.** (See the remediation section: the
attributable half of this claim did not hold as first written.) Each step is a row with its own
status, attempt count and stable reason code. The two agent steps carry the request identity that
joins to `core.ai_execution` and `core.agent_work_outbox`; the two deterministic steps carry none,
because inventing one would put a row in the correlation index that joins to no execution.

## Qualification coverage

| ID | Scenario | Covered by |
| --- | --- | --- |
| G01 | Evaluation → diagnosis → adaptation | `g01_evaluationFlowsThroughEvidenceMasteryDiagnosisAndAdaptation` |
| G02 | Diagnosis not eligible | `g02_aMasteredLearnerStopsTheWorkflowWithoutCallingAnyAgent`, `g02_aMasteredLearnerDoesNotSpendADiagnosticCall` |
| G03 | Adaptation rejected | `g03_aRejectedDiagnosisRecordsALegitimateStopRatherThanForcingAnAdaptation`, `g03_aRejectedDiagnosisCannotProduceAnAdaptation` |
| G04 | Mid-workflow provider failure | `g04_aMidWorkflowAgentFailureLeavesEarlierAuthoritativeStepsIntactAndStaysRetryable` |
| G05 | Duplicate workflow trigger | `g05_aDuplicateTriggerCollapsesOntoTheOneRun` |
| G06 | No unbounded loop | `g06_aPermanentlyFailingStepReachesABoundedTerminalStateInsteadOfLooping`, `g06_retriesAreBoundedByAttemptCountAlone`, `g06_stepOrderIsStrictlyForwardAndTerminates` |
| G07 | Cancellation / timeout | `g07_anOverrunRunTimesOutWithAnExplicitTerminalStatus`, `g07_cancellationIsTerminalAndCannotBeOverwrittenByLaterProgress` |
| G08 | Cross-step correlation | `g08_everyStepIsJoinableToTheRunAndTheAgentStepsCarryARequestIdentity`, and `theAdaptationStepRequestIdJoinsToTheDurableOutboxRow` for the real join |

## Authority change in this task

This is the first component to create learner evidence from AI-gated output, so it is worth stating
plainly what changed:

- `ledger.evidence` accepts a new `EVALUATION` type, and `MasteryService` counts it as an
  observation. Existing evidence types, algorithms and frozen engine vectors are untouched.
- The evidence row's skill, attempt and assessment version come from the workflow run — Spring-owned
  facts about what was assessed. Nothing is read from the proposal: the gate decided the evaluation
  was acceptable, it did not get to decide whose evidence it becomes.
- The write is idempotent on the evaluation's request identity, which is the only thing standing
  between an at-least-once trigger and a learner whose mastery climbs on every redelivery.

## Deliberately not included

- **No caller yet.** `LearningWorkflowOrchestrator.trigger` has no production caller; wiring it to
  the assessment submission path is a separate change with its own review. The advancer is behind
  `ramals.orchestration.enabled`.
- **Live-provider qualification, perturbation and performance** for the composed chain belong to
  M2-T15. The G-series here run against in-memory step ports, which is the right layer for state
  machine semantics and the wrong one for provider behaviour. Claim atomicity, request correlation
  and snapshot lineage are asserted against real PostgreSQL instead, for the opposite reason.
- **No LangGraph change.** Cross-agent composition is Java-side by design; the per-agent graphs and
  their ceilings are unchanged.

---

# Review remediation (second independent adversarial review)

A second independent review of PR #131 found two P1 blockers and two P2 issues while CI on the
branch was green. All four are fixed here. Two further defects surfaced while proving the fixes;
both are recorded below rather than quietly folded in.

## Reconciliation of earlier claims in this document

Three statements above were true of the design and **not** true of the implementation that first
carried them. They are corrected rather than rewritten, because what a document claimed before a
review is part of that review's evidence:

| Earlier claim | What was actually true | Now |
| --- | --- | --- |
| "Safe to call repeatedly and from more than one caller" (orchestrator javadoc) | `beginStep` was an unconditional upsert. Two instances of the advancer could both start the same step and both call a model. The deterministic request id limited the downstream damage; it did not make the state machine concurrency-safe. | A step is executed only by the worker that wins an atomic claim. |
| "The two agent steps carry the request identity that joins to `core.ai_execution` and `core.agent_work_outbox`" | The ADAPT step recorded `wf-adapt-<runId>`, which was never written to the outbox. It joined to nothing. The G08 test asserted only that it was non-blank. | The step records the request id the recommendation transaction actually enqueued, and the test asserts a real join. |
| "Every step separately observable, retryable and attributable" | Observable and retryable held. Attributable did not, for the reason above; and attempt counts included attempts that never happened. | Both corrected, with tests. |

## P1-1 — atomic step claim

`learning_workflow_step` gains `execution_token UUID` and `claimed_at`. A live token and a RUNNING
step are the same fact, enforced by `CHECK ((status = 'RUNNING') = (execution_token IS NOT NULL))`.

`claimStep` is one statement. It refuses unless the run is still RUNNING and still on the expected
step, and unless the step is unseen or PENDING — a step another worker is RUNNING is never taken
from it. The attempt ceiling is in the same predicate, so a losing caller cannot spend an attempt
the winner is using. It commits before any remote work and holds no transaction across it.

`finishClaimedStep` and `retryClaimedStep` match on the token and return false when it no longer
matches. Every terminal transition clears the token, which is what makes a cancellation or timeout
beat a worker still waiting on a model: the worker returns, finds nothing to update, and the
terminal state stands.

## P1-2 — real outbox request correlation

`RecommendationRepository.appendAdaptationWork` now returns `AdaptationWork(workId, requestId)`, and
`RecommendationService.recommend` returns `RecommendationResult` carrying the decision record id, the
outbox work id and the adaptation request id. The transaction is unchanged: decision record,
recommendation and outbox row still commit atomically.

`AdaptationHandoffStep` returns that request id. The outbox derivation was not changed to match a
workflow-local string; the workflow adopts the durable identity that already existed.

The correlation test asserts the full chain, not just the first hop:

    core.learning_workflow_step.request_id
      -> core.agent_work_outbox.request_id
      -> core.ai_execution.request_id

joined in one statement and required to return exactly one row. Verified directly against the
database as well as through the test: the correlated identity is the deterministic
`ADAPTATION|<decisionRecordId>` UUID the outbox already used, not a workflow-local string.

## P2-1 — attempt counts describe real attempts

`markSkipped` and `markCurrentStepTerminal` replace the former reuse of the claim helper as a
row-creation shortcut. A step that policy declined records `attempt_count = 0`; a cancelled or
timed-out step keeps exactly the attempts it made and gains none. `CHECK ((attempt_count = 0) =
(claimed_at IS NULL))` makes "was this ever claimed?" answerable from the row.

## P2-2 — exact mastery-snapshot lineage

ADAPT consumes the snapshot recorded as the RECOMPUTE_MASTERY step's `result_ref`, loaded by id and
verified to belong to this run's learner, skill and curriculum version. If it is missing or foreign
the step fails rather than substituting another snapshot. `latestSnapshot()` is no longer on this
path: an unrelated learner event landing between the two steps would otherwise leave the audit
naming one snapshot while the recommendation was computed from another.

## Two defects found while proving the fixes

**Schema placement was wrong.** Both tables were created in `ledger`, where the runtime role holds
SELECT and INSERT only — that restriction is what makes an audit row unrewritable. A workflow run is
a state machine whose job is to advance, so claiming failed outright with `permission denied`. They
now live in `core` beside `core.agent_work_outbox`, which is the same kind of object for the same
reason. Granting the runtime UPDATE on an immutable schema would have been the wrong trade.

**A redundant unique constraint broke the race.** `uq_learning_workflow_step_index UNIQUE (run_id,
step_index)` restated `uq_learning_workflow_step_name`, because `step_index` is a pure function of
`step_name`. `ON CONFLICT` can infer only one index, so a genuine two-worker race collided on the
uninferred one and raised instead of being handled. It is removed; the range CHECK still pins
`step_index`. Only a real-PostgreSQL race test could surface this — the single-threaded path and any
in-memory fake both pass with it present.

## Test and perturbation evidence

35 orchestration tests: 18 orchestrator, 8 policy, 9 concurrency/correlation against real
PostgreSQL. G01–G08 retained and passing.

`LearningWorkflowConcurrencyIntegrationTests` is the authority for the claim. The orchestrator's
in-memory store mirrors the same predicates for speed, and a mirror is exactly the thing that can
quietly stop matching — so atomicity, uniqueness and compare-and-set are asserted against a database.

Every guard was perturbed and proven to bite, then restored:

| Perturbation | Result |
| --- | --- |
| Claim predicate accepts a RUNNING step | `twoWorkersRacing…` and `attemptCountRisesOncePerSuccessfulClaim…` fail |
| Step records a fabricated `wf-adapt-…` id | `theAdaptationStepRequestIdJoinsToTheDurableOutboxRow` fails |
| ADAPT resolves `latestSnapshot()` again | `adaptationConsumesTheSnapshotThisWorkflowProduced…` and four others fail |
| Skipped steps credited with one attempt | `aSkippedStepRecordsNoAttempt` and `aSkipAfterARejectedDiagnosis…` fail |
| Completion and retry drop the token guard | both stale-worker tests fail |
| `ai_execution` recorded under a synthetic identity | `theAdaptationStepRequestIdJoinsToTheDurableOutboxRow` fails |

One attempted perturbation was a **no-op** and is reported as such: routing skipped steps back
through `claimStep` changed nothing, because the claim predicate already refuses a step that is not
the run's current one. The guard defended itself, but the perturbation proved nothing, so it was
replaced with one that models the original defect directly.

The race test was re-run five times with `--rerun-tasks` after the constraint fix, to confirm it is
stable rather than incidentally passing.

## Activation prerequisites — mandatory and currently open

This section is appended by a later review. It does not revise the implementation evidence above:
that record stands as written, including the guarantees it claimed at the time it was written. What
follows states which guarantees exist today and which do not.

### Activation gate

> **No production caller may be added to `LearningWorkflowOrchestrator.trigger()`, and the
> orchestration feature must not be enabled for production, until all three activation prerequisites
> below are CLOSED.**

M2-T15 must subsequently qualify abandoned-claim recovery using **injected process death at each of
the four crash windows** identified in prerequisite 3. Building the recovery mechanism is
implementation work; qualifying it under injected failure is T15's.

### The three prerequisites

**1. The production trigger must derive its authoritative facts from the immutable accepted M2-T12
decision**, rather than from caller-restated values. `EvaluationTrigger` currently takes the ACCEPTED
outcome, learner, skill, attempt and assessment version as parameters. That is adequate for a
component with no caller and inadequate the moment one exists: a caller that can restate the outcome
can assert an acceptance the gate never gave.

*Status: OPEN.*

**2. A deterministic, versioned rubric → normalized evaluation-evidence policy must be defined and
tested** before an evaluation may affect authoritative mastery. The score is currently supplied by
the caller and only range-checked. Evidence that feeds mastery needs its derivation frozen and
versioned like every other engine in `EngineVersionFreezeTests`.

*Status: OPEN.*

**3. Abandoned-claim recovery and effect→workflow-marker atomicity must be implemented and
crash-qualified.** The execution token added during remediation guarantees concurrency and
stale-worker safety. It does not guarantee recovery from process death: there is no lease, so a
claim held by a dead JVM is never released.

*Status: MECHANISM IMPLEMENTED — QUALIFICATION OPEN.* The remediation described below has landed;
what remains is proving it under injected process death, which is M2-T15's. The prerequisite is not
CLOSED until that qualification passes. Detail follows, reconciled after implementation.

### Prerequisite 3 — what the execution token does and does not guarantee

Guaranteed, and proven against real PostgreSQL:

- only one worker executes a step at a time;
- a stale worker's completion is rejected once the run is CANCELLED or TIMED_OUT;
- attempt count rises exactly once per genuine claim.

Not guaranteed: recovery from process death.

### Prerequisite 3 — the four crash windows

| # | Window | Authoritative state | Workflow consequence |
| --- | --- | --- | --- |
| 1 | Claim committed, effect not committed | Unchanged; the effect transaction rolled back | Step stranded RUNNING with an orphaned token until the run's absolute deadline |
| 2 | Authoritative effect committed, workflow step completion missing | Durable and correct | The workflow has no record that it happened; `result_ref` never written |
| 3 | Step completion committed, workflow cursor not advanced | Unchanged | Step is COMPLETED while the cursor still points at it. `claimStep` accepts only absent/PENDING and the exhausted-attempts check filters on PENDING, so neither fires and the run makes no further progress. **A lease does not address this window**, because the step is not RUNNING |
| 4 | Remote/diagnostic result durably succeeded, worker dies before workflow adoption | The AI execution and its gate decision are durable | `DIAGNOSE` cannot re-derive the outcome: `commission()` commits in its own transaction before the provider call, so a replay returns `dispatchAllowed = false` and `assess()` throws `AI_EXECUTION_ALREADY_COMMISSIONED` every time. The verdict exists in `ledger.proposal_gate_decision` keyed by `request_id` and is discarded |

### Prerequisite 3 — current failure semantics

> **Superseded by the implementation recorded at the end of this section.** The paragraphs below
> describe the behaviour as it stood when this prerequisite was raised, and are kept because the
> reasoning that drove the fix order depends on them.
>
> **As raised: safe but not self-recovering. An abandoned RUNNING claim may remain stranded until the
> workflow deadline.**

No duplicate authoritative effect is introduced by any of the four windows. The evidence write is
idempotent on its lineage key; the adaptation hand-off is idempotent on the snapshot, decision and
request identities. A stranded run still ends at its absolute deadline, moved to TIMED_OUT with an
explicit reason. What is lost is progress, not correctness.

**Successful `DIAGNOSE` work may currently be discarded** if the worker dies before the workflow
adopts the result. The model was called, the gate ruled, and the decision was persisted — and the
workflow then throws that outcome away and cannot retrieve it.

Replay safety today, established by reading each step's effect rather than inferred from the claim
mechanism:

| Step | Replay-safe | Why |
| --- | --- | --- |
| `RECORD_EVALUATION_EVIDENCE` | Yes | `lineage_key` UNIQUE with `ON CONFLICT DO NOTHING`, keyed on the evaluation request identity |
| `RECOMPUTE_MASTERY` | No | each call takes `nextVersion = current + 1` and appends a new immutable snapshot |
| `DIAGNOSE` | No | at-most-once commissioning makes a second dispatch throw rather than return the prior verdict |
| `ADAPT` | Yes | one transaction; every insert `ON CONFLICT DO NOTHING` on snapshot, decision record and request id |

### Prerequisite 3 — agreed remediation order

- **P0** — step completion and the workflow cursor advance commit in **one local database
  transaction**. Closes window 3. Both are same-database writes with no remote call between them.
- **P1** — for same-database authoritative-effect steps, the effect and `finishClaimedStep` commit
  **atomically**, after any remote work has already returned. Closes window 2 for those steps with no
  new idempotency key and no schema change. **Never hold a database transaction across a model or
  provider call.**
- **P2** — `DIAGNOSE` recovers or adopts an existing durable result using its **deterministic request
  identity** before redispatch: look up the existing gate decision or execution for this workflow's
  stable `requestId` and adopt it if already completed. Closes window 4 while preserving at-most-once
  dispatch, which must not be weakened.
- **P3** — **only after replay safety is established**, introduce abandoned-claim lease and reclaim
  using `claimed_at`. Lease duration must exceed the relevant step deadline, and stale
  execution-token completions must continue to be rejected.

### DO NOT IMPLEMENT LEASE/RECLAIM FIRST

This is the ordering constraint, stated separately because it is the one an engineer is most likely
to violate by instinct. A lease is the obvious fix for a stranded claim, and it is the wrong thing to
build first.

Reclaiming a non-idempotent authoritative step before the effect/marker atomicity gap is closed
converts today's **safe timeout degradation into duplicate authoritative effects**:

- **`RECOMPUTE_MASTERY`** — reclaiming window 2 appends a second snapshot at the next aggregate
  version with identical values, into `ledger.mastery_snapshot`, whose append-only trigger makes the
  duplicate unremovable.
- **`DIAGNOSE`** — reclaiming window 4 burns every remaining attempt on a call that throws
  deterministically, then fails the run with a reason code that describes neither what happened nor
  what already succeeded.

P0 through P3 must therefore land in order. The lease is last because it is only safe once every step
can be replayed without producing a second authoritative effect.

### Prerequisite 3 — implementation, as landed

P0 through P3 were implemented in that order and in a single change, because P3 is only safe once
P0 to P2 exist and separate merges invite exactly the partial adoption the ordering exists to
prevent. What changed:

- **P0** — step completion and the run cursor or terminal transition commit together, closing the
  window in which a step was COMPLETED while the run still pointed at it.
- **P1** — for steps whose effect is a write to this same database, the effect and the claimed-step
  completion commit as one unit through a named `WorkflowUnitOfWork` boundary. `Step` gained a
  `remoteCall` property so the provider call is excluded by construction, and a test asserts the
  diagnostic call runs with no transaction open rather than trusting a comment to keep it that way.
- **P2** — `DIAGNOSE` looks up the gate decision recorded under its stable request identity and
  adopts it before considering dispatch. At-most-once dispatch is unchanged: this adds a read, not a
  second provider call.
- **P3** — `claimStep` additionally accepts a RUNNING step whose `claimed_at` has passed
  `CLAIM_LEASE` (one minute, against a twelve-second step deadline). Reclaim issues a new execution
  token, so a lease set too short costs duplicated effort rather than a duplicated effect. The
  exhausted-attempt check was widened to match, or a reclaimable step with no attempts left would
  have stalled in place of the window being closed.

No migration was required: `claimed_at` was already recorded by the earlier remediation.

Each guard was perturbed and shown to fail the corresponding test: removing the recovery lookup
fails three adoption tests, ignoring the lease fails three concurrency tests, wrapping the provider
call in a transaction fails the no-transaction-across-a-call test, and separating a local effect from
its marker fails the atomicity test.

**What is still open.** The mechanism exists and is unit- and integration-tested against real
PostgreSQL. It has **not** been qualified under injected process death at the four crash windows
above. Until M2-T15 does that, prerequisite 3 is not CLOSED and the activation gate still holds.

