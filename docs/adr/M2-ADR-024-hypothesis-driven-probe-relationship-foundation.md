# M2-ADR-024: Hypothesis-driven probe relationships are read-only, minimally stored, and explicitly non-authoritative (H4b foundation)

- **Status:** Proposed
- **Decides:** the storage, package placement, and terminology constraints binding the H4b
  ("hypothesis-driven related-probe selection") foundation — which of the four probe-relationship
  semantics get new storage, where the code lives, how a hypothesis stays distinct from a confirmed
  diagnosis, why this PR mints no `DIAGNOSTIC_SELECTION_V5`, and (§5, added on review of PR #251)
  that more than one candidate target objective is surfaced as an explicit outcome rather than
  resolved by an arbitrary tie-break.
- **Relates to, and extends, M2-ADR-023** (`docs/adr/M2-ADR-023-diagnostic-reasoning-is-evidence-not-a-gate.md`):
  that ADR's §2 already requires any future diagnostic/causal confidence construct (H5) to be
  versioned, deterministic, and never AI-decided; this ADR extends the same discipline one layer
  earlier, to the *relationships* H5 will eventually consume evidence through.
- **Does not relate to, and must not be confused with, M2-ADR-022.** That number remains separately
  reserved for the M1-ADR-010-vs-MVP-2 conflict over `RECORD_EVALUATION_EVIDENCE` writing
  `ledger.evidence` — a different, still-open decision. This ADR is `024`, immediately after `023`,
  for the same collision-avoidance reason `023` was numbered ahead of the still-unwritten `022`.
- **Originates here**, on the same repository-native basis as M2-ADR-023.

## Context

H1–H4a gave RAMALS: why a skill reads weak, in aggregate, from the full prerequisite graph and
mastery history (`GapDiagnosisService`); prerequisite-aware selection that caps rather than gates
(`DIAGNOSTIC_SELECTION_V3`); finer-grained objectives to diagnose against (`curriculum_version` v2);
and cross-attempt regression confirmation, re-testing the *same* skill when its own history
contradicts itself (`DIAGNOSTIC_SELECTION_V4`).

None of those four react to a *single unexpected miss* by investigating a *different, related*
concept. H4b is that capability: an incorrect answer raises a hypothesis about a related objective,
and RAMALS can explain — deterministically, auditably — what triggered the hypothesis, which related
objective it points at, why that objective is considered related, and whether the evidence gathered
afterward supported or contradicted it. This ADR fixes the constraints the H4b *foundation* PR must
satisfy, before any runtime selector consumes it.

Three decisions are worth writing down now, because each has an obvious, tempting, wrong version:

1. Creating one new relationship-storage row for all four requested semantics
   (`SAME_OBJECTIVE_CONFIRMATION`, `PREREQUISITE_VALIDATION`, `ROOT_CAUSE_PROBE`,
   `CONTRADICTION_CHECK`), when two of them already have an authoritative source in the schema and
   storing them again would let the two copies drift.
2. Placing this code in the existing `diagnosis` package (H1's home) because the word "diagnosis" is
   in both names, when H1's module boundary is deliberately `-> curriculum, mastery, learner;
   read-only, no writer dependency` and has never touched item/lineage/exposure data — and when H1's
   own `rootCauses`/`PREREQUISITE_GAP` already means something different (an aggregate, whole-graph
   mastery-status classification) from what H4b needs (a single item-level miss raising a hypothesis
   about one other objective).
3. Treating a hypothesis raised from one wrong answer as licence to mint `DIAGNOSTIC_SELECTION_V5`
   and wire it into `DiagnosticService` in the same change — before the relationship model has been
   reviewed on its own, and before deciding how a hypothesis is carried into a learner's next
   attempt (the same cross-attempt question H4a already had to answer for regression).

## Decision

### 1. Storage is minimal: only genuinely new relationships get a new table

Of the four requested semantics, two are already fully represented by existing, versioned,
authoritative tables and must be *read*, not re-stored:

- **`SAME_OBJECTIVE_CONFIRMATION`** is resolved from `core.assessment_item_objective` (V046): any
  other verified item tagged to the same objective as the trigger item.
- **`PREREQUISITE_VALIDATION`** is resolved from `core.skill_prerequisite` (V003): the trigger
  skill's curriculum prerequisite(s) and their required objective(s).

Storing either of these again in a new table would create a second, independently-editable copy of
a fact the curriculum graph already owns — exactly the kind of divergence risk H2's real
curriculum-version-vs-assessment-version bug (fixed in PR #248) came from.

The remaining two are genuinely new authored content, with no existing table:

- **`ROOT_CAUSE_PROBE`** and **`CONTRADICTION_CHECK`** are hand-authored, cross-objective links the
  curriculum graph does not already assert (e.g. "a miss flavored like idempotence is worth probing
  the dedicated idempotence objective"). These get one new table,
  `core.diagnostic_probe_relationship`, scoped by objective pair and relationship type, with its own
  `DRAFT`/`PUBLISHED` lifecycle and immutable-once-published trigger — the same authoring discipline
  `core.assessment_item_version`/`core.assessment_version` already hold content to. Per §5 (below),
  runtime only ever reads `PUBLISHED` rows.

All four remain first-class, explicit values on the *resolver's* output — callers never need to know
which are DB-backed and which are graph-derived.

### 2. Package placement: `io.ramals.learningplatform.assessment`, not `diagnosis`

H4b's domain classes live alongside `AdaptiveDiagnosticSelector`/`PrerequisiteAwareDiagnosticSelector`/
`HypothesisConfirmationDiagnosticSelector`, in `assessment`, for two independent reasons:

- **Architectural fit.** `assessment` already depends on `curriculum`, `mastery`, and its own
  item/lineage/exposure data; H4b needs exactly that and nothing more. `diagnosis` has no edge to
  `assessment` today (`ArchitectureGuardrailTests.majorModulesAreAcyclic`/
  `ALLOWED_DEPENDENCY_MATRIX`), and adding one would blur the read-only-mastery boundary M2-ADR-023
  gave `diagnosis` on purpose.
- **Terminology collision avoidance.** `GapDiagnosisClassifier` (H1) already has `rootCauses`,
  `findRootCauses`, and `GapClassification.PREREQUISITE_GAP` — an *aggregate*, whole-mastery-history
  classification, computed from `latestMasteryMap` across every skill in a curriculum graph. H4b's
  "root cause probe" is a *single unexpected miss*, at *objective* grain, investigated by selecting
  one more question. Same English words, different grain, different trigger, different author
  authority. Keeping them in different packages, with different class names
  (`ProbeCandidate`/`DiagnosticHypothesis`/`ProbeRelationshipResolver`, never `RootCause*`), keeps
  the two concepts from being read as the same thing by a future maintainer.

### 3. A hypothesis is never a diagnosis, and evidence is never a boolean forever

`DiagnosticHypothesis` records what triggered a hypothesis (item, objective, relationship type, and
— where DB-backed — the `diagnostic_probe_relationship` row that authorized it) and nothing more. No
field anywhere is named or shaped like `confirmedRootCause`; nothing in this layer writes
`ledger.mastery_snapshot`, `MasteryStatus`, or any frozen policy. That remains true even after H5
exists — H5 consumes evidence *from* this layer, this layer never consumes or asserts a diagnosis.

Evidence from a probe is classified through an explicit `HypothesisEvidenceOutcome`
(`SUPPORTING` / `CONTRADICTORY` / `INCONCLUSIVE`), not a raw `is_correct` boolean carried around as
if it were the permanent domain model. For today's two deterministically-scoreable item types
(`SINGLE_CHOICE`, `FILL_BLANK` — `AssessmentItemType.scoreable()`), the mapping is deterministic:
an incorrect probe supports the hypothesis, a correct one contradicts it. For anything not
deterministically scoreable (`SHORT_ANSWER`/`USE_CASE`, gated behind M2-ADR-022 and never reaching a
learner's form today per `AssessmentItemType`'s own javadoc), the outcome is `INCONCLUSIVE` — a
branch that exists for that reason and is unreachable today only because no such response can exist
yet, not because it was left out.

Probe *resolution* itself is a separate, four-valued, explicit outcome —
`NO_RELATIONSHIP_DEFINED` / `RELATIONSHIP_DEFINED_BUT_NO_ITEMS` / `ALL_CANDIDATES_ALREADY_EXPOSED` /
`CANDIDATES_AVAILABLE` — and must never collapse into `AssessmentBankExhaustedException`, which is a
different, `DiagnosticService`-owned concept (the whole selection pool being exhausted, not one
relationship's target objective having no — or no more — unseen content).

### 4. Scope: this ADR authorizes the foundation, not `DIAGNOSTIC_SELECTION_V5`

This PR adds `core.diagnostic_probe_relationship`, the resolver, and the hypothesis/evidence domain
model — all read-only, all callable but called by nothing at runtime. It does **not** touch
`DiagnosticService`, `DiagnosticSubmissionService`, mint a `DIAGNOSTIC_SELECTION_V5` policy string,
or add a `SelectionReason` value. Whether and how a resolved hypothesis is carried into a learner's
next attempt — most likely reusing `assessment_attempt_item.selection_reason` the way H4a did,
possibly not — is exactly the kind of runtime-wiring decision this ADR defers to a follow-up PR,
reviewed once this foundation has been reviewed on its own.

### 5. More than one candidate target objective is surfaced, never arbitrated

*(Added on review of PR #251 — the foundation as first opened this ADR still silently took `LIMIT 1`
in three places; this section closes that gap before merge.)*

The repository queries behind `ROOT_CAUSE_PROBE`/`CONTRADICTION_CHECK`, `PREREQUISITE_VALIDATION`,
and even resolving a trigger item's own objective can each legitimately return more than one row:

- The `core.diagnostic_probe_relationship` uniqueness constraint is
  `(source_objective_id, target_objective_id, relationship_type)`, not `(source_objective_id,
  relationship_type)` — the schema explicitly permits two published rows of the same type from the
  same source, to two different targets.
- A trigger skill may have more than one curriculum prerequisite, and a single prerequisite may have
  more than one required objective (H3's own finer-objective split makes this the common case, not
  the exception: every one of the five skills with real assessment content now has three required
  objectives, not one).
- A trigger item could in principle be tagged to more than one objective in
  `core.assessment_item_objective`, though no content this platform has ever authored does this.

Picking one of several candidates by a fixed tie-break — lowest `id`, first `display_order` — would
not be a deterministic *reading* of a diagnostic fact, the way every other decision this foundation
makes is. It would be an **uncredited diagnostic policy decision**: a claim that one candidate matters
more than another, made by whichever ordering a SQL query happens to return, never reviewed,
authored, or attributable to anyone. That is precisely the kind of silent authority this whole
roadmap (M1-ADR-010, M2-ADR-010, M2-ADR-023) has consistently refused to grant anywhere else.

So none of the repository's target-resolution queries `LIMIT 1` any more; each returns every
candidate it finds. `ProbeRelationshipResolver` decides, from the count alone, before ever consulting
items or exposure:

- **Zero candidates** → `NO_RELATIONSHIP_DEFINED` (unchanged from the original decision).
- **Exactly one candidate** → normal resolution, exactly as before (§1–§4 unaffected).
- **More than one candidate** → `ProbeResolutionOutcome.AMBIGUOUS_TARGET_OBJECTIVE`. No
  `DiagnosticHypothesis` is raised — there is no single target objective to name one about — but
  every candidate that made the choice ambiguous is carried on `ProbeResolution
  .ambiguousTargetObjectiveIds()`, so the ambiguity itself is auditable rather than swallowed.

A trigger item tagged to more than one objective is handled one step earlier, as
`TriggerItemHasAmbiguousObjectiveException`, since `DiagnosticHypothesis` has a single
`triggerObjectiveId` field and there is no candidate-list result type to report an ambiguous
*trigger* through the way there is for an ambiguous *target*.

**Ranking, combining, or otherwise choosing among several candidates remains explicitly out of scope
for this foundation.** Considering multiple target objectives together to produce one ranked or
combined probe is a real future capability — plausibly relevant to H4b's eventual runtime-selection
follow-up, or even to H5's confidence construct, which could legitimately want to weigh several
plausible root causes at once — but it is a genuine design question (how are candidates ranked? by
what evidence? deterministically how?) that this foundation does not answer and must not answer by
accident via query ordering.

## Alternatives rejected

- **One relationship table for all four semantics.** Simpler on the surface, and wrong: it would
  duplicate `skill_prerequisite` and `assessment_item_objective` as a second, independently-editable
  source of the same facts, with no mechanism keeping the two in sync.
- **Put H4b in the `diagnosis` package.** Rejected for the architectural-boundary and naming-collision
  reasons in §2; would also require loosening `diagnosis`'s read-only-mastery-only dependency edge
  for a capability that has nothing to do with why that boundary exists.
- **Store `is_correct` directly as the evidence model**, deferring an outcome enum until H5 needs one.
  Rejected: it would make "correct/incorrect" the accidental permanent shape of evidence, and every
  future rubric-scored type would have to either force a fake boolean or trigger a breaking change to
  every caller that already assumed one.
- **Ship `DIAGNOSTIC_SELECTION_V5` in the same PR**, since the resolver would otherwise sit unused.
  Rejected per the user's own explicit split: the relationship/hypothesis model deserves review as
  its own artifact before anything commits to a specific runtime selection shape around it — the same
  reasoning H4a's cross-attempt-vs-same-attempt fork went through before code was written.
- **Pick one candidate by a fixed tie-break (lowest `id`, first `display_order`) when more than one
  target objective exists**, since the repository already had to establish *some* order to page
  through results. Rejected (§5): a tie-break is a diagnostic-policy decision, not a deterministic
  reading of a fact, and this foundation has no authority to make it silently.
- **Implement ranked or combined multi-target probe selection now**, since the ambiguity case is
  real and a smarter answer than "report and stop" is imaginable. Rejected as premature: this is a
  genuine design question with its own trade-offs, explicitly deferred rather than decided by
  accident inside a bug fix.

## Consequences

- `core.diagnostic_probe_relationship` is the only new table. `SAME_OBJECTIVE_CONFIRMATION` and
  `PREREQUISITE_VALIDATION` resolution code must read `core.assessment_item_objective` and
  `core.skill_prerequisite` directly; a future PR adding a stored row for either is a defect against
  this ADR, not a valid alternative.
- New H4b classes belong in `io.ramals.learningplatform.assessment`; a future PR placing them in
  `diagnosis` (or adding a `diagnosis -> assessment` architecture edge to permit it) is a defect
  against this ADR.
- `HypothesisEvidenceOutcome` must stay a three-valued, extensible type wherever probe evidence is
  represented — never re-collapsed to `is_correct` at any layer this ADR governs.
- A follow-up PR proposing `DIAGNOSTIC_SELECTION_V5` must review this foundation's actual shape
  first; this ADR does not pre-authorize it.
- No repository method resolving a target objective may `LIMIT 1` or otherwise truncate its result to
  one row when more than one genuinely exists; a future PR reintroducing that is a defect against §5,
  not a valid simplification.
- `AMBIGUOUS_TARGET_OBJECTIVE` must stay a first-class, auditable outcome — carrying every candidate
  that caused it — never a silently-dropped or logged-only condition.

## Revisit triggers

- If `SHORT_ANSWER`/`USE_CASE` evaluation ships under M2-ADR-022 and produces a real non-boolean
  correctness signal, `HypothesisEvidenceOutcome.INCONCLUSIVE` becomes reachable; the classifier
  should be revisited then, not before.
- If a genuinely curriculum-graph-derivable relationship is later requested as a `ROOT_CAUSE_PROBE`
  or `CONTRADICTION_CHECK` (rather than hand-authored), that is grounds to reconsider whether it
  belongs in `core.diagnostic_probe_relationship` at all or should instead extend
  `skill_prerequisite`'s own semantics — a decision for whoever proposes it, not decided here.
- When `DIAGNOSTIC_SELECTION_V5` is actually scoped, its own design should supersede or extend §4
  rather than treat runtime wiring as already decided by this ADR.
- If a real, deterministic ranking rule for multiple candidate target objectives is ever proposed
  (e.g. driven by H5's confidence construct once it exists), that supersedes §5's "surface, don't
  arbitrate" default — but needs its own decision, reviewed on its own terms, the same way §5 itself
  was.

## Note on the ADR register

Adds `M2-ADR-024` to `docs/adr/M2-ADR-register.md` immediately after `M2-ADR-023`, following the
existing table's format; does not otherwise correct the pre-existing gap that table's own entry for
`M2-ADR-023` already documents.
