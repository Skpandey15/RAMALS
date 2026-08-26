# M2-T15.2 — DIAGNOSE post-commission / pre-provider: qualification harness gap

> **Status: `PHASE 2 BLOCKED — HARNESS WORK REQUIRED BEFORE ANY QUALIFICATION RUN`**
>
> The application remediation for this scenario is merged and deployed. The qualification harness
> that would prove it is not. No Phase-2 crash window was opened, no fixture was seeded, no fault
> was armed and no pod was killed. This document is the write-up of the gap, and the specification
> for the harness change that must be reviewed and merged before Phase 2 runs.

## Candidate under qualification

| | |
| --- | --- |
| Commit | `62cbed8171180d73d425407ff0f126c2c57b562c` |
| Tree | `fb272f02393a3d3b90d137edbf1dde4908265e5d` |
| Backend image | `sha256:04098418c57283bc01c8ef3d2f752a4bda9d76e936746213ab6b6544d101dfa4` |
| Phase-1 attestation | `deploy/k8s/t15/evidence/m2-t15.1-post-154-attestation-20260826T034847Z/` — PASS, 47/47 checks |
| Live schema | Flyway `V001`–`V035`, zero failed migrations |

The candidate is deployed and attested. Nothing below asks for a rebuild, a repin, or an
application-code change.

## What is already correct

`DiagnosticAssessmentService.assess` on the candidate implements the required recovery sequence in
the required order:

```text
findRecoverableCommission(requestId)
  -> grounding.retrieveAt(subject, curriculumVersionId, REQUIRED_SOURCES, prior.asOf())
  -> hard fail AI_EXECUTION_COMMISSION_CONTEXT_MISMATCH if contextId differs
  -> executions.commission(request)                      // durable commission, dispatch AVAILABLE
  -> QualificationFault.pause(WORKFLOW_AFTER_DIAGNOSTIC_COMMISSION, null, requestId)
  -> executions.acquireDispatch(requestId)               // CAS -> DISPATCH_OWNED
  -> executions.markProviderInvocationStarted(requestId, dispatch)  // fence -> IN_FLIGHT
  -> agent.requestDiagnosticAssessment(request, DEADLINE_MS)        // first provider call
```

`core.ai_execution_dispatch` (V035) is live with `state`, `owner_token`, `fence`, `context_id`,
`context_as_of`, a `UNIQUE` constraint on `commission_event_id`, and a four-state shape constraint
over `AVAILABLE` / `DISPATCH_OWNED` / `IN_FLIGHT` / `LEGACY_INDETERMINATE`.

Both mutations are single-statement compare-and-sets in `AiExecutionRepository`:

- `acquireDiagnosticDispatch` — `SET state='DISPATCH_OWNED', owner_token=?, fence=fence+1`
  `WHERE state='AVAILABLE' AND NOT EXISTS (terminal execution) RETURNING owner_token, fence`.
  Concurrent replacements may both read `AVAILABLE`; only the returned row grants authority.
- `markDiagnosticProviderInvocationStarted` — `SET state='IN_FLIGHT'`
  `WHERE state='DISPATCH_OWNED' AND owner_token=? AND fence=? AND NOT EXISTS (terminal execution)`.

The dispatch record is therefore fully observable from durable state. The gap is not in the
application.

## The gap

### 1. The candidate's own harness encodes the pre-remediation expectation

`deploy/k8s/t15/crash-qualification.ps1` at the candidate still declares, in its
`diagnostic-commission` case:

```powershell
$targetStep = "DIAGNOSE"
$expectedTargetAttempt = 2
$expectedFailure = $true
$expectedAdaptation = $false
```

That is the pre-#154 expectation: the workflow ends abandoned and never adapts. Run unchanged
against the remediated candidate it would report `FAIL` because the workflow now *succeeds*. It is
not a usable Phase-2 harness, and it is now actively misleading — a stale expectation that a later
run could mistake for a real regression.

### 2. The harness that produced the original failure is not in the candidate

`Assert-DiagnosticCommissionRecoveryProof` — the function that threw

```text
DIAGNOSTIC RECOVERY INVARIANT FAILED after durable commission: DIAGNOSE status=FAILED;
execution=1/FAILED/AI_EXECUTION_ABANDONED; gate=0; evidence/mastery/outbox=1/1/0;
workflow=FAILED/DIAGNOSIS_EXECUTION_ABANDONED
```

exists only on the local branch `codex/t15-diagnostic-commission-recovery` (`4154231`,
"test(t15): observe diagnostic commission recovery"). That branch:

- is based on `d6794a6` (#153), one commit **before** the approved candidate;
- has no remote tracking branch and has never been pushed or reviewed;
- changes `deploy/k8s/t15/crash-qualification.ps1` only (+437 / −4) — no application code.

### 3. Neither harness observes the dispatch record at all

`ai_execution_dispatch`, `AVAILABLE`, `DISPATCH_OWNED` and `IN_FLIGHT` appear **nowhere** in either
version of `crash-qualification.ps1`. Both were written against the pre-#154 design, in which the
fencing record did not exist. A `PASS` from either would say nothing about whether the #154 fencing
actually works — it would only confirm the end-state counts.

## Required proof — coverage matrix

| # | Required proof | `4154231` branch harness | Gap |
| ---: | --- | --- | --- |
| 1 | one commission row | `diagnosticCommission -eq 1` | — |
| 2 | same `requestId` before/after reclaim | counts keyed on `Fixture.DiagnosticRequestId` throughout | — |
| 3 | same original context/grounding identity | *not observed* | **must add**: `context_id` / `context_as_of` unchanged across reclaim |
| 4 | `tokenA != tokenB` | `OldToken -ne NewToken` | — |
| 5 | attempt 1 → 2 | `NewAttempt -eq OldAttempt + 1` | — |
| 6 | exactly one dispatch-CAS winner | *not observed* | **must add**: one `DISPATCH_OWNED` transition, one `owner_token`, `fence = 1` |
| 7 | exactly one provider invocation | `diagnosticExecution -eq 1` / `SUCCEEDED` | — |
| 8 | no second commission | `diagnosticCommission -eq 1` at every checkpoint | — |
| 9 | no second request identity | implied by 2 and 8 | — |
| 10 | exactly one terminal execution | `diagnosticTerminal -eq 1` | — |
| 11 | exactly one diagnostic gate/outcome | `diagnosticGate -eq 1` | — |
| 12 | no duplicate mastery/adaptation/outbox | `evidence/mastery/outbox = 1/1/1` | — |
| 13 | workflow `COMPLETED` | `COMPLETED` / `WORKFLOW_COMPLETED` | — |
| 14 | cursor-history PASS | `Assert-CursorHistory` | — |
| 15 | provenance/correlation reconstructable | `Assert-Scenario` provenance block | — |

Two sequence steps are not in the matrix and are equally unobserved:

- **commission persisted as `AVAILABLE`** — nothing asserts the row exists in `AVAILABLE` with
  `owner_token IS NULL` and `fence = 0` at the `WORKFLOW_AFTER_DIAGNOSTIC_COMMISSION` boundary;
- **B marks provider invocation `IN_FLIGHT`** — nothing asserts `state` reaches `IN_FLIGHT` with
  B's `owner_token` and matching `fence` before the provider call.

### Fail-immediately conditions

| Condition | Covered |
| --- | --- |
| provider invocation = 0 | yes — `Assert-DiagnosticCommissionOnlyCheckpoint` requires `diagnosticExecution -eq 0` at all three pre-reclaim checkpoints, and the final proof requires exactly 1 |
| provider invocation > 1 | yes — final `diagnosticExecution -eq 1` |
| B receives `AI_EXECUTION_ABANDONED` | yes — final status must be `SUCCEEDED` |
| request identity changes | yes — counts keyed on the deterministic `requestId` |
| a second commission is created | yes — `diagnosticCommission -eq 1` |
| **redispatch from `DISPATCH_OWNED` or `IN_FLIGHT`** | **no — unobservable today** |

## Specification for the harness change

Scope: `deploy/k8s/t15/crash-qualification.ps1` only. No application code, no image rebuild, no
lock change.

1. Rebase `codex/t15-diagnostic-commission-recovery` (`4154231`) onto the approved candidate
   `62cbed8`, so the harness and the artifact under test share one commit.
2. Capture `core.ai_execution_dispatch` for the scenario `requestId` into the scenario record at
   each existing checkpoint — before kill, after pod death, while the replacement is held, and
   final — as raw rows, not derived booleans.
3. Add assertions to `Assert-DiagnosticCommissionRecoveryProof`:
   - at the `WORKFLOW_AFTER_DIAGNOSTIC_COMMISSION` boundary and after A's death:
     `state = 'AVAILABLE'`, `owner_token IS NULL`, `fence = 0`, `invocation_started_at IS NULL`;
   - `context_id` and `context_as_of` identical in the post-death row and the final row;
   - exactly one transition to `DISPATCH_OWNED`, a single non-null `owner_token`, and `fence = 1`
     — B is the only CAS winner, and A never held ownership;
   - `state = 'IN_FLIGHT'` with B's `owner_token` and the same `fence` before the provider call;
   - fail immediately on a second `ownership_acquired_at`, or on any transition out of
     `DISPATCH_OWNED` or `IN_FLIGHT` back to `AVAILABLE`.
4. Extend `expectedInvariant.diagnosticCommissionRecoveryProof` with a `dispatchProof` block and
   update `deploy/k8s/t15/evidence-schema.json` to match.
5. Correct the stale `$expectedFailure = $true` / `$expectedAdaptation = $false` in the
   `diagnostic-commission` case so the merged harness expects the remediated outcome.

## Also outstanding from Phase 1

`deploy/k8s/t15/publish-images.ps1` carries an unreviewed local fix (+26 / −1): its migration set
was the hard-coded literal `1..34`, which would have written a 34-migration attestation for a
35-migration candidate. It now derives the set from the candidate worktree's own migration
directory, with duplicate and contiguity guards. It should ride the same review as the harness work.

Separately, `deploy/k8s/t15/candidate-integrity.ps1` still asserts `"V034 applied successfully"` as
a fixed literal. The Phase-1 attestation is sound — exact set equality independently proves `V035`
applied successfully — but that literal will keep passing on candidates that have long moved past
`034`, and should be generalised to the highest approved migration.

## Status

Phase 2 has not run. It should not run until the harness change above is reviewed and merged, at
which point the scenario can be executed against the already-attested candidate without rebuilding
or repinning anything.
