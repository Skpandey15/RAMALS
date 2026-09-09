# M2-ADR-036: Production-simulation evidence model — DEFERRED roadmap stub

- **Status:** DEFERRED — reserves the boundary and the number. **This ADR authorizes no schema, no
  contract, no code, and no implementation.** It is superseded by its own full ADR when the owning
  milestone is scheduled.
- **Date:** 2026-09-08
- **Relates to:** M1-ADR-010 / M2-ADR-010 (AI assessment evaluation is formative / proposal-only; the
  deterministic core decides), M2-ADR-022 (**reserved, still unwritten** — the M1-ADR-010-vs-MVP-2
  conflict over free-text / rubric evaluation authority; it also currently gates `SHORT_ANSWER` /
  `USE_CASE`), `V047` (`AssessmentItemType` = `SINGLE_CHOICE`, `FILL_BLANK`, `SHORT_ANSWER`,
  `USE_CASE`), `ledger.evidence` (`V007`), the MVP-0 Zero Trust Security Architecture,
  [`target-intelligence-loop.md`](../architecture/target-intelligence-loop.md).
- **Originates here**, on the same repository-native basis as M2-ADR-023 through M2-ADR-035.

## Problem

Enterprise technical mastery is currently evidenced almost entirely through recognition and recall
items (`SINGLE_CHOICE`, `FILL_BLANK`). `SHORT_ANSWER` and `USE_CASE` are authorable but deliberately
unreachable by a learner, gated behind the unwritten M2-ADR-022. There is no
incident-simulation / debugging / architecture-scenario / secure-lab / operational-troubleshooting
evidence modality, and no capture of a learner's *reasoning* rather than only a final answer.

## Strategic intent

Introduce production-simulation / scenario evidence as a **first-class evidence modality** that
writes the **same `ledger.evidence`** and flows through the **same** deterministic mastery,
confidence, and diagnostic architecture — never an independent scoring system. Capture structured
reasoning-trace evidence, illustratively:

```
observe  ->  hypothesize  ->  investigate  ->  cite evidence  ->  decide  ->  remediate
```

so RAMALS can hold evidence about *how* a learner reasoned through a failure, not just whether the
final decision was correct. Candidate future modalities include incident simulation, debugging,
architecture-scenario, secure lab, coding task, operational troubleshooting, and distributed-system
failure analysis.

## Architectural invariants (to be honoured by the full ADR)

- **Common evidence model only.** Simulation evidence normalizes into `ledger.evidence` with
  provenance, policy/engine version, and `interactionId` / `traceId` / `spanId` correlation. No
  independent simulation score store.
- Scoring is deterministic, or human, or a governed hybrid. **AI must not become an authoritative
  scorer** unless a future ADR explicitly changes that governance boundary (M1-ADR-010 /
  M2-ADR-010). AI may propose, evaluate, and assist review only.
- Evidence diversity requirements are expressed per `skill_version` policy, reusing the existing
  `accepted_evidence_types` mechanism rather than a parallel one.
- Immutable, append-only, reproducible.
- Secure-lab execution is a **new security surface**. It must extend — never weaken — the Zero Trust
  architecture, workload-identity boundaries, and learner-privacy controls, and needs its own threat
  model.

## Dependencies

- **M2-ADR-022 must resolve free-text / rubric evaluation authority first.** It is a hard predecessor
  and also currently blocks `SHORT_ANSWER` / `USE_CASE` from reaching a learner.
- A secure-lab / execution-environment infrastructure decision.
- Possibly a scoring-rubric determinism ADR, depending on the modality.

## Explicit non-goals

- No independent simulation score store; no scoring path that bypasses `ledger.evidence`.
- No AI authoritative scoring under this stub, and none in the future without an explicit ADR
  changing that boundary.
- No new assessment item type reaching a learner before M2-ADR-022 is accepted.
- No change to mastery, G2, G3, or diagnostic selection.
- No premature ML.
- No schema, migration, contract, or code — this stub authorizes none.

## Expected roadmap horizon

MVP-3 / secure-lab evolution.

## Revisit / activation criteria

Activate — by authoring the full ADR and a separately reviewed design PR — when **all** hold:

1. M2-ADR-022 is accepted.
2. A secure-lab / scenario product milestone is scheduled.
3. A separate design review precedes any schema or code.

## Note on the ADR register

Adds `M2-ADR-036` to [`docs/adr/M2-ADR-register.md`](M2-ADR-register.md), clearly marked
**Deferred**, immediately after `M2-ADR-035`.
