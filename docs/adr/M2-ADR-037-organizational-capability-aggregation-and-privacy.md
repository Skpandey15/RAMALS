# M2-ADR-037: Organizational capability aggregation and privacy boundary — DEFERRED roadmap stub

- **Status:** DEFERRED — reserves the boundary and the number. **This ADR authorizes no schema, no
  contract, no code, and no implementation.** It is superseded by its own full ADR when the owning
  milestone is scheduled.
- **Date:** 2026-09-08
- **Relates to:** M1-ADR-013 (professional learner contact PII stays outside the PII-minimized
  `core.learner` boundary), the RAMALS Product Vision and Learner Segment Architecture
  ([`docs/product/RAMALS_PRODUCT_VISION_AND_SEGMENT_ARCHITECTURE.md`](../product/RAMALS_PRODUCT_VISION_AND_SEGMENT_ARCHITECTURE.md),
  §10 guardrails — privacy may only become stricter by segment, never weaker), `ledger.mastery_snapshot`
  (`V008`), `ledger.evidence` (`V007`), the MVP-0 Zero Trust Security Architecture,
  [`target-intelligence-loop.md`](../architecture/target-intelligence-loop.md) (the
  "Privacy-Governed Aggregation → Organizational Capability Digital Twin" stages).
- **Originates here**, on the same repository-native basis as M2-ADR-023 through M2-ADR-036.

## Problem

RAMALS is single-learner today (the professional beachhead). There is **no organization, tenant,
cohort, or hierarchy axis anywhere in the schema**, and no privacy-safe aggregation model.
Enterprise capability questions — e.g. *"we plan to migrate 40 applications to event-driven
architecture; do we have sufficient engineering capability?"* — cannot be answered, and **must not**
be answered by a naive average of individual mastery.

## Strategic intent

Aggregate governed individual competency evidence into organizational capability intelligence:
competency coverage, evidence sufficiency, confidence, recency, proficiency distribution,
critical-skill concentration, bus-factor / key-person risk, readiness, and training priorities —
scoped and access-controlled by organizational hierarchy.

## Architectural invariants (to be honoured by the full ADR)

- **Organizational capability is not `AVG(individual mastery)`.** The aggregation model must account
  for at least: competency coverage; evidence sufficiency; evidence confidence; evidence recency;
  proficiency distribution (not central tendency alone); critical-skill weighting; minimum-evidence
  thresholds; minimum-population / privacy (k-anonymity-style) thresholds; organizational hierarchy;
  RBAC; and tenant isolation.
- It is a **deterministic, versioned, reproducible engine** over `ledger.mastery_snapshot`,
  `ledger.evidence`, confidence, and recency — never an AI-authored capability verdict.
- **Individual learner privacy never weakens.** `core.learner` PII-minimization (M1-ADR-013) and the
  product-vision guardrail (a less restrictive scope never bypasses a stricter requirement) hold. No
  aggregate ever exposes an individual, or a group below the configured population threshold.
- Aggregates are computed, provenance-linked, versioned, and correlation-tagged.

## Dependencies

- **A multi-tenancy architecture decision is a mandatory predecessor** — the tenant model,
  isolation, identity, and RBAC on which any organizational scope depends.
- A privacy / legal review for organizational analytics.
- The learner-segment taxonomy in the product vision document.

## Explicit non-goals

- No multi-tenancy implementation under this stub.
- No `AVG(mastery)` rollup.
- No individual-identifying organizational report.
- No AI-authored capability verdict.
- No manager / mentor visibility model (that is a separate strategic-roadmap capability).
- No schema, migration, contract, or code — this stub authorizes none.

## Expected roadmap horizon

MVP-4 / MVP-5+ (enterprise / multi-tenant).

## Revisit / activation criteria

Activate — by authoring the full ADR and a separately reviewed design PR — when **all** hold:

1. A multi-tenancy ADR is accepted and implemented.
2. An enterprise product milestone with a completed privacy / legal review is scheduled.
3. A separate design review precedes any schema or code.

## Note on the ADR register

Adds `M2-ADR-037` to [`docs/adr/M2-ADR-register.md`](M2-ADR-register.md), clearly marked
**Deferred**, immediately after `M2-ADR-036`.
