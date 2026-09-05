# M2-ADR-026: Granular Diagnostic Ontology Foundation (Concept/Sub-concept/Misconception)

- **Status:** Proposed
- **Decides:** whether and how RAMALS represents diagnostic structure finer than `LearningObjective`
  — a content-driven, optional `Concept`/`Sub-concept` refinement, and a separate `Misconception`
  entity — the boundary between this new ontology and existing curriculum/mastery semantics, the V1
  typed-reference strategy for diagnostic hypotheses at this grain, the evidence semantics a
  misconception mapping produces, and that this milestone builds foundation only, no runtime wiring.
- **Relates to, and extends without modifying, M2-ADR-023/024/025**
  (`docs/adr/M2-ADR-023-diagnostic-reasoning-is-evidence-not-a-gate.md`,
  `docs/adr/M2-ADR-024-hypothesis-driven-probe-relationship-foundation.md`,
  `docs/adr/M2-ADR-025-hypothesis-driven-probe-runtime-selection.md`): this ADR governs a *finer
  grain* than H4b/V5's objective-to-objective hypotheses. Nothing in `core.learning_objective`,
  `core.assessment_item_objective`, `core.diagnostic_probe_relationship`,
  `core.diagnostic_probe_provenance`, `core.diagnostic_confidence_observation`,
  `HypothesisEvidenceOutcome`, or `DiagnosticConfidenceCalculatorV1` changes as a result of this ADR.
- **Originates here**, on the same repository-native basis as M2-ADR-023/024/025.

## Context

H1–H5 diagnose at skill grain (H1) and objective grain (H3 onward): a `LearningObjective` is
currently the finest unit anything in this codebase reasons about, and H3's own finer-objective
split (V052) already established that even objective-grain decomposition must be content-driven,
never invented ahead of what real assessment items actually distinguish — its own comment: *"not
one-objective-per-item, which would be over-fragmentation rather than diagnosis."*

Real diagnostic work sometimes needs finer structure than an objective still provides, and a
different kind of structure than objective decomposition provides at all:

1. An objective can bundle more than one distinct testable idea — a **Concept**, and occasionally a
   **Sub-concept** one level under it — but only where real content actually distinguishes them,
   never as a mandatory decomposition every objective goes through.
2. Separately, a wrong answer is sometimes evidence of a *specific, nameable, incorrect belief* — a
   **Misconception** — which is not a finer testable idea at all, but an explanation for a pattern
   of wrongness about whatever node (objective, concept, or sub-concept) it targets.

Three shortcuts are worth naming now, because each has an obvious, tempting, wrong version:

1. Modeling Concept/Sub-concept as more `core.learning_objective` rows, to reuse H4b/H5's existing
   objective-keyed machinery for free. Rejected: a Concept is not an objective — it carries no
   mastery threshold, no coverage requirement, and folding it into `learning_objective` would
   either silently inflate `objectiveCoverage`'s denominator or require special-casing every reader
   of that table to ignore rows that aren't "really" objectives. Overloading one table with two
   domain meanings is the shortcut this ADR exists to refuse.
2. Fully duplicating the H4b/H5 relationship/provenance/confidence stack once per new grain (node,
   then again for misconception), to keep every new kind of hypothesis symmetric with the old one.
   Rejected as premature: nothing today has a concrete requirement for node-to-node or
   sub-concept-to-sub-concept causal relationships, and building that machinery ahead of a real need
   is exactly the kind of speculative structure V052 already warned against, one layer up.
3. Treating this as a natural extension of H5 that needs no new ADR, since H5's own review concluded
   a *calculator* extension needed none. Rejected: unlike H5 (fully governed by M2-ADR-023's
   existing text), this introduces a new ontology, a new entity, a new typed-reference mechanism,
   and a new evidence classifier — decisions none of M2-ADR-023/024/025 make.

## Decision

### 1. The diagnostic ontology: two independent axes, not one tree

```
LearningObjective (unchanged, existing, authoritative for coverage/mastery)
   └── Concept                     (optional, content-driven diagnostic refinement)
          └── Sub-concept          (optional, content-driven; exactly one level under a Concept)

Misconception                      (separate, orthogonal entity)
   └── targets exactly one: a LearningObjective, a Concept, or a Sub-concept
```

The **node axis** (`Concept`/`Sub-concept`) refines *what correct understanding is being tested*,
strictly under one `LearningObjective` each. The **misconception axis** is orthogonal: a named
wrong belief that *explains* a pattern of wrongness about whatever node it targets. A misconception
is never a node; a node never carries wrongness semantics.

### 2. Concept/Sub-concept are diagnostic refinement, never additional coverable units

`objectiveCoverage` and every mastery computation continue to read exactly what they read today
(`core.assessment_item_objective` tags against `core.learning_objective`, unchanged). A Concept or
Sub-concept row is never counted in any coverage denominator, never carries a `required` flag
analogous to an objective's, and never gates `MasteryStatus` in any way. This is the same discipline
M2-ADR-023 §1 already holds the prerequisite graph to — a diagnostic refinement is evidence *about*
a testable unit, never itself a new unit mastery is computed against.

### 3. Exactly two levels, content-driven, never mandatory

`LearningObjective → Concept → optional Sub-concept`. No deeper nesting. Most objectives will have
zero Concepts for the foreseeable future — this ontology exists to be populated where real content
distinguishes genuinely separate testable ideas within one objective, the same "grounded in what
the items actually test, not invented ahead of content" discipline V052 already established for the
objective split itself. A schema that supports depth it has no populated rows for yet is expected
and correct, not a defect.

### 4. Misconception: a separate first-class entity, DB-enforceable exclusive-arc target, DRAFT/PUBLISHED

A misconception targets **exactly one** of `LearningObjective`, `Concept`, or `Sub-concept` —
enforced by a database-level exclusive arc (nullable, individually-typed references plus a
constraint proving exactly one is populated), never a single polymorphic column whose meaning
depends on an out-of-band type flag. A misconception's own lifecycle is `DRAFT → PUBLISHED`,
immutable once published — the same authoring discipline `core.diagnostic_probe_relationship`
(V054) already established for hand-authored diagnostic content: freely editable
pre-publication, permanent once live.

### 5. Wrong-option mapping: SINGLE_CHOICE only, its own gated lifecycle

A mapping ties one specific wrong option, on one specific `SINGLE_CHOICE` item, to one
misconception. It must reference a real option on that item, must reference an option that is
genuinely incorrect (never the item's own correct answer), and may only become `PUBLISHED` once the
misconception it names is *itself* already `PUBLISHED` — a mapping can never make a claim more
settled than the belief it claims evidence for. Immutable once published, mirroring the
misconception's own discipline. `FILL_BLANK`, `SHORT_ANSWER`, and `USE_CASE` are out of scope for
V1: `FILL_BLANK`'s wrong-answer space is unbounded, normalized free text, not an enumerable set of
distractors a mapping can name; the other two are not deterministically scoreable at all.

### 6. A new, separate evidence classifier — `HypothesisEvidenceOutcome` is not touched

An item is **misconception-evidence-eligible** for misconception M when it is `SINGLE_CHOICE` and
carries at least one `PUBLISHED` wrong-option mapping naming M — a term deliberately distinct from
H4b's already-governed "probe" vocabulary (`core.diagnostic_probe_relationship`,
`ProbeRelationshipResolver`, `core.diagnostic_probe_provenance`). Misconception-evidence eligibility
asserts no H4b probe relationship and is never read by `ProbeRelationshipResolver`; it is a plain
fact about a mapping, not an H4b diagnostic probe.

Governed V1 truth table:

- The item is **not** misconception-evidence-eligible for M → the classifier is not invoked; no
  misconception evidence is produced (an eligibility failure, not a fourth classified value).
- Evidence-eligible, and the selected option is tagged specifically to M → `SUPPORTING`.
- Evidence-eligible, and the selected answer is correct → `CONTRADICTORY`.
- Evidence-eligible, and the selected option is a different, untagged, or differently-tagged wrong
  option → `INCONCLUSIVE`. Being wrong for an unrelated or unestablished reason neither confirms nor
  refutes M.

`HypothesisEvidenceOutcome.classify(itemType, isCorrect)` remains exactly as H4b left it — it
answers a different question (does correctness support or contradict a hypothesis about a *related
objective*) with different, coarser inputs. The new classifier takes the selected option's specific
tag as a genuine third input `HypothesisEvidenceOutcome` structurally cannot express, and is never
modified, overloaded, or reinterpreted to serve this purpose.

### 7. Typed reference strategy for V1: explicit, DB-enforceable, local

Granular diagnostic hypotheses use explicit, DB-enforceable typed references in V1 — individually-
typed, nullable reference columns plus a database constraint proving exactly one is populated
(§4's exclusive arc), local to the table that needs it, not a shared, generic identity table. This
is a deliberate, narrow decision for what exists today: **it is not a claim that this is the
permanently frozen representation every future diagnostic subject type must use.** If future
diagnostic subject types make exclusive arcs unwieldy, a generalized identity abstraction may be
reconsidered through a separate architectural decision, evaluated against the concrete shapes that
exist at that time — not designed speculatively now, and no generic `DiagnosticSubject` domain
entity is introduced merely to anticipate that possibility.

### 8. Foundation only — no runtime, no provenance, no confidence integration

This ADR authorizes: the `Concept`/`Sub-concept` hierarchy, the `Misconception` entity and its
exclusive-arc target, the wrong-option mapping, and the new evidence classifier — as pure data model
and pure classification logic. It does **not** authorize wiring any of this into `DiagnosticService`,
`DiagnosticSubmissionService`, `DIAGNOSTIC_SELECTION_V2–V5`, `core.diagnostic_probe_provenance`, or
`core.diagnostic_confidence_observation`; it does not authorize a new persisted provenance or
confidence-observation table for this grain, nor a `core.diagnostic_node_relationship` table — no
concrete runtime requirement for cross-node causal relationships exists yet, and none is anticipated
here. This mirrors H4b's own precedent exactly: the relationship/hypothesis/evidence model (#251)
was reviewed and merged as a complete, inert foundation before any runtime selector (#252) or
persisted confidence (#253) consumed it.

**Governance rule for what happens next:** a future runtime/provenance/confidence milestone requires
a separately reviewed design. An additional ADR is required only if that design introduces a new
architectural decision not already governed by this ADR or existing ADRs.

### 9. H4b/H5 are fully preserved, unmodified

`core.learning_objective`, `core.assessment_item_objective`, `core.diagnostic_probe_relationship`,
`core.diagnostic_probe_provenance`, `core.diagnostic_confidence_observation`,
`HypothesisEvidenceOutcome`, and `DiagnosticConfidenceCalculatorV1` (including its frozen thresholds
and freeze hash) are untouched by this ADR. A later granular-confidence stage, when and if
authorized under the governance rule in §8, may reuse the frozen calculator over separately-governed
evidence counts gathered from this milestone's new tables — the calculator's own math is never
revisited to accommodate this ontology.

## Alternatives rejected

- **Model Concept/Sub-concept as `learning_objective` rows** (self-referencing). Rejected (context,
  §2): overloads one table with two domain meanings and risks silently changing
  `objectiveCoverage`'s existing semantics.
- **A shared `DiagnosticSubject` identity table** for typed references. Rejected as the V1 mechanism
  (§7): requires every existing and future `learning_objective` insert to also mirror a row into a
  new shared table — a real, ongoing obligation on an existing write path, for a benefit (fewer
  columns per consuming table) not yet justified by how many diagnostic subject types actually
  exist.
- **`core.diagnostic_node_relationship` (node-to-node causal links)**, mirroring V054. Rejected for
  this milestone: no concrete runtime requirement exists yet for cross-node hypotheses; building it
  now would be exactly the speculative-structure pattern this ADR's own context section warns
  against elsewhere in the same document.
- **Reusing or extending `HypothesisEvidenceOutcome`** for misconception evidence. Rejected (§6): the
  two questions are genuinely different, and forcing one classifier to answer both would either lose
  the third (`INCONCLUSIVE`-for-a-different-reason) distinction or require threading option-level
  detail through a classifier H4b built and froze around a boolean.
- **Wiring this milestone into real selection or submission now**, since a foundation with no
  runtime consumer feels incomplete. Rejected (§8): H4b's own two-stage precedent (#251 foundation,
  #252 runtime) is exactly the discipline to repeat, not skip.

## Consequences

- `Concept`/`Sub-concept` rows must never be read by any mastery or `objectiveCoverage` computation;
  a future PR that does so is a defect against §2, not an enhancement.
- No more than two levels below `LearningObjective` (`Concept`, then `Sub-concept`) may ever be
  represented; a future PR adding a third level is a defect against §3.
- A misconception's exclusive-arc target and its `DRAFT`/`PUBLISHED` lifecycle (§4), and the
  wrong-option mapping's gated lifecycle (§5), must be enforced at the database boundary, not
  trusted from application code alone — the same discipline `core.diagnostic_probe_relationship`/
  `core.diagnostic_probe_provenance` already hold themselves to.
- `HypothesisEvidenceOutcome` must never be modified, overloaded, or reinterpreted to serve
  misconception evidence; a future PR doing so is a defect against §6.
- The exclusive-arc typed-reference pattern (§7) is the V1 approach, not a permanent architectural
  commitment; a future ADR may reconsider it once a concrete need demonstrates it no longer fits.
- A future PR introducing `core.diagnostic_node_relationship`, granular provenance, granular
  confidence persistence, or wiring this ontology into `DiagnosticService`/
  `DiagnosticSubmissionService`/selection requires a separately reviewed design per §8's governance
  rule — not pre-authorized by this ADR, and requiring its own new ADR only if it introduces a
  decision not already governed here or elsewhere.

## Revisit triggers

- If a genuine cross-node causal relationship is ever needed (a real runtime requirement, not
  anticipated demand), that supersedes §8's "no `diagnostic_node_relationship` yet" — addressed
  through the §8 governance rule: a separately reviewed design, with a new ADR only if it introduces
  a decision this one or another existing ADR doesn't already govern.
- If enough distinct diagnostic subject types accumulate that per-table exclusive arcs become
  unwieldy, a generalized identity abstraction may be proposed — its own ADR, evaluated against what
  exists then, not this one amended in place.
- When a runtime/provenance/confidence-integration milestone is actually scoped, its own design must
  state explicitly how it reuses (or diverges from) this foundation, following §8's governance rule.
- If `SHORT_ANSWER`/`USE_CASE` evaluation ever ships (M2-ADR-022) with a real non-boolean correctness
  signal, whether misconception mapping extends beyond `SINGLE_CHOICE` is worth revisiting then, not
  before.

## Note on the ADR register

Adds `M2-ADR-026` to `docs/adr/M2-ADR-register.md` immediately after `M2-ADR-025`.
