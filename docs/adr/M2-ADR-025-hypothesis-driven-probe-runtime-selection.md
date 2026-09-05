# M2-ADR-025: H4b runtime targeted-probe selection — `DIAGNOSTIC_SELECTION_V5`

- **Status:** Proposed
- **Decides:** the runtime constraints binding `DIAGNOSTIC_SELECTION_V5` — cross-attempt trigger
  eligibility, probe quota, precedence with V4, interaction with V3, the provenance model, and why
  ambiguity stays non-actionable at runtime, exactly as M2-ADR-024 governed the read-only foundation
  those decisions consume.
- **Relates to, and builds on, M2-ADR-024** (`docs/adr/M2-ADR-024-hypothesis-driven-probe-relationship-foundation.md`,
  PR #251): this ADR does not revisit any of §1–§5 there — `ProbeRelationshipService`/`Resolver`
  stay exactly as governed, read-only, called now by exactly one caller.
- **Does not relate to M2-ADR-022 or M2-ADR-023** beyond what M2-ADR-024 already established.
- **Originates here**, on the same repository-native basis as M2-ADR-023/024.

## Context

#251 gave RAMALS a deterministic, auditable way to resolve *what* a miss suggests investigating —
but nothing called it. This PR is the runtime consumer: a completed attempt's incorrect response
raises a hypothesis, #251 resolves a related probe candidate, and the learner's *next* diagnostic
attempt prioritizes it — while every existing frozen selector (V1–V4) keeps working exactly as it
already does underneath.

One architectural fact shapes everything below, and is worth stating before the decisions that
follow from it: **`AdaptiveDiagnosticSelector` (V2) operates at skill grain, not objective or item
grain.** `signalsBySkill` is keyed by skill code; `AdaptiveEligibleItem` carries no objective; and
the item actually chosen within a skill/band is resolved by `bestCandidate()` with a **per-call
random tiebreak** V5 has no hook into. V3's prerequisite cap and V4's regression reprioritization
both stay correct at this grain because prerequisite state and mastery status are themselves
recorded per skill. H4b's hypothesis is objective-grained, and its resolved candidate is a specific
item — a genuine mismatch with what V2 can express, resolved in §3 below without changing a line of
V2's own code.

## Decision

### 1. Composition order: V3 → V4 → V5 → frozen V2

`adjustForPrerequisites` (V3) → `adjustForRegressions` (V4) → `adjustForHypothesisProbe` (V5) →
`AdaptiveDiagnosticSelector.select()`, unmodified. V5 is a wrapper adjustment, exactly like V3 and
V4, never a replacement selection algorithm.

### 2. Trigger eligibility is exactly this rule, frozen, not "unexpectedness"

A response raises a probe hypothesis only when **all** of: it belongs to the single most recent
`COMPLETED` attempt for the **same `assessment_version_id`** as the attempt being created (§4); the
response is incorrect; and trying `ProbeRelationshipService.resolve()` for that item, in the fixed
type order **`ROOT_CAUSE_PROBE → CONTRADICTION_CHECK → PREREQUISITE_VALIDATION →
SAME_OBJECTIVE_CONFIRMATION`**, returns `CANDIDATES_AVAILABLE`. (`ROOT_CAUSE_PROBE` first: the
hand-authored, flagship semantic H4b's own narrative is built around; `SAME_OBJECTIVE_CONFIRMATION`
last: weakest specificity, "ask about the same thing again.") Misses within that one attempt are
tried in `presentation_order` — an existing, already-deterministic field, not a new score — and the
**first** miss that produces an actionable result under **any** type wins. No probability, no
mastery-derived "surprise" measure: this is deliberately named `HYPOTHESIS_DRIVEN_PROBE`, not
`UNEXPECTED_MISS`, because the system does not compute unexpectedness — it computes "incorrect, and
a published or graph-derived relationship exists."

Every exception `ProbeRelationshipService.resolve()` can raise
(`TriggerItemHasNoObjectiveException`, `TriggerItemHasAmbiguousObjectiveException`) means "this miss
is not eligible," never "attempt creation fails." V5 degrades to no adjustment, never breaks a
learner's ability to start a new attempt.

### 3. One chosen item, expressed as a pool restriction — never a V2 code change

`HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe` does two things to V2's two
existing inputs, and nothing else: sets the target skill's signal to `priority = 0`,
`reason = HYPOTHESIS_DRIVEN_PROBE`, **leaving `targetDifficulty` exactly as V3/V4 already decided
it**; and removes every *other* item of that one skill from the pool, leaving only the single H4b
candidate chosen (first, deterministically, from `ProbeResolution.candidates()`'s own already-sorted
list — no new tie-break invented). V2's unmodified `bestCandidate()` then either picks that one item
(if its band is at or below the untouched `targetDifficulty`) or finds nothing for that skill this
round (if not) — exactly the same "never present a band above what evidence earned" rule V2 already
enforces for every other skill. This is how §6 (V3's cap is never silently undone) and §8 (quota is
exactly one) are both satisfied with zero special-casing: they are consequences of restricting V2's
*input*, not new logic reading V3's decision or counting probes served.

### 4. Source attempt: only the immediately preceding `COMPLETED` attempt, same version

Scoped to the same `assessment_version_id` as the attempt being created — the simplest reading of
"same assessment/curriculum context" that cannot itself produce a version mismatch between the
trigger item and the candidate pool. No arbitrary history scan; no attempt is preferred over another
by convenience. This is also what enforces §9 (no stale hypothesis): the moment attempt N+1 exists
and completes, it — not attempt N — is "the immediately preceding attempt" for N+2, so N's original
miss is never reconsidered. H7's longitudinal validation is not built here; this is a one-hop rule,
not a history model.

### 5. V4 vs. V5 precedence: explicit, and a direct consequence of §1's order

Both V4 and V5 replace a skill's whole `SkillMasterySignal` entry when they touch it. Running V5
*after* V4 (§1) means V5's `reason`/`priority` win over V4's on the same skill — not by accident of
whichever adjustment happens to run last, but because that composition order is the frozen rule,
tested explicitly. Rationale for choosing this direction rather than the reverse: H4b's probe is a
specific, evidence-seeking action authorized by a resolved relationship (or curriculum edge);
H4a's regression confirmation is a generic "something about this skill's own history looks off."
The more specific action wins.

### 6. Probe quota: `MAX_HYPOTHESIS_PROBES_PER_PACKET = 1`

Frozen, tested, and — per §3 — enforced structurally: the restricted pool contains exactly one item
for the target skill, so V2's own round-robin cannot serve a second one from it even across
multiple quota-filling rounds. Conservative on purpose: broad skill coverage remains the default;
one targeted probe per attempt is the whole of this PR's ambition.

### 7. Ambiguity stays non-actionable at runtime, exactly as #251 left it

`AMBIGUOUS_TARGET_OBJECTIVE` is treated the same as "not eligible" — V5 moves to the next
relationship type, then the next miss. No runtime arbitration is introduced; this ADR does not
relax M2-ADR-024 §5. `RELATIONSHIP_DEFINED_BUT_NO_ITEMS` and `ALL_CANDIDATES_ALREADY_EXPOSED` are
handled identically — no fallback to unrelated content, no conversion into
`AssessmentBankExhaustedException`, which remains `DiagnosticService`'s own, different, whole-pool
concept.

### 8. Provenance: one new table, additive, immutable once written

`core.diagnostic_probe_provenance` — `attempt_id`/`item_version_id` (FK to the *new* attempt's
`assessment_attempt_item` row via its existing `(attempt_id, item_version_id)` unique key, so no
surrogate id needs to be threaded back from insertion), `source_attempt_id`,
`source_item_version_id`, `source_objective_id`, `relationship_type` (all four values — a
**different** vocabulary from `core.diagnostic_probe_relationship`'s own two-value CHECK, which
only ever stores the two hand-authored types), `target_objective_id`, `authorizing_relationship_id`
(nullable, populated only for the two DB-backed types), `created_at`. Guarded the same way
`core.assessment_attempt_item` already is: immutable once written, insertable only while the owning
attempt is `IN_PROGRESS`. Nothing here claims a diagnosis — it records the reason a probe was
selected, and nothing more.

### 9. One new `SelectionReason`, not four

`HYPOTHESIS_DRIVEN_PROBE` (Option A over four granular reasons): the provenance table already
carries `relationship_type` as a structured column, so a four-way reason split would duplicate that
fact in the enum for no additional auditability. `ck_assessment_attempt_item_reason` widens by this
one value, a pure superset, the same pattern V050/V051/V053 already established.

### 10. No new authority anywhere in this PR

V5 does not recompute mastery, does not calculate H5 confidence, does not assert a confirmed root
cause, does not touch H1's `GapDiagnosisService`, does not add LangGraph, does not introduce
same-attempt dynamic questioning, and does not ask an LLM to choose a probe. Every one of trigger
eligibility, relationship lookup, ambiguity handling, candidate selection, priority, tie-breaks,
quota, and provenance is deterministic and reproducible from already-authoritative inputs.

## Alternatives rejected

- **Give `AdaptiveDiagnosticSelector` an objective-aware signal map** so V5 could express a
  preference below skill grain natively. Rejected: V2 is frozen; widening its own input contract to
  make one caller's job easier is exactly the shortcut M2-ADR-023/024 already refused elsewhere.
- **Bias the per-item tiebreak** V2 already computes internally. Rejected: V5 has and should have no
  hook into V2's internals; a caller reaching into a frozen engine's private tiebreak map is a far
  worse coupling than restricting the caller-owned pool it already assembles.
- **Run V5 before V4** in the composition order. Rejected (§5): the reverse would make regression
  confirmation override a more specific, actively-resolved hypothesis on the same skill, backwards
  from the stated preference for the more specific action.
- **Four granular `SelectionReason` values** mirroring `ProbeRelationshipType`. Rejected (§9): the
  provenance table already carries the type; duplicating it in the reason enum buys nothing.
- **Encode provenance as JSON on `selection_reason` or in logs only.** Rejected per explicit
  instruction and prior project discipline (H1–H4b): a string reason field is not a database, and
  logs are not an audit trail a query can join against.
- **Scan a learner's full attempt history for the "most convenient" miss.** Rejected (§4): would
  make probe selection depend on iteration order or an invented importance score, exactly what this
  ADR exists to rule out.

## Consequences

- `HypothesisDrivenProbeDiagnosticSelector` must remain a pure function of already-resolved inputs
  (signals, pool, one chosen selection) — no database access, the same discipline
  `PrerequisiteAwareDiagnosticSelector`/`HypothesisConfirmationDiagnosticSelector` are already held
  to.
- `DiagnosticService` owns every DB read behind V5 (source attempt, ordered misses, calls into
  `ProbeRelationshipService`) — a future PR moving that orchestration elsewhere without preserving
  this ADR's eligibility/precedence/quota rules is a defect against it.
- The composition order in §1 is frozen; a future change reordering V3/V4/V5 needs its own ADR, not
  a quiet refactor.
- `core.diagnostic_probe_provenance` is the only new table; a future PR persisting provenance as
  JSON on `assessment_attempt_item` or in logs only is a defect against §8.

## Revisit triggers

- If H5's confidence construct is built and wants to weigh several candidate probes rather than the
  first eligible one, that supersedes §2/§6's "first eligible, quota one" default — a new decision,
  not an extension smuggled into this ADR.
- If a genuine multi-hop hypothesis chain (H7-adjacent) is ever wanted, §4's one-hop horizon is what
  must be revisited first, deliberately, not reused implicitly.
- If V2 is ever reworked to be objective-aware, §3's pool-restriction mechanism should be
  reconsidered — it exists specifically to work around V2 staying frozen and skill-grained.

## Note on the ADR register

Adds `M2-ADR-025` to `docs/adr/M2-ADR-register.md` immediately after `M2-ADR-024`.
