# MVP-1 release board

The standing view of what blocks the MVP-1 release candidate. Updated as tasks land.

This exists because R1 has been open since MVP-0 and is easy to lose sight of: it is not blocked on
engineering, so it never surfaces in a task, and every task can pass without it moving. It must not
become a surprise at M1-T18. `Mvp1ReleaseBoardTests` fails if R1 disappears from this file or is
recorded as closed without evidence.

## Blocking the release candidate

| Item | Status | Owner | What closing it requires |
| --- | --- | --- | --- |
| **R1 — calibrated performance baseline** | 🔴 **OPEN** | not assigned | An authoritative fixed-spec environment. The harness is repaired and produces clean data, but only from a developer workstation, and developer-machine numbers are indicative only (Doc 07 §4). |

**R1 blocks M1-T18.** Under [M1-ADR-000](../adr/M1-ADR-000-mvp1-engineering-before-r1.md), MVP-1
engineering may proceed while R1 is open — that exception covers *building*, not *releasing*. The RC
and any deterministic-versus-agentic comparison remain blocked until R1 closes or is explicitly
risk-accepted by name under that ADR.

Nothing in T00–T17 will close R1. It needs an environment decision, not a commit.

What *is* measured at MVP-1, and what is deliberately absent, is recorded in the
[MVP-1 baseline record](mvp1-baseline.md). It is the reference point MVP-2 compares against, and it
names the unmeasured dimensions so they cannot later be mistaken for baselines of zero.

## Task progress — canonical task → decision mapping

**This table is the single owner of which decisions gate which task.** Other documents reference it
rather than restating it; `docs/adr/README.md` describes what each decision says, not what it gates.
A mapping maintained in several places is a mapping that disagrees with itself, and the copy a
reader happens to open is the one they believe.

A task may be `next` or `done` only when every decision it requires has status **Accepted** in its
ADR file. Having the file is not enough — an ADR in Draft is a decision still being made.


| Task | Status | Gating decision |
| --- | --- | --- |
| M1-T00 Control freeze | ✅ done | M1-ADR-000 ✅ |
| M1-T01 FastAPI foundation | ✅ done | — |
| M1-T02 Canonical OpenAPI contract | ✅ done | M1-ADR-001 ✅, M1-ADR-002 ✅ |
| M1-T03 Workload identity | ✅ done | M1-ADR-003 ✅ |
| M1-T04 Correlation and OTel | ✅ done | — |
| M1-T05 LLMGateway and LiteLLM | ✅ done | M1-ADR-008 ✅ |
| M1-T06 LangGraph runtime | ✅ done | — |
| M1-T07 Tutor Agent V1 | ✅ done | — |
| M1-T08 Spring + UI Tutor integration | ✅ done | M1-ADR-004 ✅ |
| M1-T09 Diagnostic Agent V1 | ✅ done | — |
| M1-T10 Assessment Agent V1 | ✅ done | M1-ADR-006 ✅, M1-ADR-010 ✅ |
| M1-T11 Adaptation Agent V1 | ✅ done | — |
| M1-T12 LIMITED_DURABLE approval | ✅ done | M1-ADR-007 ✅ |
| M1-T13 ai_execution persistence | ✅ done | M1-ADR-005 ✅ |
| M1-T14 Resilience, latency, cost | ✅ done | — |
| M1-T15 AI evaluation release gates | 🟡 slice 1 of 2 | M1-ADR-009 ✅ |
| M1-T16 AI security challenge | ⬜ | — |
| M1-T17 CI/CD and deployment packaging | ⬜ | — |
| M1-T18 MVP-1 E2E validation and RC | ⬜ | **R1** 🔴 |

### Completed readiness slices

| Slice | Status | Evidence |
| --- | --- | --- |
| S0-07 Assessment Candidate Provenance Intake | ✅ done | Merged implementation and V018 provenance migration |
| TD-S0-07-01 audit transaction-manager construction cleanup | ✅ done | Merged implementation; Spring-managed transaction manager is the only production path |
| M1-T12 limited-durable approval workflow | ✅ done | V019 durable approval state/command idempotency; MFA-gated API; atomic revalidation and promotion; rollback and authorization tests |
| M1-T14 resilience, latency and cost | ✅ done | [M1-T14 evidence](evidence/m1-t14-resilience-latency-cost.md) |

## Decisions written just in time

**Operating rule: no implementation task may start while one of its required architectural decisions
is still OPEN.** Each ADR is an architecture gate immediately before the task that depends on it —
not a batch written ahead of time, and not something a task discovers it needed halfway through.

The reasoning is that both failure modes are real. A decision written far ahead of its
implementation is made without the information the implementation produces: M1-ADR-004 is the
example, where the argument that settled it — validation needs a complete response — only became
concrete once M1-T07 existed. A decision *not* written before the task is made anyway, implicitly,
by whoever writes the first line of code that assumes an answer.

`Mvp1ReleaseBoardTests` enforces the rule: a task cannot be marked started or done while the board
lists a decision it requires as outstanding.

Outstanding: none. Every decision the package registers is authored and Accepted.

Written so far under this practice: M1-ADR-004 (before T08), M1-ADR-008 (before T05), M1-ADR-006
(before T10), M1-ADR-007 (before T12), M1-ADR-005 (before T13) and M1-ADR-009
(before T15).

## Known gaps carried forward

| Gap | Where it bites |
| --- | --- |
| Doc 02 §4's step and repair ceilings were mutually unsatisfiable (8 steps, 2 repairs, 12 needed) | Resolved by S0-01: node executions are derived from the current graph (12), while repair cycles, model calls, and the caller-owned absolute deadline remain separate controls. |
| Doc 07 quality thresholds unmeasurable on `ci-fake` | Tutor pedagogical and functional rubrics cannot be scored without a real route and a provider credential. |
| Deployed backend digest predates the M1-T04 MDC fix | The shared environment cannot answer a support-code lookup until a release is cut. |
| Architecture guards are source-scanning rather than ArchUnit | `DomainNeutralityTests` and `EvaluationAuthorityBoundaryTests` read `.java` files as text, so they see imports and literals but not the type graph. Migration tracked as **S0-03**; not a blocker for the guards they currently enforce. |
| The MVP-1 Canonical Package is cited by every M1 ADR but is not in the repository | A fresh clone cannot audit an MVP-1 decision against its source, unlike MVP-0 whose nine documents are frozen in `docs/` with checksums. Bringing it in is a decision about repository scope, not a docs edit. |
| Database implementation records stopped at M0-T06 | Migrations kept arriving through MVP-1. The schema is documented in the migrations and ADRs, so nothing is undocumented — but `docs/database/` no longer indexes it. Resume the practice or retire it deliberately. |
