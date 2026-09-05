# M2-ADR-027: Granular Diagnostic Runtime — Immutable Event-Time Misconception Evidence Capture

- **Status:** Proposed
- **Decides:** how the granular diagnostic ontology foundation (M2-ADR-026) is wired into real
  learner-response processing — passive misconception evidence capture only, no adaptive selection.
  Governs: immutable event-time capture; no silent retroactive reinterpretation; evaluating every
  event-time-eligible misconception in the absence of adaptive targeting; that one response may
  yield multiple observations; the exact `assessment_response` provenance anchor; complete
  event-time mapping provenance; the mandatory `MISCONCEPTION_EVIDENCE_V1` policy identifier; atomic
  capture with submission; and strict separation from mastery, H4b/H5, granular confidence, and
  adaptive selection.
- **Relates to, and extends without modifying, M2-ADR-026**
  (`docs/adr/M2-ADR-026-granular-diagnostic-ontology-foundation.md`): that ADR built the ontology and
  a pure evidence classifier as foundation, explicitly deferring "runtime/provenance/confidence" to a
  separately reviewed design, requiring a new ADR only if that design introduces a decision not
  already governed. This ADR is that separately reviewed design, and the new decisions below are
  exactly what triggers its own existence under that rule.
- **Relates to, and extends without modifying, M2-ADR-023/024/025**: `HypothesisEvidenceOutcome`,
  `core.diagnostic_probe_relationship`, `core.diagnostic_probe_provenance`,
  `core.diagnostic_confidence_observation`, `DiagnosticConfidenceCalculatorV1`, and
  `DIAGNOSTIC_SELECTION_V2–V5` are untouched by this ADR — granular misconception evidence is a
  separate, parallel evidence stream, not a replacement or extension of H4b/H5's own.
- **Originates here**, on the same repository-native basis as M2-ADR-023/024/025/026.

## Context

M2-ADR-026 gave RAMALS a content-driven `Concept`/`Sub-concept` refinement of `LearningObjective`
and a separate `Misconception` entity, evidenced through a wrong-option mapping and a pure
`MisconceptionEvidenceOutcome` classifier — but nothing called it. That foundation deliberately left
open exactly how a real, scored learner response becomes a persisted, learner-specific evidence
record, and explicitly deferred the question to a "separately reviewed design."

Wiring this into the real `DiagnosticSubmissionService` submission path surfaces several genuine
architectural questions the foundation ADR never answered, because it assumed a single, externally
given "misconception under test" and never addressed persistence, timing, or provenance at all:

1. **When is evidence captured, and can it change later?** Mappings are authored and published over
   time, independently of when a learner answers. If evidence were recomputed from whatever mappings
   happen to be `PUBLISHED` at query time, a mapping published tomorrow would silently reinterpret a
   response already scored today — undermining the reproducibility this whole project has
   consistently protected (frozen calculators, freeze-hash tests, H5's own event-time precedent).
2. **Which misconception is "under test" when nothing selects one?** This milestone adds no
   adaptive targeting. The only identity that exists is eligibility itself, defined per
   `(item, misconception)` pair — so a response must be evaluated against every misconception the
   item is independently eligible for, not one arbitrarily chosen one.
3. **What, exactly, does a persisted observation prove, and how?** An immutable evidence record is
   only as trustworthy as what it can prove about itself. A naive design would check "does a
   published mapping exist" at write time — insufficient, since a mapping published after a response
   already exists could otherwise be laundered into looking like it explains a much older response.

## Decision

### 1. Immutable event-time capture, computed once, never revisited

Misconception evidence is captured exactly once, synchronously, at the moment a `SINGLE_CHOICE`
response is scored — never recomputed later, never revisited. An observation reflects only the
`assessment_item_option_misconception` rows that were already `PUBLISHED`, as of the exact instant
the response it explains was recorded (`core.assessment_response.created_at` — the most precise,
already-immutable anchor available; verified to be populated purely by that column's own default,
never supplied by the application).

### 2. No silent retroactive reinterpretation

A mapping published after a response was already scored never becomes evidence for that response.
This is not merely an application-level convention: the database itself proves, for every piece of
provenance an observation cites, that the mapping predates the response (§6) — a guarantee, not an
assumption resting on well-behaved callers.

### 3. Evaluate every event-time-eligible misconception, in the absence of adaptive targeting

Since no mechanism yet exists for narrowing to one "targeted" misconception, a scored response is
evaluated against **every** misconception for which the item was event-time-eligible — determined
independently, per misconception, from the same authoritative facts (§6's eligibility set). This is
not an arbitrary default; it is the only reading consistent with eligibility already being a
per-misconception fact rather than a per-item one. A correct answer therefore produces
`CONTRADICTORY` evidence for every such misconception, not a single one; a wrong answer produces
`SUPPORTING` for whichever misconception(s) the selected option was itself tagged to (as of event
time) and `INCONCLUSIVE` for every other misconception the item was independently eligible for.

### 4. One response may yield multiple observations

A single scored response can legitimately produce more than one persisted observation — one per
event-time-eligible misconception. This includes the case where one wrong option is validly tagged
to more than one misconception (a deliberate authoring choice M2-ADR-026 already permits, not
constrained here): a single selection can be `SUPPORTING` evidence for two distinct beliefs at once.

### 5. Exact provenance anchor: `core.assessment_response`, not the attempt/item pair

Each observation's provenance is a direct foreign key to the exact, immutable
`core.assessment_response` row it was computed from — not a reconstructed `(attempt_id,
item_version_id)` pair standing in for it. The evidence statement is precisely *"this response
produced this evidence about misconception M,"* anchored to the one row that already is that
response's own unique identity.

### 6. Complete event-time mapping provenance, and its own independent event-time proof

An observation's provenance names **every** `assessment_item_option_misconception` row that
contributed to its event-time eligibility for that misconception — not merely the one row matching
the selected option. For an item where options A and C are both published-tagged to the same
misconception before the response, an observation for that misconception (`SUPPORTING`,
`CONTRADICTORY`, or `INCONCLUSIVE` alike) cites both mapping rows, giving an exact, immutable
snapshot of every fact that made the item eligible at event time — not a partial record limited to
whichever fact happened to be causally decisive for the classification.

Each such provenance reference independently carries its own proof that the mapping it cites
predates the response — the database does not trust that a caller assembled correct provenance; it
verifies each citation on its own terms.

**The observation's own claimed outcome is independently re-derivable from authoritative facts
alone — never from its own provenance children.** Because an observation and its provenance rows
cannot be written in the same physical instant (the parent row must exist before children can
reference it), a design that validated the observation's outcome by consulting its own children
would validate nothing at the moment the parent itself is written — the very moment integrity most
needs proving. The eligibility set and expected outcome are therefore computed independently, directly
from `assessment_item_option_misconception` and `assessment_response`, every time an observation is
written, regardless of what provenance is inserted alongside it.

**Accepted V1 limitation**: the database cannot cheaply guarantee that at least one provenance row
exists for every observation (a cross-table existence constraint would need deferred trigger
machinery disproportionate to what this fact currently warrants). §1's independent outcome
derivation is what keeps this limitation from being a correctness gap: an observation's own claimed
outcome can never be wrong regardless of whether its provenance children are complete, so an
implementation defect in provenance-writing can produce an under-documented row, never a
semantically false one. Completeness of provenance is upheld by the capture service always writing
an observation and its full provenance set atomically, verified by tests, not by a schema-level
guarantee.

### 7. `MISCONCEPTION_EVIDENCE_V1`: one mandatory, database-constrained policy identifier

Every persisted observation carries `policy_version = 'MISCONCEPTION_EVIDENCE_V1'`, enforced at the
database boundary. This one identifier governs the whole capture policy as a single, versioned unit —
eligibility semantics, the evaluate-every-eligible-misconception rule, the
`SUPPORTING`/`CONTRADICTORY`/`INCONCLUSIVE` truth table, event-time capture, and no-retroactive-
reinterpretation — not merely the pure classifier's own two-input arithmetic. The classifier itself
(`MisconceptionEvidenceOutcome.classify`) is deliberately not given an `EngineVersionFreezeTests`
frozen vector: it has no tunable threshold or weight the way `DiagnosticConfidenceCalculatorV1` does,
the same reasoning `HypothesisEvidenceOutcome` (H4b) was never frozen either.

### 8. Atomic capture with submission

Evidence capture runs inside `DiagnosticSubmissionService`'s existing single `@Transactional`
`submit` method, as a sibling to H5's own per-response confidence write, immediately after the
response itself is persisted. A failure during capture rolls back the entire submission — the
response, H5's confidence observation, ledger evidence, and mastery recompute together — the
identical guarantee H5's own Option C (M2-ADR-023 §2) already established for exactly this shape of
problem. No new transactional behavior is introduced.

### 9. Strict separation — no new authority anywhere in this ADR

This capability never reads or writes `WeightedMasteryCalculator`, `EvidenceConfidenceCalculatorV2`,
`MasteryStatusPolicyV2`, `objectiveCoverage`, or progression eligibility. It never modifies
`HypothesisEvidenceOutcome`, `core.diagnostic_probe_relationship`, `core.diagnostic_probe_provenance`,
`core.diagnostic_confidence_observation`, or `DiagnosticConfidenceCalculatorV1` (math, thresholds, or
freeze hash). It does not implement granular confidence aggregation (a later, separately authorized
stage may reuse the frozen `DiagnosticConfidenceCalculatorV1` over counts drawn from this evidence,
unmodified — this ADR only makes that future aggregation possible, it does not build it). It does
not add adaptive misconception-driven item selection, a `core.diagnostic_node_relationship` table,
new probe relationship types, or any change to `DIAGNOSTIC_SELECTION_V2–V5`. No MCP, Spring AI,
LangGraph, or LLM integration.

## Alternatives rejected

- **Recompute evidence on read, from whatever mappings are `PUBLISHED` at query time.** Rejected
  (context, §1/§2): makes historical evidence silently reinterpretable, breaking reproducibility for
  the identical reasons H5 already rejected the same option for confidence persistence.
- **Validate an observation's outcome against its own provenance children.** Rejected (§6): the
  children cannot exist yet at the moment the parent is written, so this would validate nothing at
  exactly the moment integrity matters most. The database instead re-derives the expected outcome
  independently, every time.
- **Persist only the mapping matching the selected option, when one exists.** Rejected (§6): would
  silently omit every other mapping that also established eligibility for the same misconception
  (e.g., a second option also tagged to it), producing an incomplete audit trail for a construct this
  project holds to a "prove it, don't merely assert it" standard everywhere else.
- **A single, arbitrarily-chosen "targeted" misconception per response.** Rejected (§3): no targeting
  mechanism exists in this milestone; inventing an implicit one (e.g., "the first eligible
  misconception") would be an uncredited policy decision with no basis in the ontology.
- **Deferred constraint machinery to guarantee provenance completeness at the schema level.**
  Rejected (§6, accepted limitation): disproportionate machinery for a gap that independent outcome
  derivation already prevents from becoming a correctness problem.
- **Freezing `MisconceptionEvidenceOutcome` via `EngineVersionFreezeTests`.** Rejected (§7): it has no
  tunable arithmetic to protect against drift, the same reasoning that already left
  `HypothesisEvidenceOutcome` unfrozen.

## Consequences

- Any future change to eligibility semantics, the evaluate-every-eligible rule, the
  `SUPPORTING`/`CONTRADICTORY`/`INCONCLUSIVE` truth table, event-time capture, or the no-retroactive-
  reinterpretation guarantee requires a new, differently-named policy identifier
  (`MISCONCEPTION_EVIDENCE_V2` or similar) — never an in-place edit to what
  `MISCONCEPTION_EVIDENCE_V1` already means for rows already written under it.
- `core.misconception_evidence_observation`/`core.misconception_evidence_observation_mapping` are
  the only new tables; a future PR persisting this evidence differently, or bypassing the
  independent-derivation guard, is a defect against §6, not a simplification.
- A future runtime consumer of this evidence (granular confidence aggregation, adaptive
  misconception-driven selection, or anything wiring this into `DiagnosticService`'s selection path)
  requires its own separately reviewed design; an additional ADR is required only if that design
  introduces a decision not already governed by this ADR or an existing one — the identical
  governance rule M2-ADR-026 §8 already established, applied here in turn.

## Revisit triggers

- If granular confidence aggregation is scoped, its own design must state explicitly how it draws
  `SUPPORTING`/`CONTRADICTORY`/`INCONCLUSIVE` counts from this evidence and feeds
  `DiagnosticConfidenceCalculatorV1` unmodified — this ADR makes that possible without deciding it.
- If a future need arises to retroactively generate evidence for historical responses against
  newly-published mappings, that is a genuine, separate backfill capability requiring its own
  decision — never an implicit reinterpretation of this ADR's event-time guarantee.
- If `MisconceptionEvidenceOutcome`'s own logic ever grows tunable thresholds or weights, revisit
  whether it should be frozen via `EngineVersionFreezeTests` at that point, not before.

## Note on the ADR register

Adds `M2-ADR-027` to `docs/adr/M2-ADR-register.md` immediately after `M2-ADR-026`.
