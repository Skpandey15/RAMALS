# M2-ADR-029: Granular diagnostic report (H6)

- **Status:** Proposed
- **Date:** 2026-09-06
- **Gates:** the granular-diagnostic-report PR (H6 V1); does not authorize H5 reporting, a
  true historical/as-of-attempt learner-state report, mastery/confidence recomputation, or any new
  RBAC role

## Context

M2-ADR-026/027/028 established a complete, governed chain: an authored misconception ontology, its
immutable event-time evidence (`MISCONCEPTION_EVIDENCE_V1`), and its append-only, permanently
historical confidence snapshots (`DIAGNOSTIC_CONFIDENCE_V1`). None of that is exposed to a learner or
an admin today. H6 is the read-only presentation layer that composes those already-governed facts
into one deterministic report — it invents no new diagnosis, computes no new confidence, and adds no
persisted state of its own.

## Decision

### A. H6 is a deterministic, read-only composition layer

Every H6 endpoint is `@Transactional(readOnly = true)`. Nothing under this ADR writes to any table.
H6 is not a new diagnostic engine, not a root-cause engine, not a mastery engine, not an
adaptive-selection engine, and not an LLM-generated interpretation layer — it templates copy from
governed facts; no model reinterprets evidence or invents a conclusion.

### B. Two report identities, never conflated

1. **Current Domain Diagnostic Report** — identity `(learner_id, domain_code)`. The complete
   current diagnostic view for that learner and domain: every misconception the learner has
   `MISCONCEPTION_EVIDENCE_V1` evidence for in that domain, each at its own latest governed state.
2. **Attempt Diagnostic Report** — identity `(learner_id, attempt_id)`. The diagnostic findings
   that exact attempt produced — never described as, and never substituted with, "learner state as
   of this attempt." A misconception that attempt did not touch is absent, even when the learner has
   older evidence for it from a different attempt.

A true **Historical/As-Of Learner Diagnostic State** report — the latest snapshot for every
misconception as it stood at some past attempt's own completion boundary — is explicitly deferred.
It would require selecting, per misconception, the latest snapshot with `created_at` at or before a
governed historical boundary; that is a materially different query shape from either report above
and needs its own separately reviewed design.

### C. V1 composes G2 evidence, G3 confidence, ontology context, and mastery context only

H6 V1 reads: `core.misconception_evidence_observation` (evidence counts), `core.
misconception_confidence_observation`/`_evidence` (confidence and its exact provenance), `core.
misconception`/`core.diagnostic_node`/`core.learning_objective` (ontology projection), and `ledger.
mastery_snapshot` via the existing `MasteryRepository.latestMasteryMap` (mastery context). It composes
nothing else.

### D. H5 hypothesis findings are out of scope for V1

H6 V1 never reads `core.diagnostic_confidence_observation`, `DiagnosticConfidenceService`, or
`DiagnosticConfidenceRepository` (H5's own hypothesis-tuple stream). No `hypothesisFindings` field, no
`sourceObjectiveId`/`targetObjectiveId`/`relationshipType` field, appears anywhere in an H6 response.
H5 remains a separate diagnostic stream; it may get its own investigation/admin reporting surface in
a future, separately authorized milestone.

### E. H6 never invokes `DiagnosticConfidenceCalculatorV1`

`DiagnosticReportService` does not depend on `DiagnosticConfidenceCalculatorV1` at all — not as a
constructor argument, not transitively. A finding with `MISCONCEPTION_EVIDENCE_V1` evidence but no
persisted `core.misconception_confidence_observation` row is reported as `confidenceState =
NOT_ASSESSED`, `confidence = null`, verbatim — never synthesized by calling the calculator on H6's
own behalf. Every `band` an H6 response ever carries was read back from a row G3 itself already
persisted.

### F. No report persistence table

Reports are computed on read, both modes. G2/G3 already persist everything a report needs
reproducibly (event-time-immutable evidence; append-only, permanently historical confidence with
complete provenance) — a persisted report artifact would duplicate derived state with no
reproducibility gap to close, and would introduce its own staleness risk the source tables don't have.

### G. Ontology projection: ancestry is display context, never structure

A misconception targets exactly one of `LEARNING_OBJECTIVE`, `CONCEPT`, or `SUB_CONCEPT` (M2-ADR-026)
— `targetType`/`targetId` on a finding are the only structural truth. `objectiveContext`/
`conceptContext`/`subConceptContext` are display-only ancestry (present or absent per target level,
resolved by walking at most two hops: `SUB_CONCEPT` → its own `CONCEPT` → that concept's own
objective, matching the ontology's own "no third nesting level" rule) — never a claim that the
misconception is stored beneath the node it targets.

### H. Zero-data semantics — four distinct states, never blurred

- **`NO_EVIDENCE`** (report-level): no `MISCONCEPTION_EVIDENCE_V1` evidence at all in this report's
  own scope (the requested domain, or this exact attempt). `misconceptionFindings` is empty.
- An authored misconception with **zero** learner evidence is **absent** from `misconceptionFindings`
  entirely — H6 never enumerates the full authored-misconception catalogue (that belongs to
  content/coverage APIs).
- **`NOT_ASSESSED`** (per finding): evidence exists; no persisted G3 snapshot exists yet.
  `confidence = null`.
- **`ASSESSED`** (per finding) with `band = INSUFFICIENT_EVIDENCE`: a real, persisted G3 result
  (`supporting = 0, contradictory = 0`, possibly with `inconclusive > 0`) — categorically different
  from `NOT_ASSESSED`, and never rendered or worded as if it were the same absence of computation.

### I. Visibility — learner vs. admin, no new role

Learner: own misconception findings (name/description/ontology context/evidence counts/persisted
confidence band+policy+timestamp), mastery context. Admin: the same, plus each assessed finding's
exact confidence snapshot id, the attempt that computed it, and its complete cited evidence-
observation ids. No `REVIEWER`/`INSTRUCTOR` role is introduced — none exists in the codebase today
(`ADMIN`, `CONTENT_AUTHOR`, `LEARNER`, `SERVICE` are the only roles in use), and H6 grants neither
of the latter two access to learner diagnostic data.

### J. `Cache-Control: no-store` on all four endpoints

Both learner-facing endpoints and both admin-facing equivalents return `Cache-Control: no-store` —
every H6 response reflects live governed state (even the Attempt report re-reads its own persisted
rows fresh on every call), matching `AssessmentFeedbackController`'s own established precedent for a
freshly-computed, learner-sensitive read. No historical/differential caching strategy is introduced
in V1.

### K. Latest-G3-snapshot ordering reuses an established convention, hardened

`ORDER BY misconception_id, created_at DESC, id DESC` — investigated specifically before this
decision, not assumed. This is not a new tiebreak invented for H6: `AssessmentRepository.
findMostRecentCompletedAttempt` (M2-ADR-025 §4) and `AdminAuditQueryRepository`'s own "most recent"
queries already establish `created_at DESC, id DESC` as this codebase's governed answer to "two rows
can share a timestamp; PostgreSQL is free to break the tie arbitrarily, which a deterministic,
reproducible read cannot accept." `id` (UuidV7) is a total order over every row regardless of
timestamp collisions, so a genuine tie always resolves the same way on repeated reads.

This is a read-side ordering choice only — it requires and introduces no change to
`core.misconception_confidence_observation`'s own schema (V059). No monotonic per-`(learner,
misconception)` version counter (mirroring `mastery_snapshot.aggregate_version`) is added; doing so
would be a new G3 schema decision, out of scope for a reporting milestone, and inconsistent with how
this exact class of problem is already solved elsewhere in the codebase without one.

## Alternatives rejected

- **Folding the Attempt report into a "historical state" claim.** Rejected explicitly — `attempt_id`
  scoping only ever proves what one submission itself produced, never the learner's complete state at
  that moment (evidence from other, unrelated attempts is not "as of" anything with respect to a
  single `attempt_id`). Conflating the two would silently misrepresent an incomplete view as complete.
- **Enumerating every authored misconception per domain, evidenced or not.** Rejected — that is
  content/coverage's own concern; a diagnostic report about one learner's evidence should not carry
  rows about misconceptions that learner has never touched.
- **A persisted report table or projection.** Rejected (§F) — no reproducibility gap exists that
  computing on read does not already close.
- **A live re-aggregation-based "current" band computed by H6 itself.** Rejected — H6 has no
  dependency on `DiagnosticConfidenceCalculatorV1` at all (§E); a `NOT_ASSESSED` finding stays exactly
  that, never silently promoted to a synthesized band.
- **A new monotonic G3 version counter to harden latest-snapshot selection.** Investigated and
  rejected for V1 (§K) — the existing `created_at DESC, id DESC` convention, already relied upon
  elsewhere in this codebase, is adopted as-is; introducing new G3 schema is a separate decision this
  ADR does not authorize.

## Consequences

- A future H5 reporting surface, and a future true historical/as-of-attempt learner-state report, are
  both explicitly anticipated as separate, later decisions — neither is precluded by this ADR, and
  neither is authorized by it.
- `DiagnosticReportService`/`DiagnosticReportRepository`/`DiagnosticReportController`/
  `AdminDiagnosticReportController` are new, additive classes. `MisconceptionConfidenceRepository`
  gains three additive, read-only methods (`findLatestForLearner`, `findAllForAttempt`,
  `findProvenanceForSnapshots`); everything that already existed there is untouched.
- No migration accompanies this ADR — V059 remains the latest schema version.

## Revisit triggers

- A future milestone exposing H5 hypothesis findings, or the deferred historical/as-of-attempt
  report, each needs its own separately reviewed design and, if it introduces a genuinely new
  architectural decision, its own ADR.
- If real telemetry ever shows a genuine concurrent-submission collision at the `misconception_
  confidence_observation` latest-snapshot boundary, that would justify a separate, evidence-driven G3
  hardening ticket (e.g., a monotonic version counter) — not a retroactive change to this ADR.
