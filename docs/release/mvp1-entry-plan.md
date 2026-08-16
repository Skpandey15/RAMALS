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
| 1 | Performance baseline on the authoritative environment | ❌ **Not met** | Harness repaired and produces clean data, but only from a developer workstation |
| 2 | Pull-based deployment proven, including rollback | ✅ Met | Full sequence executed on **both** `rc1` and `rc2` digests: `HEALTHY`, bad version rolled back to a *verified* known-good digest, held, then recovered |
| 3 | Live-token authorization verified | ✅ Met | 26/26 against a real Keycloak token, including the MFA-gated admin path |
| 4 | R1–R3 closed or explicitly accepted | ⚠️ Partial | R2 and R3 closed; **R1 open** |
| 5 | Deterministic control frozen | ⚠️ Not formalised | The versions exist and are stamped on records; nothing yet *prevents* changing them |
| 6 | Boundary respected | ⚠️ Not yet enforceable | Only two DB roles exist; there is no AI workload identity to constrain |

Three met, three outstanding. Only one of the three is genuinely blocked on anything external.

## 2. Gate A — finish MVP-0 before writing MVP-1 code

### A1. Close R1, or accept it in writing

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

### A2. Freeze the deterministic control

Seven versioned engines currently define every consequential decision:

```
DIAGNOSTIC_SCORING_V1   WEIGHTED_MASTERY_V1     EVIDENCE_CONFIDENCE_V1
MASTERY_STATUS_POLICY_V1  RECOMMENDATION_POLICY_V1  PROGRESSION_POLICY_V1
SESSION_POLICY_V1
```

They are stamped onto persisted records, so a historical decision can be reconstructed. What is
missing is anything that *stops* someone editing a `_V1` constant in place — which would silently
invalidate every decision already recorded under that identifier.

**Do:** add a CI guard that fails when the behaviour behind an existing `_V*` identifier changes
without a new identifier and an [ADR](../adr/README.md). A checksum over the engine constants,
asserted in a test, is enough — the same shape as the existing migration-checksum protection.

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

### B1. A third database identity

Only `ramals_core_migration` and `ramals_core_runtime` exist. Add `ramals_ai_runtime` with **no
grants on `core` or `ledger` at all**. If the AI service needs learner context, it gets it from the
platform API under its own authorisation, not from SQL.

Verify it the way MVP-0 verifies the runtime role: a privilege test asserting `42501` on every table
the AI identity must not touch. That test is the boundary; the document is only a description of it.

### B2. Workload identity, not a borrowed learner token

The AI service authenticates as itself — client credentials, its own audience — never by replaying a
learner's token. A learner token reaching the AI service and continuing outward is privilege
laundering: the platform can no longer distinguish "the learner asked for this" from "a model
decided to do this".

### B3. Trace context continuation

[Master Plan](../RAMALS_MVP0_Implementation_Master_Plan_v1.0.docx) §5 (Mandatory Propagation
Contract) already specifies it: the Python side **continues** the incoming W3C trace context and
propagates `X-Interaction-ID` rather than starting an unrelated trace. MVP-0 wired the correlation
model precisely so this would not need redesign. A test that an interactionId survives a round trip
through the AI boundary belongs in the first MVP-1 change set, not the last.

### B4. Agent output is a proposal, not a write

Authoritative mastery and progression stay in the deterministic engine. Agent output enters through a
policy gate that can reject it, and whatever is accepted is recorded with its own provenance so a
decision can still be reconstructed. If an agent can write `ledger.mastery_snapshot`, the control is
gone and MVP-0's guarantees stop holding.

## 4. Suggested MVP-1 sequencing

Proposal, not an approved plan. The deferred scope in
[Master Plan](../RAMALS_MVP0_Implementation_Master_Plan_v1.0.docx) §2 (MVP-0 Scope Freeze) — LLM
calls, LangGraph, RAG / pgvector, Temporal, Redis, Kafka event backbone, labs, Kubernetes,
multi-tenancy, BKT/IRT/CAT — is larger than one increment and should be scoped deliberately.

| Task | Outcome | Depends on |
| --- | --- | --- |
| M1-T01 | `ramals-ai` service skeleton, workload identity, health, structured logs | Gate B1, B2 |
| M1-T02 | Boundary enforcement suite: DB privilege denial, no learner-token replay | M1-T01 |
| M1-T03 | Trace/interaction continuation across the boundary, proven end to end | M1-T01 |
| M1-T04 | First LLM call behind a cost and deadline budget, fully recorded | M1-T03 |
| M1-T05 | Policy gate: agent proposals reviewed against deterministic state | Gate B4 |
| M1-T06 | A/B harness comparing agentic output against the frozen MVP-0 control | Gate A1, A2 |

M1-T06 is the reason for all of it, and it is the task that silently becomes impossible if A1 and A2
are skipped.

## 5. Definition of ready for MVP-1

Start MVP-1 when:

- [ ] R1 closed with a committed baseline, **or** accepted in writing by a named owner
- [ ] Engine version identifiers frozen and guarded in CI
- [ ] Rollback drill executed against the released digests
- [ ] `ramals_ai_runtime` exists with a privilege test proving it cannot reach `core`/`ledger`
- [ ] AI workload identity issued, distinct from any learner token
- [ ] Trace/interaction continuation contract has a failing-then-passing test

Everything except the first is a day or two of work inside this repository. The first needs a
machine.
