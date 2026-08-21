# MVP-1 release board

The standing view of what blocks the MVP-1 release candidate. Updated as tasks land.

This exists because R1 has been open since MVP-0 and is easy to lose sight of: it is not blocked on
engineering, so it never surfaces in a task, and every task can pass without it moving. It must not
become a surprise at M1-T18. `Mvp1ReleaseBoardTests` fails if R1 disappears from this file or is
recorded as closed without evidence.

## Blocking the release candidate

| Item | Status | Owner | What closing it requires |
| --- | --- | --- | --- |
| **R1 — calibrated performance baseline** | 🔴 **OPEN** | not assigned | **No longer hardware.** An attested `perf-standard-01` environment was provisioned, measured and destroyed on 2026-08-21 — see the [R1 evidence package](evidence/r1-calibrated-baseline.md). What remains is a decision: the canonical 60 rps workload run from one source IP collides with a rate-limit ceiling that is itself misconfigured (**TD-R1-01**), so there is not yet a baseline that is both a pass and taken under production policy. |

**What changed on 2026-08-21.** R1 is still open, but for a different reason than before. The
environment question is answered: a conforming host and a separate load generator were provisioned
under Terraform, the attestation passed with zero failures, `v0.1.0-rc3` was deployed by immutable
digest, and two runs were recorded.

- **R1 Run A — VALID / FAIL — normal rate-limit policy.** 9,599 iterations, 12,417 requests, 60 rps
  sustained. Every latency threshold passed comfortably (p95 5.38 ms against a 250 ms budget on
  skill-map reads). The error-rate threshold failed at **17.33%**, all of it HTTP 429 from the
  pre-authentication IP rate-limit tier. Zero restarts and zero OOM kills: policy, not saturation.
- **R1 Run B — PASS — capacity characterization** with the documented `compose.perf-override.yml`
  active and recorded as active. Same workload, images, limits and configuration; only the
  rate-limit ceiling differed. **0.00% failures across 12,519 requests**, all thresholds passed.

Run B does not close R1 and does not supersede Run A. It establishes only that the refusals were the
ceiling rather than the application. **Run A is the canonical result and is not to be relabelled.**

**What was closed earlier, and still is.** The hole that would have survived buying a machine:
`RAMALS_PERF_ENV` was free text copied into the baseline, so a laptop run could label itself
`perf-standard-01` and nothing anywhere would disagree. The id is now earned --
`performance/environment/attest.py` measures the host against a declared spec, a non-conforming run
is recorded as `local-unqualified` whatever the operator asked for, and `baseline.schema.json`
refuses a baseline whose label and attestation disagree.

Running the attestation on any candidate machine prints exactly what it lacks, so provisioning is now
a checklist rather than a judgement:

```bash
python3 performance/environment/attest.py
```

The spec's status is `proposed`, not `reference`: its values are a reasoned starting point from the
MVP-0 indicative run, and the first calibrated run on a registered host confirms or revises them.

**R1 blocks M1-T18.** Under [M1-ADR-000](../adr/M1-ADR-000-mvp1-engineering-before-r1.md), MVP-1
engineering may proceed while R1 is open — that exception covers *building*, not *releasing*. The RC
and any deterministic-versus-agentic comparison remain blocked until R1 closes or is explicitly
risk-accepted by name under that ADR.

Nothing in T00–T17 will close R1. As of 2026-08-21 it needs a policy decision — **TD-R1-01** — and a
re-run under whatever that decision settles on; the environment itself is no longer the obstacle.

What *is* measured at MVP-1, and what is deliberately absent, is recorded in the
[MVP-1 baseline record](mvp1-baseline.md). It is the reference point MVP-2 compares against, and it
names the unmeasured dimensions so they cannot later be mistaken for baselines of zero.

## Open technical debt from R1

Raised by the 2026-08-21 runs. TD-R1-01 gates closing R1; TD-R1-02 is already fixed in code and is
listed because the refactor it names is broader than the fix that landed.

| Item | Status | What it is | What closing it requires |
| --- | --- | --- | --- |
| **TD-R1-01 — canonical workload vs rate-limit policy** | 🔴 open | The canonical 60 rps `mixed-learning` workload runs from a single load-generator IP and is refused by the pre-authentication rate-limit tier, which `application.yml` binds to `capacity 120 / refill 60` — the values `RateLimitProperties` intends for the *per-subject* tier, whose own default is `600 / 300` for the IP tier. The two-tier design shipped; the configuration kept the pre-fix numbers on the wrong tier. Beyond benchmarking, this partially reinstates the shared-egress problem [`performance-baseline.md` §4](evidence/performance-baseline.md) recorded as resolved: users behind one NAT still share a 60 rps allowance. | A decision on how future calibrated runs are made: (a) run with a documented capacity override and label every baseline accordingly, (b) distribute load across multiple source IPs so the benchmark exercises the application rather than the ceiling, or (c) keep the canonical run under production policy and add a **separate** rate-limit-resilience scenario that asserts the limiter behaves correctly. Whichever is chosen, the IP-tier binding in `application.yml` needs review on its own merits — it is a product concern, not only a benchmarking one. |
| **TD-R1-02 — failed threshold runs must still produce a baseline** | 🟡 fixed, refactor outstanding | k6 exits non-zero on a breached threshold and `run-baseline.sh` ran under `set -e`, so it aborted before distillation: the run most worth recording was the only one that recorded nothing. R1 Run A produced no `.baseline.json`. Because the `setup_data` scrub lives in that same block, Run A's summary also retained **20 live bearer tokens**, caught by gitleaks. | The immediate defect is fixed: the status is captured rather than fatal, distillation and the scrub always run, the status is re-raised afterwards, and the baseline records `thresholds_passed`, `k6_exit_status` and `performance_rate_limit_override`. A CI guard drives the script with a stub k6 that fails after writing `setup_data`. What remains is the refactor proper — a FAIL baseline is currently the same schema with a flag, and `baseline.schema.json` does not yet require the verdict field, so a consumer can still read a failing baseline as a passing one. |

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
| 3a | Hardening: the expand/contract guard judges complete statements rather than physical lines | ✅ merged |

**Slice 3's guard needed hardening, and 3a supplied it.**
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
