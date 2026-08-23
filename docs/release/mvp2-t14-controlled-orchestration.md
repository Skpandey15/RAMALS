# M2-T14 — Controlled multi-agent orchestration

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
| `ledger.learning_workflow_run` | One run per gated evaluation: authoritative identifiers, status, current step, absolute deadline, terminal reason |
| `ledger.learning_workflow_step` | One row per step per run: status, attempt count, stable reason code, and the request identity that joins to `core.ai_execution` |

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

**Every step separately observable, retryable and attributable.** Each step is a row with its own
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
| G08 | Cross-step correlation | `g08_everyStepIsJoinableToTheRunAndTheAgentStepsCarryARequestIdentity` |

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
  machine semantics and the wrong one for provider behaviour.
- **No LangGraph change.** Cross-agent composition is Java-side by design; the per-agent graphs and
  their ceilings are unchanged.
