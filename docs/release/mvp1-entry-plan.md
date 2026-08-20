# MVP-0 exit → MVP-1 entry plan

MVP-0 shipped as `v0.1.0-rc2`. This document states, against evidence, which MVP-0 exit criteria are
met, what is left, and the order to do it in. It contains **no MVP-1 code and proposes none until
Gate A closes**.

The point of MVP-0 was never the features. It was to produce a *deterministic control* — a system
whose decisions are reproducible from stored evidence — so that later agentic versions can be
measured against something rather than asserted to be better. Every item below protects that.

### Terms used here

**Gate A** and **Gate B** are the two batches of work between here and the first line of MVP-1 code.
They are release gates in the planning sense — a set of conditions that must hold before the next
increment starts — and they are **unrelated** to the two other things this repository already calls
gates:

| Name | What it is | Where |
| --- | --- | --- |
| `ci-gate` | The single required status check on every PR | [CI branch protection](../architecture/ci-branch-protection.md) |
| health gates | The six post-deploy probes the controller must pass before marking a version `HEALTHY` | `deploy/health-gates.sh` |
| policy gate | The runtime check that constrains agent output so it cannot become authoritative | §3 B4 |
| **Gate A / Gate B** *(this document)* | Planning checkpoints: A finishes MVP-0, B builds the boundary before Python exists | below |

Gate A must close before MVP-1 code is written. Gate B must close before any Python workload touches
the platform. They are independent of each other and can run in parallel.

Section references are to the
[Implementation Master Plan v1.0](../RAMALS_MVP0_Implementation_Master_Plan_v1.0.docx) unless stated
otherwise. Decision records live in [docs/adr](../adr/README.md).

## 1. Where MVP-0 actually stands

Against the six entry criteria recorded in the [release candidate](mvp0-release-candidate.md#6-mvp-1-entry-criteria):

| # | Criterion | Status | Evidence |
| --- | --- | --- | --- |
| 1 | Performance baseline on the authoritative environment | ❌ **Not met** | Harness repaired and produces clean data, but only from a developer workstation. Sequencing exception adopted ([M1-ADR-000](../adr/M1-ADR-000-mvp1-engineering-before-r1.md)); R1 still blocks the MVP-1 RC |
| 2 | Pull-based deployment proven, including rollback | ✅ Met | Full sequence executed on **both** `rc1` and `rc2` digests: `HEALTHY`, bad version rolled back to a *verified* known-good digest, held, then recovered |
| 3 | Live-token authorization verified | ✅ Met | 26/26 against a real Keycloak token, including the MFA-gated admin path |
| 4 | R1–R3 closed or explicitly accepted | ⚠️ Partial | R2 and R3 closed; **R1 open** |
| 5 | Deterministic control frozen | ✅ Met | `EngineVersionFreezeTests` pins all seven engines by behaviour hash and fails if a new engine is unfrozen |
| 6 | Boundary respected | ✅ Met | B1–B4 all done: `ramals_ai_runtime` denied everything (`42501`); workload identity issued (M1-T03); trace/interaction continuation proven (M1-T04); agent output constrained by deterministic gates throughout M1-T07…T14 |

Five of six met; the sixth is R1. **R1 is the only item blocked on anything external.**

## 2. Gate A — finish MVP-0 before writing MVP-1 code

### A1. Close R1, or accept it in writing — sequencing exception adopted

[M1-ADR-000](../adr/M1-ADR-000-mvp1-engineering-before-r1.md) is now adopted in the repository:
isolated MVP-1 engineering may begin before R1 closes. **R1 itself remains open and owned** — it
still blocks the MVP-1 release candidate, any calibrated deterministic-versus-agentic comparison and
any performance claim, unless a named owner accepts the risk with scope and expiry.

The rest of this section stands: it describes what closing R1 actually requires.

This is the only item that needs infrastructure this project does not control.

**To close it:** stand up the authoritative fixed-spec environment, run `mixed-learning` and
`diagnostic` (the latter carries Adaptive Decision Latency), and commit the machine-readable
baselines with a regression tolerance — the existing convention is >10–15% degradation triggers
investigation.

The harness is ready: it provisions its own Keycloak fixtures, spreads load across distinct learners,
tags every request class, and scrubs tokens out of exported summaries. An indicative run passes every
class budget with 0% errors, so the remaining work is environmental, not engineering.

**To accept it instead:** a named owner records that MVP-1 may proceed without a calibrated control,
and the release notes stop implying a latency commitment. This is a legitimate choice — but it costs
the ability to say later that an agentic version did or did not regress performance, which is a
stated purpose of MVP-0.

> **Recommendation: close it.** The whole justification for building a deterministic baseline first
> is to have something to compare against. Starting MVP-1 without it discards most of that value for
> the sake of a few days.

### A2. Freeze the deterministic control — ✅ **done**

Seven versioned engines currently define every consequential decision:

```
DIAGNOSTIC_SCORING_V1   WEIGHTED_MASTERY_V1     EVIDENCE_CONFIDENCE_V1
MASTERY_STATUS_POLICY_V1  RECOMMENDATION_POLICY_V1  PROGRESSION_POLICY_V1
SESSION_POLICY_V1
```

They are stamped onto persisted records, so a historical decision can be reconstructed. What is
missing is anything that *stops* someone editing a `_V1` constant in place — which would silently
invalidate every decision already recorded under that identifier.

`EngineVersionFreezeTests` hashes each engine's output over fixed input vectors and asserts it
against a recorded value. Refactoring is free; changing a weight, threshold, rounding mode or branch
is not. A second test scans the sources for `_V*` identifiers and fails if any lacks a frozen vector,
so a new engine cannot be added without being frozen.

Verified by perturbing one weight (`QUIZ` 1.50 → 1.55): the guard tripped and named the engine.

If it fails, the fix is never to update the expected hash. Either revert, or mint a new identifier,
leave the old one untouched so existing records stay reconstructable, and record an
[ADR](../adr/README.md).

### A3. Re-run the rollback drill on the released digests — ✅ **done**

Executed against the `rc2` digests: a bad version failed the gates, the environment rolled back to
the verified `rc2` digest and held the release (exit 3), the held version was refused on the next
reconcile (exit 2), and a corrected manifest returned it to `HEALTHY` (exit 0). Recorded in
[live-stack drills](evidence/live-stack-drills.md#6-re-validated-against-the-v010-rc2-digests).

Criterion 2 no longer carries an asterisk.

## 3. Gate B — the boundary, before any Python touches the platform

MVP-1 introduces a second runtime. Criterion 6 says Python workloads must not reach `core`/`ledger`
directly and must not be the authority on mastery or progression. Today that is a sentence in a
document; there is nothing to enforce it, because there is no AI identity yet.

Build the constraint **before** the thing it constrains, exactly as MVP-0 built database invariants
before the features that rely on them.

### B1. A third database identity — ✅ **done**

`ramals_ai_runtime` is provisioned NOLOGIN with no password, and `V015` revokes schema USAGE, all
table/sequence/function privileges, future default privileges, and CONNECT to the database itself.

The role is **not** created by the migration: `ramals_core_migration` deliberately lacks `CREATEROLE`,
and a migration able to mint roles is a privilege-escalation path. That surfaced while building this —
the first attempt failed with `42501`, which is the least-privilege model working. Provisioning
creates the role; the migration only removes privilege from it.

`AiRuntimeBoundaryIntegrationTests` proves `42501` on read, write and DDL across all eight platform
tables, and that no schema USAGE is held. It re-grants LOGIN and CONNECT to demonstrate the *object*
denials positively — asserting only "the connection fails" would pass for uninteresting reasons, such
as a wrong password.

### B2. Workload identity, not a borrowed learner token — ✅ **done**

Delivered by M1-T03 under [M1-ADR-003](../adr/M1-ADR-003-workload-identity.md); evidence in
[workload-identity drill](evidence/workload-identity-drill.md).

The AI service authenticates as itself — client credentials, its own audience — never by replaying a
learner's token. A learner token reaching the AI service and continuing outward is privilege
laundering: the platform can no longer distinguish "the learner asked for this" from "a model
decided to do this".

### B3. Trace context continuation — ✅ **done**

Delivered by M1-T04; evidence in [interactionId drill](evidence/interaction-id-drill.md).

[Master Plan](../RAMALS_MVP0_Implementation_Master_Plan_v1.0.docx) §5 (Mandatory Propagation
Contract) already specifies it: the Python side **continues** the incoming W3C trace context and
propagates `X-Interaction-ID` rather than starting an unrelated trace. MVP-0 wired the correlation
model precisely so this would not need redesign. A test that an interactionId survives a round trip
through the AI boundary belongs in the first MVP-1 change set, not the last.

### B4. Agent output is a proposal, not a write — ✅ **done**

Held across every agent task. `EvaluationAuthorityBoundaryTests` pins that `ledger.evidence` has a
single writer and that no AI component can reach an authoritative repository, engine or service.

Authoritative mastery and progression stay in the deterministic engine. Agent output enters through a
policy gate that can reject it, and whatever is accepted is recorded with its own provenance so a
decision can still be reconstructed. If an agent can write `ledger.mastery_snapshot`, the control is
gone and MVP-0's guarantees stop holding.

## 4. Suggested MVP-1 sequencing — superseded

This section originally proposed its own six-task sequence and labelled it `M1-T01` … `M1-T06`.
Those identifiers now mean something else: the MVP-1 Canonical Package numbers the real tasks
`M1-T00` … `M1-T18`, and under that numbering `M1-T03` is workload identity, `M1-T04` is correlation
and OTel, and `M1-T05` is the LLM gateway — none of which is what the table said.

The proposal is removed rather than renumbered. It was written before the canonical package was
adopted and its content is now covered by the real plan; keeping a second live meaning for
`M1-T04` inside `docs/release/`, where the evidence drills use the canonical one, was the actual
hazard. The shape of the argument it made survives in the package's own ordering.

**The authoritative sequence and its status is the
[MVP-1 release board](mvp1-release-board.md).** The deferred scope named in
[Master Plan](../RAMALS_MVP0_Implementation_Master_Plan_v1.0.docx) §2 (MVP-0 Scope Freeze) — LLM
calls, LangGraph, RAG / pgvector, Temporal, Redis, Kafka event backbone, labs, Kubernetes,
multi-tenancy, BKT/IRT/CAT — remains larger than one increment and is scoped there.

## 5. Definition of ready for MVP-1

Start MVP-1 when:

- [ ] R1 closed with a committed baseline, **or** accepted in writing by a named owner
- [x] Engine version identifiers frozen and guarded in CI
- [x] Rollback drill executed against the released digests
- [x] `ramals_ai_runtime` exists with a privilege test proving it cannot reach `core`/`ledger`
- [x] AI workload identity issued, distinct from any learner token
- [x] Trace/interaction continuation contract has a failing-then-passing test

Everything except the first is done. The first still needs a machine, and R1 remains open.
