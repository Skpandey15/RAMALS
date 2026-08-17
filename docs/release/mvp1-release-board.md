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

## Task progress

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
| M1-T10 Assessment Agent V1 | ⬜ next | M1-ADR-006 ✅, M1-ADR-010 ✅ |
| M1-T11 Adaptation Agent V1 | ⬜ | — |
| M1-T12 LIMITED_DURABLE approval | ⬜ | M1-ADR-007 |
| M1-T13 ai_execution persistence | ⬜ | M1-ADR-005 |
| M1-T14 Resilience, latency, cost | ⬜ | — |
| M1-T15 AI evaluation release gates | ⬜ | M1-ADR-009 |
| M1-T16 AI security challenge | ⬜ | — |
| M1-T17 CI/CD and deployment packaging | ⬜ | — |
| M1-T18 MVP-1 E2E validation and RC | ⬜ | **R1** 🔴 |

## Decisions written just in time

ADRs are authored immediately before the task they gate, rather than as a batch. A decision written
far ahead of its implementation is written without the information the implementation produces —
M1-ADR-004 is the example: the argument that settled it (validation needs a complete response) only
became concrete once M1-T07 existed.

Outstanding: **M1-ADR-007** (before T12), **M1-ADR-005** (before T13), **M1-ADR-009** (before T15).

Written so far under this practice: M1-ADR-004 (before T08), M1-ADR-008 (before T05), M1-ADR-006
(before T10).

## Known gaps carried forward

| Gap | Where it bites |
| --- | --- |
| Doc 02 §4's step and repair ceilings are mutually unsatisfiable (8 steps, 2 repairs, 12 needed) | Repair loops are unreachable at the documented ceiling. Implemented as written; resolving it is a governance decision. |
| Doc 07 quality thresholds unmeasurable on `ci-fake` | Tutor pedagogical and functional rubrics cannot be scored without a real route and a provider credential. |
| Deployed backend digest predates the M1-T04 MDC fix | The shared environment cannot answer a support-code lookup until a release is cut. |
