# M2-ADR-028: Granular diagnostic confidence

- **Status:** Proposed
- **Date:** 2026-09-05
- **Gates:** the granular-diagnostic-confidence PR (G3); does not authorize adaptive misconception-driven selection, mastery integration, or any change to H5's own hypothesis-tuple confidence stream

## Context

M2-ADR-027 (V058) made it possible for a scored `SINGLE_CHOICE` response to append one immutable
`core.misconception_evidence_observation` row per event-time-eligible misconception, classified
`SUPPORTING`/`CONTRADICTORY`/`INCONCLUSIVE`. That milestone was explicitly scoped as passive
capture only — it persists evidence but never aggregates it into a strength judgement.

G3 closes that gap: given the immutable evidence a learner has accumulated for one misconception,
turn it into a deterministic confidence band, the same four-value vocabulary
(`INSUFFICIENT_EVIDENCE`/`LOW`/`MODERATE`/`HIGH`) H5 already established for hypothesis-tuple
confidence (M2-ADR-023 §2), computed by the same frozen calculator, `DiagnosticConfidenceCalculatorV1`.

This is a second, independent confidence stream — not an extension of H5's own
`core.diagnostic_confidence_observation`/`DiagnosticConfidenceService`, which remain untouched. The
two streams share a calculator, nothing else.

## Decision

### 1. Semantic confidence identity: `(learner_id, misconception_id)`

The governed unit this confidence stream evaluates is `(learner_id, misconception_id)` — not
further scoped by curriculum version, assessment version, or objective. This is a semantic
decision, not an artifact of `core.misconception_evidence_observation`'s column set: a `PUBLISHED`
`misconception_id` denotes one immutable, governed incorrect belief (M2-ADR-026). Granular
diagnostic confidence is confidence *in that governed identity itself* — the assessment content
that happened to produce a piece of evidence is incidental to what is being evaluated. Two
misconceptions with similar or even identical names remain categorically distinct confidence
streams whenever their `misconception_id`s differ; there is no text- or name-based merging, ever,
under any circumstance.

### 2. Cross-assessment-version aggregation is authorized, deliberately

Evidence from different items, item versions, and assessment versions contributes to the same
confidence stream whenever — and only whenever — it references the identical `misconception_id`.
This is an explicit semantic policy decision, not an accidental consequence of
`core.misconception_evidence_observation` lacking a version column. It holds because a
misconception's own meaning cannot change after publication (`trg_misconception_guard`, V057): the
object being evaluated is stable by construction, so evidence about it from a later assessment
version is evidence about the *same* thing, not a different one. If misconception immutability were
ever relaxed, this decision would need re-examination — it is not being relaxed here.

### 3. Snapshot-event identity: `(attempt_id, misconception_id)` — distinct from confidence identity

The unit that identifies one *persisted confidence row* is `(attempt_id, misconception_id)`: "the
confidence state of this misconception, as recomputed once by this submission." This is
deliberately distinct from the aggregation identity in §1. `attempt_id` is a safe, exact,
already-enforced anchor: `DiagnosticSubmissionService.submit` dispatches on the attempt's own
status — `COMPLETED` returns the cached result without re-scoring, `IN_PROGRESS` scores exactly
once, any other status is rejected outright. `score()`, and everything inside it, therefore runs at
most once, ever, per `attempt_id`.

### 4. Exactly one snapshot per affected misconception per submission

A single submission may contain multiple responses that each produce evidence for the same
misconception. Persisting one confidence snapshot after every individual evidence observation would
manufacture artificial diagnostic history (three snapshots within one submission where only one
ever represents this submission's own final word). Instead: the whole per-response loop runs first,
capturing every response's evidence exactly as M2-ADR-027 already governs; only after the loop
finishes does this milestone determine which misconceptions were touched *at all* by this
submission, and recompute each exactly once, from the complete accumulated evidence for
`(learner_id, misconception_id)` — including evidence from earlier submissions (§2) and this
submission's own new rows. Everything remains inside the one transaction `score()` already opens;
no new transaction boundary is introduced.

### 5. Append-only confidence history

`core.misconception_confidence_observation` is append-only and immutable once written, mirroring
`core.diagnostic_confidence_observation`'s own model (V056) — the same domain semantics (immutable
evidence feeding a deterministic band, needing full audit reconstruction) justify the same choice,
not a mechanical copy of H5's schema.

### 6. Historical snapshots are bound to their own contributing evidence set, permanently

A confidence snapshot's meaning is fixed by the evidence that existed at the moment it was computed,
recorded explicitly via the complete provenance join table (§7) — never by a live re-aggregation of
"whatever evidence exists now." An older snapshot does not, and must never be made to, equal a
later global aggregation once further attempts add evidence for the same misconception. Concretely:
if attempt A1 produces evidence `{E1, E2}` and snapshot `S1` cites exactly `{E1, E2}`, and a later
attempt A2 produces further evidence `{E3, E4}` and snapshot `S2` cites `{E1, E2, E3, E4}`, `S1`
remains historically correct for `{E1, E2}` forever — it is never revisited, recomputed, or
reinterpreted by `S2`'s existence. No database invariant may imply otherwise: there is deliberately
no trigger or constraint that ties an existing snapshot's persisted counts to a *live* query over
`core.misconception_evidence_observation` — only to the counts and provenance set it was given at
its own insert time. (Contrast this with `core.misconception_evidence_observation`'s own guard,
which independently re-derives its outcome from immutable, already-fixed facts anchored to one
specific response's own `created_at` — a genuinely fixed point that never moves. A confidence
snapshot's own evidence set is not anchored to a single fixed timestamp the same way; its
correctness is guaranteed by application discipline at write time — reading the complete evidence
set once and deriving counts, calculator input, band, and provenance ids all from that *same* read —
not by a database trigger re-deriving it independently after the fact.)

### 7. Complete evidence provenance, including `INCONCLUSIVE`

`core.misconception_confidence_observation_evidence` records the complete set of
`misconception_evidence_observation` rows contributing to one snapshot — `SUPPORTING`,
`CONTRADICTORY`, **and** `INCONCLUSIVE` alike. `INCONCLUSIVE` rows never move `supporting_count`,
`contradictory_count`, or `band` (per `DiagnosticConfidenceCalculatorV1`'s own established
treatment), but they remain part of the auditable evidence set and are what the persisted
`inconclusive_count` is answerable against. A snapshot's provenance-row count is expected to equal
`supporting_count + contradictory_count + inconclusive_count`.

### 8. `DiagnosticConfidenceCalculatorV1` reused unchanged; no new calculator

`DiagnosticConfidenceCalculatorV1`, `DiagnosticConfidenceInputs`, `DiagnosticConfidenceResult`, and
`DiagnosticConfidenceBand` are reused directly, unmodified. None of these types reference
hypothesis-tuple-specific fields (source/target objective, relationship type) at all — they were
already domain-agnostic before this milestone touched them. No `MisconceptionConfidenceCalculatorV1`
is introduced; no adapter class is introduced (none is technically necessary — the existing
`DiagnosticConfidenceInputs` record already accepts exactly the three counts this milestone
produces). No formula change, no threshold change, no frozen-hash change.

### 9. Terminology: evidence strength, not authority

A persisted band means *strength of accumulated evidence concerning one governed misconception* —
nothing more. It does not mean, and must never be represented as: a confirmed misconception, a
confirmed root cause, mastery, objective weakness, progression eligibility, or a probability that
the learner holds the belief. This is the same posture M2-ADR-023 already established for H5
("diagnostic reasoning is evidence, not a gate"), extended to a second, independent stream.

### 10. Strict separation from H5, mastery, and progression

Untouched by this milestone: `core.diagnostic_confidence_observation`, `DiagnosticConfidenceService`,
`DiagnosticConfidenceRepository`, `HypothesisEvidenceOutcome`, `core.diagnostic_probe_provenance`,
`core.diagnostic_probe_relationship`, `WeightedMasteryCalculator`, `EvidenceConfidenceCalculatorV2`,
`MasteryStatusPolicyV2`, `objectiveCoverage`, and progression eligibility. G3 shares only the
stateless calculator with H5 — no shared table, no shared service, no shared repository.

## Alternatives rejected

- **Recompute on read.** Undermines the reproducibility this ADR requires: a band computed today and
  re-derived tomorrow from the same nominal query could differ once new evidence lands, making "what
  did we tell the learner on date X" unanswerable.
- **Overwrite one current confidence row.** Cheapest, but destroys history outright; rejected for the
  same reason H5 rejected it.
- **Hybrid current projection + immutable history.** A read-side optimization with no present need —
  volume here is bounded by how many misconception-tagged items a learner has answered, the same
  order of magnitude as H5's own hypothesis observations, where a plain `ORDER BY created_at DESC
  LIMIT 1` already suffices.
- **One snapshot per individual evidence observation.** Manufactures artificial diagnostic history
  when one submission produces multiple observations for the same misconception; rejected in favor
  of exactly one recompute per affected misconception per submission (§4).
- **`UNIQUE(triggering_observation_id)` as the idempotency key**, mirroring H5's own single-reference
  model. No longer fits once recomputation is batched per submission: a snapshot may legitimately
  summarize several observations, so there is no single "the" triggering row to key off. Replaced by
  `UNIQUE(attempt_id, misconception_id)` (§3).
- **A DB trigger re-deriving a confidence row's persisted counts from a live re-aggregation of
  `core.misconception_evidence_observation`, independent of the application layer** (the same
  discipline V058 applied to its own observation guard). Rejected specifically for the *historical*
  snapshot table: such a trigger, evaluated only at insert time, cannot retroactively invalidate an
  already-written row when new evidence arrives later — so it would not actually protect anything a
  correct write-time read did not already guarantee, while creating the appearance that persisted
  counts are always "current," which is precisely the false impression §6 rules out. The band-matches-
  its-own-counts `CHECK` (§ below, same idiom as V056) is kept, because that is a static, permanent
  arithmetic fact about the row's own persisted values, never a claim about live state.
- **Count-only or watermark-based (max `created_at`/max id) snapshot boundary.** Both are
  approximations requiring a later as-of re-query to reconstruct, and the watermark forms are
  vulnerable to timestamp/id ties. The explicit join table (§7) records the exact set directly, with
  no re-query needed, matching M2-ADR-027's own provenance-completeness bar (§6 there) rather than
  regressing from it.

## Consequences

- Two independent, non-interacting confidence streams now exist side by side: H5's hypothesis-tuple
  stream and this milestone's misconception stream. A future reader must not conflate them merely
  because both reuse `DiagnosticConfidenceCalculatorV1`.
- A misconception's confidence history is permanent and cannot be edited by future evidence — only
  extended by new, later snapshots. Any feature reading this data must query "the latest snapshot for
  `(learner_id, misconception_id)`," never assume the aggregate table always reflects the most
  current possible computation instantaneously.
- No adaptive selection, mastery integration, or learner-facing surface is authorized by this ADR.
  Those remain separate, future decisions.

## Revisit triggers

- A future milestone wanting to feed granular confidence into adaptive item selection, mastery, or
  progression needs its own separately reviewed design and its own ADR — this one authorizes none of
  that.
- If misconception immutability-once-published is ever relaxed, §2's cross-version aggregation
  policy must be re-examined.
- A future policy version (`MISCONCEPTION_CONFIDENCE_V2` or similar, if this stream is ever given its
  own distinct policy identifier rather than continuing to reuse `DIAGNOSTIC_CONFIDENCE_V1`) is a new
  decision, not an edit to this one.
