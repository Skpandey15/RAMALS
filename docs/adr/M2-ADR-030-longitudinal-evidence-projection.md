# M2-ADR-030: Longitudinal evidence projection (H7)

- **Status:** Proposed
- **Date:** 2026-09-06
- **Gates:** the longitudinal-evidence-projection PR (H7 V1); does not authorize order-sensitive
  interpretation (recurrence/regression/reversal), mastery integration, H5 integration, persisted H7
  events, or any H6 DTO/endpoint change

## Context

M2-ADR-026/027/028/029 established a governed chain ending in H6's read-only composition of current
G2/G3/ontology/mastery facts. None of it answers a longitudinal question: given a fixed governed
evidentiary boundary, what has evidence said since? H7 is that read-only projection.

## Decision

### A. Purpose

G3 answers: "What does accumulated governed evidence say about misconception M?" (a cumulative,
ever-growing aggregate). H7 answers: "What governed evidence concerning M has appeared after a fixed
baseline evidentiary boundary?" (a bounded, post-baseline-only view).

H7 is NOT verification of the misconception, confirmation, resolution, recurrence detection,
regression detection, mastery, progression, or root-cause reasoning. It composes existing G2/G3 facts
under one new, narrow, named policy; it invents no new diagnosis.

### B. Identity

`(learner_id, misconception_id)` -- reuses G3's own aggregation identity exactly (M2-ADR-028).
`attempt_id`, `assessment_version_id`, `item_version_id`, `curriculum_version_id`, `objective_id`,
domain are provenance/context only, never identity components. The same immutable misconception ID
aggregates evidence across assessment versions (M2-ADR-028 §2, preserved unchanged). Different
misconception IDs are never merged for any reason -- same name, similar description, same target, or
semantic similarity are all explicitly rejected as merge criteria; no such linkage exists in the
ontology (M2-ADR-026) and none is introduced here.

### C. Baseline

**The baseline is the deterministically selected first eligible persisted `core.
misconception_confidence_observation` row for `(learner_id, misconception_id)`, under the repository's
governed ordering convention `created_at ASC, id ASC`, restricted to rows where `supporting_count +
contradictory_count > 0`.** This wording is deliberate and must not be loosened to "the earliest"
stated as a causal/temporal fact:

- `created_at` is fixed for the whole PostgreSQL transaction that wrote it -- two snapshots written by
  the same submission always share an identical value, so it alone cannot order them.
- `id` (UuidV7, via `UuidV7.generate()`) draws its own tiebreak bits from `SecureRandom` on every call,
  with no monotonic counter -- two ids generated within the same millisecond have comparison order
  uncorrelated with call order (verified directly against the implementation; see §I).
- Therefore `ORDER BY created_at ASC, id ASC` **cannot prove which eligible snapshot was truly
  generated first** whenever two snapshots share both a timestamp and a millisecond. It is a
  **governed deterministic evidentiary anchor** -- the same query, run again, always selects the exact
  same row -- **never a claim of causal or generation-first ordering**.
- Because baseline selection fixes `E_baseline` (§E), and therefore `E_post`, this ordering choice is
  not merely a presentation concern the way §I's detail-list ordering is: it is load-bearing for which
  row anchors the boundary. It remains sound precisely because H7 needs *a* fixed, reproducible anchor,
  not *the* causally-first one -- every state `LongitudinalEvidencePolicyV1` produces is computed from
  the anchor's own permanent provenance set onward, and is itself order-independent (§F), so no H7
  outcome ever depends on which of two same-instant eligible snapshots the tiebreak happened to prefer.

This is a fixed evidentiary boundary only -- its own direction is irrelevant to every later-evidence
state H7 reports. `S=1,C=0`, `S=0,C=1`, and `S=1,C=1` are all equally valid baselines; a row with
`supporting_count = 0 AND contradictory_count = 0` (`INSUFFICIENT_EVIDENCE`, by construction only
`INCONCLUSIVE` evidence) is never baseline-eligible, no directional content to fix a boundary around.
Once selected, a baseline is permanent for that pair -- never re-chosen as later snapshots accumulate,
and never re-selected merely because a same-instant tie could in principle have gone the other way.

Selected by, per `(learner_id, misconception_id)`:
```sql
SELECT DISTINCT ON (misconception_id)
  id, attempt_id, learner_id, misconception_id, supporting_count, contradictory_count,
  inconclusive_count, band, policy_version, created_at
FROM core.misconception_confidence_observation
WHERE learner_id = ?
  AND (supporting_count + contradictory_count) > 0
ORDER BY misconception_id, created_at ASC, id ASC
```

### D. Longitudinal data status -- NO_BASELINE is never NO_LATER_EVIDENCE

`LongitudinalDataStatus` (`NO_BASELINE` / `HAS_BASELINE`) is a distinct, independent fact from
`LongitudinalEvidenceState`. `NO_BASELINE` means no eligible baseline (§C) exists yet for the pair --
H7 has nothing to classify. `LongitudinalEvidenceState#NO_LATER_EVIDENCE` is itself a real
classification, meaningful **only** when `dataStatus = HAS_BASELINE` and `E_post` (§E) is empty. H7
never manufactures a longitudinal state when no baseline exists. The single-misconception detail
endpoint returns HTTP 200 with `dataStatus = NO_BASELINE`, `state = null`, `baseline = null`, and
`laterEvidence` all-zero for an existing misconception the learner has no eligible baseline for -- not
a 404, and not `NO_LATER_EVIDENCE`. The domain summary continues to include only `HAS_BASELINE`
findings (a misconception with `NO_BASELINE` is absent from the summary entirely, mirroring H6's own
"zero evidence is absence, not a row" convention).

### E. Post-baseline evidence (`E_post`)

`E_post = E_all(learner_id, misconception_id) − E_baseline`, where `E_all` is every
`core.misconception_evidence_observation` row for the pair and `E_baseline` is the baseline
snapshot's own permanent cited set (`core.misconception_confidence_observation_evidence`). Membership
is determined strictly by evidence-observation-id set difference -- never by comparing timestamps. No
baseline-cited evidence row is ever counted again as later evidence, because
`misconception_confidence_observation_evidence` is proven, by its own deferred constraint trigger, to
never grow after a snapshot is committed (M2-ADR-028).

### F. Policy: LONGITUDINAL_EVIDENCE_V1

A pure, sign/existence classifier over `E_post`'s own outcome counts `(supportingΔ, contradictoryΔ,
inconclusiveΔ)` -- no numeric threshold, no probability, no confidence band. Deliberately does not
reuse `DiagnosticConfidenceCalculatorV1` (frozen, untouched, unmodified): that calculator answers a
different question (cumulative lifetime strength), and a heavily-supported baseline can absorb real
post-baseline contradictory evidence without its band ever moving (e.g. baseline `S=10,C=0` HIGH stays
HIGH after two new contradictory observations, `10 > 3×2`) -- reusing its band vocabulary for a
different question would silently overload meaning.

```
SΔ=0, CΔ=0, IΔ=0      -> NO_LATER_EVIDENCE
SΔ=0, CΔ=0, IΔ>=1      -> LATER_INCONCLUSIVE_ONLY
SΔ>=1, CΔ=0, IΔ=any    -> LATER_SUPPORT_ONLY
SΔ=0, CΔ>=1, IΔ=any    -> LATER_CONTRADICTION_ONLY
SΔ>=1, CΔ>=1, IΔ=any   -> LATER_MIXED_EVIDENCE
```

The constant is named `POLICY_VERSION = "LONGITUDINAL_EVIDENCE_V1"` (not `POLICY`), deliberately
opting into `EngineVersionFreezeTests`'s mechanical scan
(`static final String \w*VERSION = "..._V\d+"`) -- since H7's outcome is the final, directly
learner/admin-facing interpretation, it is frozen like every other governed policy in this codebase,
not exempted the way `MISCONCEPTION_EVIDENCE_V1` was.

### G. Forbidden terminology

H7 V1 never produces or implies: `VERIFIED`, `CONFIRMED`, `RESOLVED`, `CURED`, `RECURRENCE`,
`RECURRED`, `REGRESSION`, `REGRESSED`, `REVERSAL`, `ROOT_CAUSE`, or any probability/likelihood
language. None are justified by repository semantics today.

### H. Latest persisted G3 context -- confidenceCoverage requires a G3 snapshot

H7 may expose the latest persisted G3 snapshot as separate context, worded **"latest persisted
overall evidence strength"** -- never "current confidence" unless provenance proves it covers every
evidence row that exists. `E_uncovered = E_all − latestPersistedConfidence`'s own cited provenance
set. **`confidenceCoverage` is meaningful only when a persisted G3 snapshot exists at all.** If no
persisted `core.misconception_confidence_observation` row exists yet for the pair,
`latestConfidence = null` **and** `confidenceCoverage = null` -- never reported as `CURRENT` in that
case, and no fake snapshot is ever manufactured to produce one. When a snapshot exists:
`confidenceCoverage = CURRENT` iff `E_uncovered` is empty, else `STALE_RELATIVE_TO_LATER_EVIDENCE`.
Determined by exact provenance ids, never timestamps. H7 never recomputes G3.

### I. Ordering

`UuidV7.generate()` (`io.ramals.learningplatform.observability.UuidV7`) draws its low 12 bits
(`randomA`) and its entire 64-bit least-significant word from `SecureRandom`, independently on every
call, with no monotonic counter. Two calls within the same millisecond have uncorrelated comparison
order relative to call order. Combined with Postgres's `CURRENT_TIMESTAMP` being fixed for the whole
transaction (so every evidence row written by one submission shares one `created_at`), this means:

`ORDER BY created_at ASC, id ASC` (used both for baseline selection, §C, and for the detail endpoint's
presentation of post-baseline evidence) is a **deterministic ordering** -- reusing this codebase's own
established tiebreak convention (`AssessmentRepository.findMostRecentCompletedAttempt`,
`AdminAuditQueryRepository`, H6's `findLatestForLearner`) -- but it is **not** a causal or
generation-order guarantee when both `created_at` and the underlying millisecond tie, which is the
common case for evidence rows from one attempt. This applies identically to both uses: §C's baseline
anchor is a governed deterministic evidentiary anchor, not a causal-first claim, exactly as the
detail-list presentation order is not a causal claim. No H7 V1 outcome depends on evidence sequence.
No recurrence/regression/reversal inference is ever derived from order.

### J. Persistence

H7 V1 is computed on read. No H7 table, no H7 event table, no migration, no current-state
persistence. G2/G3 already supply a complete, immutable, append-only, provenance-linked evidence
trail sufficient to reconstruct both the current classification and its full post-baseline evidence
list on every read -- the same reproducibility argument M2-ADR-029 §F already used to justify H6
needing no report table. A future order-sensitive or policy-historical need (e.g. a genuine
sequential/recurrence model) would require persisted events and is explicitly deferred to a later,
separately reviewed ADR.

### K. Mastery

H7 V1 exposes no mastery field, anywhere. It never joins `ledger.mastery_snapshot` or calls
`MasteryRepository`. It never infers "later contradiction implies mastery increased" or "later
support implies mastery decreased" -- no relationship between the two is asserted.

### L. H5

H7 never reads or modifies `core.diagnostic_confidence_observation`, `core.diagnostic_probe_provenance`,
`DiagnosticConfidenceService`, or `DiagnosticConfidenceRepository`. H5 remains a wholly separate
hypothesis-tuple stream, even though it happens to share `DiagnosticConfidenceCalculatorV1` with G3
for its own unrelated purpose.

### M. H6 -- untouched, zero shared surface

H6 was just stabilized (PR #257). H7 does not modify `DiagnosticReportService`,
`DiagnosticReportController`, `AdminDiagnosticReportController`, `DiagnosticReportResponse`, or
`DiagnosticReportRepository`. H7's own `LongitudinalEvidenceRepository` implements its own bounded
ontology/context reads (misconception context, diagnostic-node lookup, objective context),
structurally identical to but a wholly separate type from `DiagnosticReportRepository`'s own methods
-- a small, deliberate duplication (also mirrored in `LongitudinalEvidenceService`'s own ancestry-walk,
duplicated from `DiagnosticReportService.resolveAncestry`/`resolveTarget`) preferred over any
dependency that could let a future H6 change silently ripple into H7, or vice versa, during this
governed milestone. A shared resolver may be extracted later, in its own dedicated refactoring PR,
once both milestones have settled. H7 gets four standalone endpoints; any future projection of H7
state into an H6 report is a separate, later, separately reviewed decision.

## Alternatives rejected

- Reusing `DiagnosticConfidenceCalculatorV1` for the post-baseline delta -- rejected (§F): a different
  question wearing the same band vocabulary would silently overload meaning.
- Treating `created_at ASC, id ASC` as proof of generation order -- rejected (§I): `UuidV7.generate()`
  draws its tiebreak bits from `SecureRandom`, with no monotonic counter; same-millisecond order is
  uncorrelated with call order. This applies equally to baseline selection (§C, caught on review of
  PR #258): describing the selected row as "the earliest" G3 snapshot in a causal/temporal sense would
  overclaim what the ordering can prove whenever two eligible snapshots share both a timestamp and a
  millisecond. §C's baseline is instead defined as the deterministically selected first eligible
  snapshot under this governed ordering -- a fixed, reproducible anchor, never a causal-first claim,
  with every downstream H7 classification remaining order-independent regardless of which same-instant
  row the tiebreak happened to prefer.
- Persisting H7 events -- rejected (§J): no reproducibility gap exists that computing on read does not
  already close, and it would manufacture a second, redundant source of truth for something fully
  derivable from G2/G3.
- Exempting `LONGITUDINAL_EVIDENCE_V1` from `EngineVersionFreezeTests` the way `MISCONCEPTION_EVIDENCE_V1`
  was -- rejected (§F): unlike G2's internally-consumed classification, H7's outcome is the final,
  directly learner/admin-facing interpretation.
- Reusing `DiagnosticReportRepository`'s existing ontology-context methods, or extracting a shared
  ancestry resolver from `DiagnosticReportService` -- rejected for V1 (§M): H6 was just stabilized;
  zero shared surface is preferred over a coupling that could let either milestone's future change
  silently ripple into the other. A small, bounded duplication is the accepted cost.
- Merging misconception IDs across ontology evolution by name/description similarity -- rejected
  (§B): identity is governed strictly by immutable IDs.
- Reporting `confidenceCoverage = CURRENT` when no G3 snapshot exists, or manufacturing a synthetic
  snapshot to compute it -- rejected (§H): coverage is meaningful, and computed, only relative to a
  real persisted snapshot.

## Consequences

- `LongitudinalDataStatus`, `LongitudinalEvidenceState`, `ConfidenceCoverage`,
  `LongitudinalEvidencePolicyV1`, `LongitudinalEvidenceRepository`, `LongitudinalEvidenceService`,
  `LongitudinalEvidenceReport`, `LongitudinalEvidenceResponse`, `MisconceptionNotFoundException`, and
  two controllers are new, additive classes. `ApiExceptionHandler` gains one additive
  `@ExceptionHandler` for `MisconceptionNotFoundException`. `EngineVersionFreezeTests` gains one
  additive frozen vector for `LONGITUDINAL_EVIDENCE_V1`. No existing G2/G3/H5/H6/mastery main-source
  file is otherwise modified.
- No migration accompanies this ADR.

## Revisit triggers

- A future need for order-sensitive or sequential (recurrence-style) interpretation requires its own
  ADR and, per §J, likely a persisted-event design.
- A future H6 integration (a small current-state projection) requires its own separate review.
- Once both H6 and H7 have settled, extracting a shared ancestry/ontology-context resolver is a
  legitimate, low-risk refactor -- but its own dedicated PR, not bundled with either milestone's own
  governed semantics.
