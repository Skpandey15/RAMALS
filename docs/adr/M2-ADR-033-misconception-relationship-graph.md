# M2-ADR-033: Misconception relationship graph and bounded queryable graph surface

- **Status:** Proposed
- **Date:** 2026-09-08
- **Decides:** whether, and how, RAMALS represents *relationships* (edges) among authored
  misconceptions and between a misconception and an existing prerequisite skill/objective, plus a
  bounded, read-only, authored-knowledge-only query surface that co-locates, per objective or
  concept, its prerequisites and its misconceptions. This is the "cross-node causal relationship"
  M2-ADR-026 §8 explicitly deferred, brought forward now against a concrete strategic direction
  (the misconception graph in [`target-intelligence-loop.md`](../architecture/target-intelligence-loop.md)).
- **Relates to, and extends without modifying, M2-ADR-026**
  (`docs/adr/M2-ADR-026-granular-diagnostic-ontology-foundation.md`): this ADR is the "separately
  reviewed design" M2-ADR-026 §8's governance rule requires, and it exists as its own ADR because it
  introduces a decision (relationship edges, an edge lifecycle, a graph read surface) that
  M2-ADR-026 does not make. Nothing in `core.diagnostic_node`, `core.misconception`,
  `core.assessment_item_option_misconception`, `core.misconception_evidence_observation`,
  `core.misconception_confidence_observation`, `MisconceptionEvidenceOutcome`,
  `MISCONCEPTION_EVIDENCE_V1`, `DIAGNOSTIC_CONFIDENCE_V1`, or `DiagnosticConfidenceCalculatorV1`
  changes as a result of this ADR.
- **Relates to, and does not modify, M2-ADR-023/024/025/027/028/029/030**: `HypothesisEvidenceOutcome`,
  `core.diagnostic_probe_relationship`, `core.diagnostic_probe_provenance`,
  `DIAGNOSTIC_SELECTION_V2`–`V5`, H6 (`M2-ADR-029`) and H7 (`M2-ADR-030`) semantics and their
  forbidden-terminology lists are untouched and, where relevant, extend to any rendering of the
  graph surface.
- **Originates here**, on the same repository-native basis as M2-ADR-023 through M2-ADR-032.
- **Migrations `V001`–`V059` are immutable.** Any table this ADR authorizes is a new migration
  (`V060` or later), strictly additive.

## Context

M2-ADR-026 gave RAMALS a first-class `Misconception` entity on an axis *orthogonal* to the
`LearningObjective → Concept → Sub-concept` node tree: a misconception targets exactly one node via a
DB-enforced exclusive arc, has its own `DRAFT`/`PUBLISHED` lifecycle, and is immutable once
published. M2-ADR-027/028 wired it into runtime as an immutable event-time evidence stream
(`MISCONCEPTION_EVIDENCE_V1`) and an append-only confidence stream (`DIAGNOSTIC_CONFIDENCE_V1`,
identity `(learner_id, misconception_id)`). M2-ADR-029/030 exposed those facts through read-only
report and longitudinal projections.

What none of that provides is **structure between misconceptions**, or between a misconception and
the prerequisite graph. M2-ADR-026 §8 deferred `core.diagnostic_node_relationship` deliberately:
*"no concrete runtime requirement for cross-node causal relationships exists yet."* Its revisit
trigger names the condition for reopening it: *"If a genuine cross-node causal relationship is ever
needed (a real runtime requirement, not anticipated demand), that supersedes §8's 'no
diagnostic_node_relationship yet' — addressed through the §8 governance rule: a separately reviewed
design, with a new ADR only if it introduces a decision this one or another existing ADR doesn't
already govern."*

The strategic direction supplies that requirement. RAMALS wants misconceptions to be an explicit,
queryable diagnostic knowledge structure — for a concept such as "Kafka Consumer Groups," its
prerequisites (`partitions`, `offsets`, `rebalancing`) **and** its known misconceptions ("more
consumers always increase throughput"; "multiple consumers in one group can consume the same
partition simultaneously"; "consumer lag means the broker is slow") available as one bounded
structure — and it wants misconceptions relatable to one another and to the prerequisite that most
often underlies them.

Four shortcuts are worth naming now, because each has an obvious, tempting, wrong version:

1. Modelling misconception edges as `core.skill_prerequisite` rows, or misconceptions as new
   `core.skill` / `core.learning_objective` rows, to reuse the curriculum graph's machinery.
   Rejected: it collapses the orthogonal axis M2-ADR-026 §1 built on purpose, and silently overloads
   the prerequisite graph's meaning.
2. Making `Misconception` a `core.diagnostic_node` (a Concept) so the existing node tree can host it.
   Rejected: M2-ADR-026 §1 is explicit — *"A misconception is never a node; a node never carries
   wrongness semantics."*
3. Adding a third level under `Sub-concept` to host a misconception hierarchy. Rejected: M2-ADR-026
   §3 forbids a third level, and enforces it with a guard trigger in `V057`.
4. Letting an edge carry, or a reader derive from an edge, a probability/confidence that a specific
   learner holds a misconception. Rejected: that is a second, parallel confidence model competing
   with G3 (`DIAGNOSTIC_CONFIDENCE_V1`, M2-ADR-028), and it invites an AI-authored number
   M2-ADR-023 §2 already refuses.

## Decision

### 1. Two authored, non-authoritative edge kinds

- **`MISCONCEPTION_RELATED`** — an edge between two published misconceptions, carrying a relationship
  type from a small closed set, initially: `CO_OCCURS_WITH`, `SPECIALISES` / `GENERALISES`,
  `CONTRASTS_WITH`. Types that are inherently directed (`SPECIALISES`/`GENERALISES` are the same edge
  read in two directions and are stored once, directed) keep their direction; types that are
  symmetric by meaning (`CO_OCCURS_WITH`, `CONTRASTS_WITH`) are stored once under an explicit,
  documented canonical ordering of the two misconception ids, never twice.
- **`MISCONCEPTION_PREREQUISITE_LINK`** — an edge from a published misconception to an existing
  curriculum prerequisite target (a `core.skill` / `core.learning_objective` already reachable
  through `core.skill_prerequisite`), meaning *"this misconception is commonly rooted in an
  unsecured prerequisite."* It is an authored diagnostic hint, never a computed causal claim and
  never read by any code as fact.

Both edge kinds are hand-authored content with a `DRAFT` → `PUBLISHED` lifecycle, immutable once
published — the same discipline `core.diagnostic_probe_relationship` (`V054`) and `core.misconception`
(`V057`) already hold hand-authored diagnostic content to. A published edge may reference only
published endpoints, forming one continuous publication chain with M2-ADR-026 §4's own chain
(`PUBLISHED Concept → PUBLISHED Sub-concept → PUBLISHED Misconception → PUBLISHED wrong-option
mapping → PUBLISHED edge`); no link may depend on a still-mutable one beneath it.

### 2. Misconception stays orthogonal — never a Skill, never a DiagnosticNode

Edges live in their own table(s) — `core.misconception_relationship` for `MISCONCEPTION_RELATED`,
and a `core.misconception_prerequisite_link` table (kept separate because its far endpoint is a
curriculum entity, not a misconception) — each keyed by a foreign key to `core.misconception(id)`.
No edge is ever represented by inserting a row into `core.skill`, `core.learning_objective`, or
`core.diagnostic_node`, and no edge type makes a misconception a parent or child of a `Concept` or
`Sub-concept`. The `LearningObjective → Concept → Sub-concept` tree is untouched: exactly two levels
(M2-ADR-026 §1/§3), and a misconception continues to *target* exactly one node through M2-ADR-026
§4's exclusive arc. This ADR adds edges *among misconceptions* and *from a misconception to a
prerequisite* — it adds no place in the node tree.

### 3. No third nesting level, restated

`MISCONCEPTION_RELATED` edges form a graph, not a tree, and impose no containment on
`core.diagnostic_node`. `SPECIALISES` / `GENERALISES` between two misconceptions is a relationship
between two first-class misconception entities, each still targeting its own single node; it is not a
new nesting level and creates no `Concept → Sub-concept → X` chain. `SPECIALISES` is acyclic,
enforced in PostgreSQL (the same discipline `core.skill_prerequisite` holds itself to since `V003`)
and re-checked in application code; symmetric types have no cycle concept. Self-edges are rejected.

### 4. Edges carry no confidence and no learner state

An edge is authored domain knowledge about misconceptions *in the abstract*. It never carries, and
no reader may derive from it, a probability or confidence that a specific learner holds a
misconception. `(learner_id, misconception_id)` confidence remains exactly G3
(`DIAGNOSTIC_CONFIDENCE_V1`, M2-ADR-028), computed only by the frozen `DiagnosticConfidenceCalculatorV1`
over `core.misconception_evidence_observation`. This ADR introduces no calculator, no band, no
snapshot table, and no confidence policy-version identifier of its own. `MisconceptionEvidenceOutcome`
/ `MISCONCEPTION_EVIDENCE_V1` (M2-ADR-027), the G3 aggregation identity and cross-assessment-version
semantics (M2-ADR-028), event-time capture, and the H6/H7 semantics and forbidden-terminology lists
(M2-ADR-029/030) are all unchanged and apply unchanged to any rendering of the graph surface.

### 5. A bounded, read-only, authored-knowledge-only query surface

If and when implemented, the graph surface is a deterministic, `@Transactional(readOnly = true)`
composition — the same shape as H6/H7. Given an objective or a concept it returns: that node's
prerequisites (from the existing `core.skill_prerequisite` / curriculum reads, unchanged); the
published misconceptions targeting that node via the exclusive arc; and the published
`MISCONCEPTION_RELATED` / `MISCONCEPTION_PREREQUISITE_LINK` edges among, and from, that set. It uses
a small fixed number of bounded queries (the "three bounded queries, not one per node" discipline of
[M0-T06](../database/m0-t06-curriculum-graph.md)), persists no view table, and any endpoint is
`Cache-Control: no-store`.

The V1 surface composes **authored structure only** — never learner evidence, never mastery, never
G2/G3. A learner-scoped overlay ("which of these misconceptions does learner L have evidence for,
and at what G3 band") is a genuinely different capability that composes G2/G3 and must be reviewed
against M2-ADR-028/029; it is explicitly deferred to its own later ADR and is not authorized here.

### 6. No adaptive selection, no runtime wiring

This ADR does not authorize wiring edges into `DiagnosticService`, `DiagnosticSubmissionService`,
`DIAGNOSTIC_SELECTION_V1`–`V5` (or a future `V6`), `ProbeRelationshipResolver`,
`core.diagnostic_probe_relationship`, `core.diagnostic_probe_provenance`, or
`core.misconception_evidence_observation`. Adaptive misconception-driven probing remains
unauthorized exactly as M2-ADR-027 §9 left it. If a future milestone wants edges to influence probe
selection, that belongs to M2-ADR-034's information-gain design or to its own separately reviewed
design — a new ADR only if it introduces a decision not already governed here or elsewhere (the
M2-ADR-026 §8 governance-rule pattern, applied here in turn).

### 7. Integrity is enforced at the database boundary

Typed foreign keys to `core.misconception(id)` (and, for `MISCONCEPTION_PREREQUISITE_LINK`, to the
existing curriculum target tables); `DRAFT`/`PUBLISHED` with an immutable-once-published trigger; the
published-endpoint-only rule enforced by a trigger lookup (the pattern `V055` §8a and `V057`'s
`core.protect_diagnostic_node` already established for facts no plain `CHECK` can express);
`SPECIALISES` acyclicity in PostgreSQL; self-edges rejected; `MISCONCEPTION_RELATED` uniqueness on
`(misconception_a_id, misconception_b_id, relationship_type)` under the canonical ordering of §1, so
the schema permits two different typed relationships between the same pair but never a duplicate.

## Alternatives rejected

- **Model misconception edges through `core.skill_prerequisite`, or misconceptions as `core.skill` /
  `core.learning_objective` rows.** Rejected (§2): collapses M2-ADR-026 §1's orthogonal axis and
  overloads the prerequisite graph's meaning; a reader of `skill_prerequisite` would then have to
  special-case rows that are not "really" prerequisites.
- **Make `Misconception` a `core.diagnostic_node` so the node tree hosts it.** Rejected (§2):
  M2-ADR-026 §1 — a misconception is never a node.
- **Add a third node level to host a misconception hierarchy.** Rejected (§3): M2-ADR-026 §3, already
  guard-triggered in `V057`.
- **Let an edge carry a per-edge "learner holds this" likelihood.** Rejected (§4): a parallel
  confidence model competing with G3, and an invitation to an AI-authored number M2-ADR-023 §2
  forbids.
- **Ship the query surface as a learner-scoped G2/G3 overlay now.** Rejected (§5): that composes
  learner evidence and belongs to its own review against M2-ADR-028/029.
- **Wire edges into `DIAGNOSTIC_SELECTION` in this milestone.** Rejected (§6): the M2-ADR-024
  (#251 inert foundation) → M2-ADR-025 (#252 runtime) two-stage discipline is exactly the pattern to
  repeat.
- **One generic `diagnostic_node_relationship` table for every future edge kind.** Rejected for V1,
  on the same reasoning M2-ADR-026 §7 gave for the exclusive arc: local, DB-enforceable typed tables
  for what exists today, not a speculative generic identity/relationship abstraction; revisit if
  enough distinct edge kinds accumulate.

## Consequences

- `core.misconception_relationship` and `core.misconception_prerequisite_link` (new migrations,
  `V060`+) are the only additions; a future PR storing these facts on `core.skill_prerequisite` or
  `core.diagnostic_node`, or as `core.skill` / `core.learning_objective` rows, is a defect against
  §2, not a valid alternative.
- No mastery, `objectiveCoverage`, G2, G3, H5, H6, H7, or `DIAGNOSTIC_SELECTION` code changes under
  this ADR; a PR that reads an edge into any of those under this ADR is a defect against §4/§6.
- The query surface is authored-knowledge composition only; a learner-scoped overlay requires its
  own ADR reviewed against M2-ADR-028/029.
- `V057`–`V059` remain immutable; every table here is an additive later migration.
- Adds a row to [`M2-ADR-register.md`](M2-ADR-register.md). `docs/adr/README.md`'s index does not
  enumerate individual M2 ADRs, and `AdrRegisterTests` matches only `M1-ADR-\d{3}` filenames, so
  neither requires a change.

## Revisit triggers

- If a concrete runtime requirement emerges for edges to influence probe selection, that is
  M2-ADR-034's territory or its own separately reviewed design — a new ADR only if it introduces a
  decision not already governed.
- If a learner-scoped graph overlay is wanted, it needs its own ADR, reviewed against M2-ADR-028
  (G3 identity/semantics) and M2-ADR-029 (report identities and zero-data states).
- If enough distinct edge kinds accumulate that per-kind typed tables become unwieldy, a generalized
  `diagnostic_node_relationship` abstraction may be reconsidered in its own ADR, evaluated against
  what exists then — the same stance M2-ADR-026 §7 takes toward the exclusive arc.

## Note on the ADR register

Adds `M2-ADR-033` to [`docs/adr/M2-ADR-register.md`](M2-ADR-register.md)'s "Decisions originating in
this repository" table, immediately after `M2-ADR-032`, in the same row format already used for
`M2-ADR-016` through `M2-ADR-032`.
