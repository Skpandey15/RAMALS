# M2-ADR-026: Diagnostic confidence is a staged, count-based construct over probe-relationship evidence — `DIAGNOSTIC_CONFIDENCE_V1`

- **Status:** Proposed
- **Decides:** what "diagnostic confidence" means, what receives it, what evidence feeds it, how
  contradiction and insufficiency are handled, how it is persisted, and how it stays independently
  versioned and frozen (H5).
- **Relates to, and satisfies, M2-ADR-023 §2**
  (`docs/adr/M2-ADR-023-diagnostic-reasoning-is-evidence-not-a-gate.md`): that ADR already required
  H5 to be a named, versioned calculator with a frozen behaviour vector, deterministic from
  already-authoritative inputs, and never fed back into mastery computation. This ADR is that
  calculator.
- **Relates to, and consumes without modifying, M2-ADR-024/025**
  (`docs/adr/M2-ADR-024-hypothesis-driven-probe-relationship-foundation.md`,
  `docs/adr/M2-ADR-025-hypothesis-driven-probe-runtime-selection.md`): H5 reads
  `HypothesisEvidenceOutcome`/`core.diagnostic_probe_provenance` exactly as H4b/V5 left them. No
  selector, no relationship semantics, no provenance field is touched.
- **Originates here**, on the same repository-native basis as M2-ADR-023/024/025.

## Context

H4b's foundation (#251) built a complete evidence model —
`HypothesisEvidenceOutcome.classify` turns a probe's scored response into `SUPPORTING`/
`CONTRADICTORY`/`INCONCLUSIVE` — but nothing ever called it: `ProbeRelationshipService.evidenceFor`
existed, tested, unused. V5's runtime (#252) added `core.diagnostic_probe_provenance`, recording
*why* a probe entered a packet, but nothing recorded what a probe's *outcome* showed once answered.

H5 is the first consumer of both: given the evidence gathered so far for one hypothesis (which
objective's weakness is suspected to explain another's), how strongly does that evidence, taken
together, support the hypothesis? M2-ADR-023 §2 already fixed the constraints this construct must
satisfy before it was built: versioned, deterministic, frozen, never fed back into mastery. This ADR
is the concrete design.

Two temptations are worth naming, because each has an obvious, tempting, wrong version:

1. **Reusing `evidenceConfidence`'s weighted-blend style** (`EvidenceConfidenceCalculatorV2`:
   `0.40*volume + 0.35*coverage + ...`) for diagnostic confidence too, since a formula already
   exists and blends nicely into a single number. Wrong for a different reason than M2-ADR-023
   already gave for reusing the number itself: H5's evidence is small and discrete (bounded to at
   most one new observation per completed attempt, `MAX_HYPOTHESIS_PROBES_PER_PACKET = 1`), not the
   continuous, dozens-of-items regime a weighted blend of ratios is defensible over. Inventing
   decimal weights over counts of 0/1/2/3 would be false precision — a `0.6234` no one could defend
   against `0.61`.
2. **Treating net margin (`supportingCount - contradictoryCount`) as sufficient on its own.** It is
   not: `(4 supporting, 1 contradictory)` and `(100 supporting, 97 contradictory)` share an identical
   net margin of 3, but the first is strongly one-sided (80% share) and the second is nearly
   balanced directional evidence under this deterministic evidence-count model (just over half).
   Confidence must reason about *both* volume and proportional dominance, not one alone.

## Decision

### 1. Unit of confidence: the hypothesis tuple, not a skill, objective, or single event

Confidence attaches to `(learnerId, sourceObjectiveId, targetObjectiveId, relationshipType)` — the
same grouping key every `core.diagnostic_probe_provenance` row already carries. This is
repository-native (no new identifier is minted; confidence is computed *over* existing provenance
rows, grouped by this tuple) and matches H4b's own boundary: a `DiagnosticHypothesis` is a single
triggering event (a record, not an entity, no stored identity); the tuple is the standing claim that
accumulates evidence across many such events over time. Confidence is never attached to a skill, an
objective alone, or a misconception — one precise unit, one precise meaning, per M2-ADR-024 §2's own
terminology-collision discipline.

### 2. Evidence inputs: distinct evidence observations, classified exactly as H4b already does

For one hypothesis tuple, for one learner, scoped to one `assessment_version_id` (the same scoping
boundary V5's own source-attempt lookup already uses, M2-ADR-025 §4 — `learning_objective.id` is
itself curriculum-version-scoped, so this is also naturally where evidence would stop lining up
anyway): every `core.diagnostic_probe_provenance` row matching the tuple, and for each, the response
to `(attempt_id, item_version_id)` classified by the exact, unmodified
`HypothesisEvidenceOutcome.classify` every earlier H4b evidence read already uses. No normalization
step exists or is needed: `classify`'s own signature takes no `relationshipType` parameter at all —
it is structurally incapable of varying by which of the four `ProbeRelationshipType` values raised
the hypothesis, confirmed explicitly by `ProbeRelationshipType.CONTRADICTION_CHECK`'s own javadoc
("evidence classification ... is computed uniformly from correctness regardless of which type
triggered the probe") and by `ProbeRelationshipResolverTests`, whose classification tests never pass
a relationship type.

**Distinct, not independent.** Every provenance row is guaranteed to name a logical item never
presented to this learner before (no-repeat exposure) and a distinct `(attempt_id, item_version_id)`
pair (the table's own `UNIQUE` constraint) — so one row is one distinct evidence observation. This is
not a claim of statistical or diagnostic independence; the schema proves distinctness, nothing more,
and this ADR's vocabulary is deliberately "distinct evidence observation," never "independent
evidence."

**Excluded, with reasons already established**: recency/time-decay (no governed diagnostic-domain
recency policy exists; `EvidenceConfidenceCalculatorV2`'s is mastery-domain and walled off by
M2-ADR-023 §2); graph distance beyond relationship type (the probe-relationship model is strictly
one-hop — `PREREQUISITE_VALIDATION`'s query and `core.diagnostic_probe_relationship`'s edges have no
multi-hop data; `relationship_type` already encodes the only distance-like fact that exists);
`evidenceConfidence`/`MasteryStatus` as a direct input (§6 below).

### 3. The staged band rule — volume and proportional dominance together, integer-only

```
s = supportingCount, c = contradictoryCount

s == 0 && c == 0        -> INSUFFICIENT_EVIDENCE
c == 0 && s == 1        -> LOW
c == 0 && s == 2        -> MODERATE
c == 0 && s >= 3        -> HIGH
c >= 1 && s > 3*c        -> HIGH        (strong dominance: >3:1 supporting-to-contradictory)
c >= 1 && s - c >= 3     -> MODERATE    (real net corroboration, not yet 3:1-dominant)
c >= 1, otherwise        -> LOW
```

`INCONCLUSIVE` never participates — it contributes to neither `s` nor `c`, and cannot promote a
hypothesis out of `INSUFFICIENT_EVIDENCE` on its own, consistent with
`HypothesisEvidenceOutcome.INCONCLUSIVE`'s own javadoc (a non-scoreable response carries no
directional signal either way).

**Why the constant is always 3.** Three or more uncontested supporting observations is what "strong
corroboration" already means in the pure-support branch; the mixed-evidence dominance test
(`s > 3*c`) and margin test (`s - c >= 3`) both reuse that same constant rather than introducing new,
independently-tuned numbers. For any `c >= 1`, `s > 3*c` algebraically implies `s - c > 2c >= 2`,
i.e. `s - c >= 3` — the dominance test, once satisfied, always already satisfies the margin test;
they are not two independent hurdles, checked in the order shown only for clarity, not because order
changes the result.

**Why cross-multiplication, not a ratio.** `s > 3*c` is exactly the rational test
`s / (s + c) > 3/4` (supporting observations are more than 75% of all directional evidence), tested
without ever computing a fraction — pure integer arithmetic, reproducible without floating-point
rounding.

**Why `HIGH` is recoverable, but only through proportional dominance, not volume alone.** A single
historical contradiction does not permanently disqualify a hypothesis from `HIGH`:
`(4 supporting, 1 contradictory)` reaches `HIGH` outright. This is deliberate — RAMALS evidence is
behavioral (one scored answer), and treating one early, possibly-noisy contradiction as a *permanent*
ceiling would be disproportionate to what a single observation actually establishes once it becomes a
small minority of a much larger picture. But recovery requires the contradiction to end up
proportionally overwhelmed (`s > 3c`), not merely outnumbered in absolute terms —
`(100 supporting, 97 contradictory)` has the identical net margin (3) as `(4, 1)` and stays at
`MODERATE`, because proportionally it is nearly balanced directional evidence: volume alone must
never manufacture confidence a nearly-even split does not deserve.

**Why balanced and contradiction-dominant evidence both land at `LOW`, not a fifth band.** A state
below `LOW` (distinguishing "actively refuted" from "merely thin") is a real, separate design
question this milestone does not answer; `LOW` is deliberately the floor for every case that is
neither unjudged (`INSUFFICIENT_EVIDENCE`) nor genuinely corroborated (`MODERATE`/`HIGH`). A future
version may split it if a real consumer needs that distinction.

### 4. Band-only, no numeric score

No decimal or normalized score accompanies the band in V1. Nothing today consumes one, and inventing
a value merely to look more precise than four bands warrant is exactly the false precision this
policy exists to avoid. `BigDecimal` is not used anywhere in this calculator — every quantity is a
plain, small, non-negative integer count.

### 5. Persistence: append on the explicit event of a probe response being scored

Confidence is recomputed and persisted exactly once per triggering event — a probe response being
scored inside `DiagnosticSubmissionService.score`'s existing transaction — never on mere read, and
never mutated afterward.

**Verified against the actual submission implementation, not assumed.** `DiagnosticSubmissionService
.submit` is a single `@Transactional` method; `findAttemptForUpdate` row-locks the attempt for its
duration; response persistence, evidence recording, and mastery recompute already commit together or
not at all. Resubmission of an already-`COMPLETED` attempt returns the cached result and writes
nothing (`duplicateSubmitIsIdempotentAndAddsNoRows`), so a retry after a successful commit is a pure
read, and a retry after a rolled-back failure re-enters cleanly with nothing partial surviving. A
probe response is identified deterministically — `(attemptId, itemVersionId)` is exactly
`core.diagnostic_probe_provenance`'s own unique key — and because
`MAX_HYPOTHESIS_PROBES_PER_PACKET = 1`, at most one response in a single submission can ever match a
provenance row. Adding the confidence-observation write inside this same method's existing
transaction means a failure there rolls back the entire submission, including the response that was
about to become authoritative — the same guarantee `EvidenceService`/`MasteryService`'s own writes in
this exact method already rely on. This is confirmed by the method's current design, not assumed from
a hypothetical lifecycle.

**Schema** (`V056`, additive only — `V055` and every earlier migration untouched):
`core.diagnostic_confidence_observation` — one immutable row per triggering event, referencing (not
duplicating) `core.diagnostic_probe_provenance` via `triggering_provenance_id` (`UNIQUE`, `NOT NULL`,
`FOREIGN KEY`). Append-only: `trg_diagnostic_confidence_observation_guard` rejects every `UPDATE`/
`DELETE`, the same discipline `trg_probe_provenance_guard` already holds V055's table to.
`relationship_type`/`band`/`policy_version` are `CHECK`-constrained to their exact closed vocabularies
at the database boundary, not trusted from the application alone.

### 6. No mastery feedback, in either direction

`DiagnosticConfidenceCalculatorV1` takes zero mastery inputs — no `MasteryStatus`, no
`evidenceConfidence`, no `MasterySnapshot` field of any kind — and its result is read by no mastery
code path (`WeightedMasteryCalculator`, `EvidenceConfidenceCalculatorV2`, `MasteryStatusPolicyV2`).
This satisfies M2-ADR-023 §2 structurally: the calculator's own input type has no place to put a
mastery value even if a future change tried.

### 7. No new authority anywhere in this PR

H5 does not recompute mastery, does not assert a confirmed root cause, does not change `DIAGNOSTIC_
SELECTION_V2/V3/V4/V5`'s behaviour, does not touch H1's `GapDiagnosisService`, does not add LangGraph/
MCP/Spring AI/an LLM of any kind. Diagnostic confidence is a statement about how strongly distinct
evidence observations agree with themselves in favor of a hypothesis — never a probability, never
automatic root-cause confirmation, and never itself a `confirmedRootCause` state (no such state
exists anywhere in this codebase; this ADR introduces none).

## Alternatives rejected

- **A weighted-blend formula mirroring `EvidenceConfidenceCalculatorV2`.** Rejected (context, above):
  defensible for a continuous, dozens-of-items regime, false precision over H5's small, discrete
  counts.
- **Net margin alone, with no proportional-dominance test.** Rejected: conflates `(4,1)` with
  `(100,97)`, letting sheer volume manufacture confidence a nearly-even split does not deserve.
- **A permanent `contradictoryCount == 0` requirement for `HIGH`.** Rejected: disproportionately
  punitive to a hypothesis with one early, possibly-noisy contradiction that is later overwhelmed by
  proportionally dominant support.
- **A fifth band below `LOW` for contradiction-dominant evidence.** Rejected as premature: a real
  question, not answered by this milestone; `LOW` is the floor for every case that is neither
  unjudged nor corroborated.
- **A numeric score alongside the band.** Rejected: no concrete consumer exists yet; inventing one
  now is false precision for its own sake.
- **Computing confidence read-only, with no persistence.** Rejected as the sole answer: gives no
  audit trail of what confidence *was* at an earlier point (relevant to H7's later reassessment work),
  and two callers at two different times could see different answers with no record of which was
  read when.
- **Persisting a new immutable snapshot on every read.** Rejected: would create snapshot noise
  disconnected from any real event, unlike a write tied to an actual new observation.

## Consequences

- `DiagnosticConfidenceCalculatorV1` must remain pure — no database access, the same discipline
  `PrerequisiteAwareDiagnosticSelector`/`HypothesisDrivenProbeDiagnosticSelector` are already held to.
- The threshold constant (`3`, in all three of its uses) is frozen by `EngineVersionFreezeTests`; a
  future change to any threshold, to the dominance/margin arithmetic, or to which band a case
  resolves to requires a new version identifier (`DIAGNOSTIC_CONFIDENCE_V2`), never an in-place edit.
- `core.diagnostic_confidence_observation` is the only new table; a future PR persisting confidence
  as JSON, in logs only, or by mutating an existing row is a defect against §5.
- No V2/V3/V4/V5 selector, `DiagnosticService`, mastery formula, or `EvidenceConfidenceCalculatorV2`
  is touched by this ADR; a future PR routing confidence back into any of them is a defect against
  §6/§7, not an enhancement.

## Revisit triggers

- If a real consumer needs a numeric score, that is a new, explicitly-justified decision (§4), not a
  quiet addition.
- If a distinction between "contradiction-dominant" and "merely thin" evidence is ever needed, that
  is grounds to add a fifth band under a new version, not to reinterpret `LOW`.
- If H7 (reassessment-based diagnosis verification) is scoped, it may read this table's append-only
  history, but this ADR makes no claim about H7 and adds no H7 mechanism — M2-ADR-023 §3's own
  constraint on H7 needing its own explicit decision still applies, unchanged.
- If multi-hop probe relationships are ever introduced, graph distance becomes a real, separate input
  then, superseding §2's current exclusion — not before.

## Note on the ADR register

Adds `M2-ADR-026` to `docs/adr/M2-ADR-register.md` immediately after `M2-ADR-025`.
