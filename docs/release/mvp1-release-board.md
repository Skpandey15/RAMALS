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
| M1-T15 AI evaluation release gates | ✅ done | M1-ADR-009 ✅ |
| M1-T16 AI security challenge | ✅ done | — |
| M1-T17 CI/CD and deployment packaging | ✅ done | M1-ADR-011 ✅ |
| M1-T18 MVP-1 E2E validation and RC | ⬜ | **R1** 🔴 |

### M1-T17 slices

T17 was planned as two slices. It is three because slice 2 found that the thing slice 2 was meant to
gate did not work: `RouteRegistry.rolled_back()` was reachable only from tests, and had it been
reachable it would have moved the prompt version recorded on every proposal while dispatching the
same prompt. A smoke gate over that would have certified a rollback that had not happened.

| Slice | Scope | Status |
| --- | --- | --- |
| 1 | The AI plane joins the release pipeline: built, scanned, attested, digest-pinned in the manifest, gated independently, and rollback-capable | ✅ merged |
| 2 | Prompt identity resolves to the artifact that builds it; rollback is a configuration pin validated at startup and verified against the running service (M1-ADR-011) | ✅ merged |
| 3 | Flyway expand/contract enforced in CI, and the additive `prompt_template_id` / `agent_run_id` columns on `core.ai_execution` | ✅ merged |
| 3a | Hardening: the expand/contract guard judges complete statements rather than physical lines | 🟡 in review |

**Slice 3's guard needed hardening, and T17 is not claimed fully hardened until 3a is green.**
The guard shipped in slice 3 matched each rule against one physical line, so ordinary SQL formatting
walked through eight of its nine rules -- `ALTER TABLE core.foo` on one line and `DROP COLUMN value`
on the next was accepted, while the identical statement on a single line was refused. It cried wolf
the same way: a `REVOKE ... FROM PUBLIC` split across two lines lost its exemption.

This was not hypothetical here. Judging statements instead of lines immediately surfaced twelve
`ADD CONSTRAINT ... CHECK` findings across V009, V016, V017 and V022 that the line-scoped matcher
had never been able to see. All four migrations are applied and immutable, so they are recorded as
accepted with their reasons rather than corrected -- and V022's was a genuine rollback hazard when
it shipped, unlike the other three, which constrain columns they add themselves.

3a also adds the four rules the same review found missing: `CREATE UNIQUE INDEX` on a pre-existing
table, `ADD PRIMARY KEY`, `ADD CONSTRAINT ... EXCLUDE USING`, and `DROP DEFAULT`. None of them flags
any existing migration.

**Delivered separately:** P6 agent observability (`agentRunId`/`toolCallId`/`proposalId`) is
tracked against the Observability HLD-LLD rather than against T17, because it is correlation work
rather than CI/CD packaging. See the observability phases below.

**Closed in slice 3.** `core.ai_execution` now records both, so the pivot reaches durable
provenance rather than stopping at the database:

| Column | What it answers |
| --- | --- |
| `prompt_template_id` | whether an execution generated an assessment item or evaluated a response — both otherwise record `ASSESSMENT_PROMPT_V1` |
| `agent_run_id` | which orchestrated run produced a durable execution record |

Both were added as an expand: nullable, no default, no backfill. A rollback restores the previous
image against this schema and that image inserts without them.

**The rule is now enforced rather than remembered.** `scripts/ci/check-migration-compatibility.py`
refuses any migration the previously released image cannot run against — dropped or renamed columns,
tightened nullability, narrowed types, new constraints, and revoked grants — unless the line above
declares the contract phase and names the release whose expand made it safe.

### Observability phases (Business Logging / Exception / Observability HLD-LLD v1.0 §15)

| Phase | Scope | Status |
| --- | --- | --- |
| P6 — Agents | `agentRunId`/`toolCallId`/`proposalId` correlation through the graph, tool calls, logs, spans and the proposal contract | ✅ done |

P6's exit criterion is that agent workflows are traceable end to end. The chain now runs
interactionId → agentRunId → proposalId → toolCallId in every log line a run emits, and the
proposal carries `agentRunId` across the plane boundary so the deterministic core's records can name
the run that proposed a decision. M1-T17 slice 3 carries it the last step, into the durable execution
row.

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
