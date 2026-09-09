# M2-ADR-035: Intervention outcome and effectiveness — DEFERRED roadmap stub

- **Status:** DEFERRED — reserves the boundary and the number. **This ADR authorizes no schema, no
  contract, no code, and no implementation.** It is superseded by its own full ADR when the owning
  milestone is scheduled.
- **Date:** 2026-09-08
- **Relates to:** M2-ADR-001 (deterministic core authoritative; agents propose), M2-ADR-023 §2/§3
  (diagnostic confidence is separate from mastery and never feeds it; H7 verification inherits the
  still-deferred retention/spaced-reassessment constraint), M2-ADR-030 (H7 longitudinal projection,
  and its forbidden-terminology list), M2-ADR-032 (advisory AI boundary),
  [`target-intelligence-loop.md`](../architecture/target-intelligence-loop.md) (the "Intervention →
  Reassessment → Outcome → Intervention Effectiveness" stages).
- **Originates here**, on the same repository-native basis as M2-ADR-023 through M2-ADR-034.

## Problem

RAMALS can already detect *what* a learner misunderstands (H1 gap diagnosis; G2 misconception
evidence, `MISCONCEPTION_EVIDENCE_V1`; G3 confidence, `DIAGNOSTIC_CONFIDENCE_V1`; H6/H7 reports). It
has **no governed record of what was done about it and whether it worked**. There is no
`intervention` or `intervention_outcome` table. `core.learning_recommendation` (`V010`) plus
`ledger.decision_record` record a recommendation *decision*, not a closed
diagnosis → intervention → reassessment → outcome loop. H7 (`M2-ADR-030`) projects post-baseline
evidence for one misconception but is explicitly forbidden from expressing verification,
resolution, or effectiveness.

## Strategic intent

A governed, versioned, auditable dataset that captures the chain:

```
diagnosis (misconception_id / hypothesis)
   ->
intervention                         (the action or resource actually undertaken)
   ->
pre-intervention evidence            (references into ledger.evidence)
   ->
reassessment                         (an assessment attempt undertaken to re-measure)
   ->
post-intervention evidence           (references into ledger.evidence)
   ->
outcome                              (POSITIVE / NEGATIVE / INSUFFICIENT_EVIDENCE)
   ->
measured effectiveness               (deterministic INTERVENTION_EFFECTIVENESS_V1 aggregation)
```

The first objective is **governed evidence collection** — a trustworthy, reproducible history.
Later milestones may use that history to inform intervention selection.

## Architectural invariants (to be honoured by the full ADR)

- Append-only and immutable; every row carries provenance, the governing policy/engine version, and
  `interactionId` / `traceId` / `spanId` correlation.
- Pre- and post-intervention evidence are **references into the common `ledger.evidence`** — no
  parallel evidence store.
- Effectiveness is a **separate stream**. It never feeds `WEIGHTED_MASTERY_V1`,
  `EVIDENCE_CONFIDENCE_V2`, `MASTERY_STATUS_POLICY_V2`, or G3 (M2-ADR-023 §2).
- `INSUFFICIENT_EVIDENCE` is a first-class outcome; a failed or ineffective intervention is itself
  useful recorded evidence, never silently discarded or retried blindly.
- Agents may *propose* interventions; deterministic services decide (M2-ADR-001, M2-ADR-032). No new
  AI authority.
- The **reassessment leg** must remain distinguishable in the audit trail from ordinary adaptive
  selection reaching bank exhaustion, and from the deferred retention/spaced-reassessment capability
  — M2-ADR-023 §3 records that this constraint applies and is not satisfied by anything in H1–H7.
- Deterministic, versioned, reproducible aggregation (`INTERVENTION_EFFECTIVENESS_V1`) with a frozen
  behaviour vector, following the `EngineVersionFreezeTests` discipline.

## Dependencies

- The still-unscoped **M2-ADR-023 §3 retention / spaced-reassessment decision**, which the
  reassessment leg depends on.
- The "Next Best Action" stage of the intelligence loop is a *consumer* of this dataset, not a
  prerequisite for it.

## Explicit non-goals

- **No reinforcement learning.** **No learned / ML effectiveness model.**
- No automatic or AI-driven intervention selection.
- No change to mastery, diagnostic selection (`V1`–`V5`), G2, G3, H5, H6, or H7.
- No new AI authority of any kind.
- No schema, migration, contract, or code — this stub authorizes none.

## Expected roadmap horizon

MVP-3 foundation (governed dataset first). Any selection/optimization use of the dataset is LATER
(MVP-5/6 in the strategic roadmap).

## Revisit / activation criteria

Activate — by authoring the full ADR and a separately reviewed design PR — when **all** hold:

1. A concrete product milestone requiring closed-loop improvement is scheduled.
2. The M2-ADR-023 §3 retention / spaced-reassessment decision has been written.
3. A separate design review precedes any schema or code.

## Note on the ADR register

Adds `M2-ADR-035` to [`docs/adr/M2-ADR-register.md`](M2-ADR-register.md), clearly marked
**Deferred**, immediately after `M2-ADR-034`.
