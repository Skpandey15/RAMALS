# M2-ADR-034: Information-gain diagnostic probe selection (`DIAGNOSTIC_SELECTION_V6` / `INFORMATION_GAIN_V1`) — design only

- **Status:** Proposed. **Amended — 2026-09-10** — see
  [Amendment 1](#amendment-1--hypothesis_uncertainty_v1-2026-09-10): ratifies the deterministic
  hypothesis-uncertainty construct §4 originally left to "the design PR", names it
  `HYPOTHESIS_UNCERTAINTY_V1`, freezes its complete mathematics and golden vectors, and authorizes
  its inert implementation as Step 1. `DIAGNOSTIC_SELECTION_V6` and `INFORMATION_GAIN_V1` remain
  design-only.
- **Date:** 2026-09-08
- **Decides:** the design constraints binding a future deterministic information-gain diagnostic
  probe-selection policy — a `DIAGNOSTIC_SELECTION_V6` that supersedes only `V5`'s final
  candidate-tiebreak step (§2), and a named, versioned, frozen `INFORMATION_GAIN_V1` construct —
  including how diagnostic-hypothesis
  uncertainty is represented, how expected information gain is computed and ranked deterministically,
  why the authoritative selection is never an LLM's, and that this ADR authorizes design only, not
  implementation.
- **Relates to, and extends, M2-ADR-025**
  (`docs/adr/M2-ADR-025-hypothesis-driven-probe-runtime-selection.md`): this ADR is the "new
  decision" M2-ADR-025's own revisit trigger anticipated — *"If H5's confidence construct is built
  and wants to weigh several candidate probes rather than the first eligible one, that supersedes
  §2/§6's 'first eligible, quota one' default — a new decision, not an extension smuggled into this
  ADR."* It does not revisit `V1`–`V5`, their composition order, `MAX_HYPOTHESIS_PROBES_PER_PACKET`,
  `core.diagnostic_probe_relationship`, or `core.diagnostic_probe_provenance`.
- **Relates to, and extends, M2-ADR-023 §2**: any diagnostic/causal confidence or uncertainty
  construct must be a named, versioned calculator with a frozen behaviour vector, deterministic and
  reproducible from already-authoritative inputs, and never a number an AI model assigns or adjusts.
- **Reaffirms, and does not widen, M2-ADR-032** (governed advisory AI diagnostic-probe proposal
  boundary): the only permitted AI role remains "propose at most one bounded candidate per
  interaction; Java's independent, deterministic, fail-closed gate decides."
- **Originates here**, on the same repository-native basis as M2-ADR-023 through M2-ADR-033.
- **Scope (as amended 2026-09-10).** The original ADR authorized *design only*. [Amendment 1](#amendment-1--hypothesis_uncertainty_v1-2026-09-10)
  additionally authorizes implementation of the **inert `HYPOTHESIS_UNCERTAINTY_V1` foundation
  construct only** (§4 as frozen there; staged as **Step 1**). It still authorizes **no**
  `DIAGNOSTIC_SELECTION_V6` code, **no** `INFORMATION_GAIN_V1` code, **no** migration, **no** contract
  change, and **no** `SelectionReason` value. `V6` is not implemented. `DIAGNOSTIC_SELECTION_V1`–`V5`,
  their composition order, `MAX_HYPOTHESIS_PROBES_PER_PACKET`,
  `core.diagnostic_probe_relationship` / `core.diagnostic_probe_provenance`, and every existing
  frozen calculator are untouched; `HYPOTHESIS_UNCERTAINTY_V1` adds one new frozen vector to
  `EngineVersionFreezeTests` and changes no existing one, and no runtime selector consumes it.

## Context

`DIAGNOSTIC_SELECTION_V2`–`V5` already select a diagnostic probe deterministically: `V2`
(`AdaptiveDiagnosticSelector`) escalates difficulty per skill from its own evidence, `V3`
(`PrerequisiteAwareDiagnosticSelector`) caps for unsecured prerequisites, `V4`
(`HypothesisConfirmationDiagnosticSelector`) reprioritizes on cross-attempt regression, and `V5`
(`HypothesisDrivenProbeDiagnosticSelector`) targets a probe from a resolved hypothesis relationship.
`V5`'s trigger, per M2-ADR-025 §2, is deliberately *"incorrect, and a published or graph-derived
relationship exists"* — resolved in a **fixed type-priority order** (`ROOT_CAUSE_PROBE →
CONTRADICTION_CHECK → PREREQUISITE_VALIDATION → SAME_OBJECTIVE_CONFIRMATION`), first eligible miss by
`presentation_order`, quota `MAX_HYPOTHESIS_PROBES_PER_PACKET = 1`. M2-ADR-025 §2 is explicit that
this is *not* an unexpectedness measure: *"the system does not compute unexpectedness — it computes
'incorrect, and a published or graph-derived relationship exists.'"*

The strategic direction wants probe selection driven instead by **which probe is expected to reduce
diagnostic uncertainty the most** — entropy reduction / expected information gain / Bayesian
posterior update. Given diagnostic hypotheses with posterior mass (illustratively `H1` 0.40, `H2`
0.35, `H3` 0.25), the platform should prefer the probe whose possible outcomes best separate them,
rather than the probe a fixed type-priority order happens to reach first.

Five shortcuts are worth naming now, because each has an obvious, tempting, wrong version:

1. *"Ask the LLM which question maximizes information gain."* Forbidden by M2-ADR-023 (no AI-ranked
   or AI-classified root-cause hypotheses), M2-ADR-025 §10 (*"does not ask an LLM to choose a
   probe"*), and M1-ADR-010 / M2-ADR-010. M2-ADR-032 already fixes the sole permitted AI role and
   this ADR does not widen it.
2. Rewriting `V2`–`V5` to be posterior-aware. Forbidden: the composition order and `V2`'s frozen,
   skill-grained input contract are frozen (M2-ADR-025 §1/§3 and its "Alternatives rejected").
3. Folding the posterior or the information-gain score into `WEIGHTED_MASTERY_V1`,
   `EVIDENCE_CONFIDENCE_V2`, or `MASTERY_STATUS_POLICY_V2`. Forbidden: M2-ADR-023 §2 — diagnostic
   confidence *"never feeds back into mastery computation."*
4. Introducing probability with an inline heuristic that can drift silently across commits.
   Forbidden: M2-ADR-023 §2 — a named, versioned calculator with a frozen behaviour vector, the same
   discipline `EvidenceConfidenceCalculatorV2`, `WeightedMasteryCalculator`, and
   `DiagnosticConfidenceCalculatorV1` are already held to.
5. Making information gain a learned / ML model. Out of scope and not authorized — a deterministic,
   frozen construct comes first, as every other scored engine in this codebase.

## Decision

### 1. The target loop, stated

```
diagnostic hypotheses
      ->
hypothesis uncertainty / posterior         (deterministic, versioned, never AI-assigned)
      ->
candidate probes                           (from existing selection machinery + M2-ADR-032 advisory input, if any)
      ->
expected information gain per candidate     (INFORMATION_GAIN_V1, frozen)
      ->
deterministic ranking                       (explicit total order, documented tie-break)
      ->
selected probe                              (Java, reproducible from persisted inputs)
      ->
new evidence                                (a learner action)
      ->
updated hypothesis confidence               (recomputed deterministically from the new evidence)
```

Every arrow except "new evidence" is a deterministic, reproducible, versioned computation from
already-authoritative inputs. No arrow is an LLM call.

### 2. `DIAGNOSTIC_SELECTION_V6` supersedes only `V5`'s final candidate tiebreak — never a rewrite

- The wrapper chain stays `V3 → V4 → V5 → frozen V2`, mirroring exactly how `V3`/`V4`/`V5` already
  wrap `V2` (M2-ADR-025 §1). `V6` is not a fourth wrapper stacked after `V5`; it is a governed
  replacement of a single deterministic step *inside* `V5`'s candidate handling (the tiebreak that
  picks one eligible candidate — see the next bullet). `V6` is never a replacement selection
  algorithm, and never a change to `V1`–`V4` code, to `MAX_HYPOTHESIS_PROBES_PER_PACKET`, or to the
  shape of `core.diagnostic_probe_relationship` / `core.diagnostic_probe_provenance`.
- `V6` acts only when a bounded, well-formed hypothesis set with a computed posterior exists for the
  attempt being created; otherwise it degrades to no adjustment and `V5` behaves exactly as
  M2-ADR-025 already froze it — the same "degrades to no adjustment, never breaks attempt creation"
  guarantee M2-ADR-025 §2 gives `V5`.
- **`V6` supersedes exactly one `V5` step, not the composition order.** `V5` today does two things
  (M2-ADR-025 §2/§3): (a) it *resolves* which related-probe candidates are eligible for the trigger
  miss, through `ProbeRelationshipService` — trigger eligibility, the fixed relationship-type
  priority order, ambiguity handling, and provenance; then (b) it *picks one* of those eligible
  candidates by a deterministic tiebreak ("first miss by `presentation_order`", "first relationship
  type by fixed priority") and restricts `V2`'s pool to it. Because step (b) collapses the pool to a
  single item, a `V6` that merely "runs after `V5`" would receive a degenerate one-probe pool and
  have nothing to rank. `V6` therefore **replaces step (b)'s deterministic tiebreak** with an
  information-gain ranking over the *same* eligible-candidate set step (a) produced, when `V6` is
  active — and only step (b). Step (a), the quota, and the provenance model are untouched; `V5`'s
  own code path still resolves the candidates, and `V1`–`V4` are entirely unaffected. This is
  precisely the supersession M2-ADR-025's own revisit trigger authorized ("supersedes §2/§6's
  'first eligible, quota one' default — a new decision, not an extension smuggled into this ADR"),
  scoped here to the tiebreak alone.
- **Quota unchanged.** `V6` still selects **at most one** probe per packet and still restricts
  `V2`'s input pool to that one item — `MAX_HYPOTHESIS_PROBES_PER_PACKET` and its structural
  enforcement (M2-ADR-025 §3/§6) are frozen and carried forward verbatim. `V6` changes *which*
  eligible candidate becomes that one item, from "first by fixed priority" to "highest expected
  information gain"; it never widens the packet to more than one probe.

### 3. `INFORMATION_GAIN_V1` — a named, versioned, frozen, deterministic construct

- Follows the `EngineVersionFreezeTests` discipline: a `static final String … VERSION =
  "INFORMATION_GAIN_V1"` identifier, a frozen behaviour vector, and no tunable threshold or weight
  that can change silently across commits (M2-ADR-023 §2; the `DiagnosticConfidenceCalculatorV1`
  precedent).
- **Inputs are already-authoritative and persisted:** the diagnostic hypothesis set and its
  provenance (`DiagnosticHypothesis`, `core.diagnostic_probe_relationship`,
  `core.diagnostic_probe_provenance`); existing diagnostic/causal confidence
  (`core.diagnostic_confidence_observation`, `DiagnosticConfidenceCalculatorV1`); prerequisite-graph
  distance; evidence volume and corroborating-versus-contradictory counts (the same input family
  M2-ADR-023 §2 already enumerates); and the candidate probe pool with each probe's possible
  *deterministically scoreable* outcomes.
- **A deterministic outcome model is part of the freeze — an expectation needs one, and it may not
  be learned.** An *expected*-information-gain score requires, for each candidate probe, the
  relationship between the probe's possible deterministically-scoreable outcomes and each
  hypothesis. `INFORMATION_GAIN_V1` MUST define that outcome model as a **fixed deterministic
  function of already-authoritative inputs**, frozen with the rest of the construct — for example
  built from `HypothesisEvidenceOutcome`'s existing three-valued mapping (M2-ADR-024 §3: an
  incorrect scoreable response is `SUPPORTING`, a correct one `CONTRADICTORY`, a non-scoreable one
  `INCONCLUSIVE`) combined with the current posterior — with **no learned, fitted, or tuned
  probabilities**. If the design PR cannot express a defensible deterministic outcome model, it MUST
  NOT use an expectation form: the output then becomes a **non-expectation deterministic
  discrimination score** — a bounded rubric over authoritative quantities such as posterior spread,
  relationship-type specificity, and evidence volume. "Expected information gain" wording is
  permitted only when the outcome model behind it is itself deterministic and frozen.
- **Output:** a deterministic real-valued score per candidate probe (an expected-information-gain
  value under the frozen outcome model above, or the non-expectation discrimination score), and a
  total order over candidates with ties broken by an explicit, documented, deterministic key — never
  by SQL row order (the discipline M2-ADR-024 §5 and M2-ADR-025 §4 already enforce). The score is
  evidence-acquisition value only. It is never a diagnosis, never a learner-facing number, never
  mastery, and never root-cause truth.
- The design PR chooses and freezes the concrete method — entropy reduction over the hypothesis
  posterior under the frozen outcome model, expected KL divergence, expected posterior-variance
  reduction, or a bounded deterministic scoring rubric. The brief's constraint is adopted verbatim:
  *do not over-engineer this into an ML system prematurely.*
- **`INFORMATION_GAIN_V1` consumes, and never recomputes, the frozen hypothesis-uncertainty
  distribution** (Amendment 1). It reads `HYPOTHESIS_UNCERTAINTY_V1`'s output verbatim; it may not
  re-derive it with different band weights, a different evidence boundary, or a different
  normalization. If the `INFORMATION_GAIN_V1` design needs different uncertainty semantics, that is a
  new `HYPOTHESIS_UNCERTAINTY_V2`, not a silent reinterpretation.

### 4. Hypothesis uncertainty / posterior representation

> **Amended 2026-09-10.** [Amendment 1](#amendment-1--hypothesis_uncertainty_v1-2026-09-10) resolves
> the choices this section left open: the construct is a **normalized relative hypothesis-uncertainty
> distribution** (not a Bayesian posterior — it has no prior, no likelihood, no evidence-update), it
> is named **`HYPOTHESIS_UNCERTAINTY_V1`**, it is a **companion frozen construct** distinct from
> `INFORMATION_GAIN_V1`, and its complete mathematics, status model, decimal contract, canonical
> ordering, and golden vectors are frozen there. The bullets below remain binding as the framing
> constraints; Amendment 1 is the authoritative specification of the construct itself.

- Diagnostic hypotheses gain an explicit, versioned, deterministic uncertainty representation — a
  posterior mass, or an equivalent bounded uncertainty measure, over the current hypothesis set for
  the attempt/interaction — computed by `INFORMATION_GAIN_V1` (or a companion frozen construct the
  design PR names) from already-authoritative inputs, and **never assigned or adjusted by an AI
  model** (M2-ADR-023 §2).
- This posterior is a **distinct stream**. It never merges into, and never revises,
  `WEIGHTED_MASTERY_V1` evidence confidence, G3 `(learner_id, misconception_id)` confidence
  (M2-ADR-028), H5's hypothesis-tuple confidence stream, or H7's longitudinal projection. It may
  reuse an existing frozen calculator only if the design PR shows the reuse is exact and unmodified
  — the precedent M2-ADR-028 set by reusing `DiagnosticConfidenceCalculatorV1` verbatim.
- Where persisted, it is append-only with provenance, policy-version, and `interactionId` /
  `traceId` / `spanId` correlation, mirroring `core.diagnostic_confidence_observation` (M2-ADR-025
  §8, M2-ADR-028). Whether it is persisted or computed-on-read is a design-PR decision, reviewed
  against the "compute on read where the append-only trail already reconstructs it" precedent
  (M2-ADR-029 §F, M2-ADR-030 §J).

### 5. The authoritative selection is never an LLM's — M2-ADR-032 is the only AI role

- `INFORMATION_GAIN_V1` scoring, ranking, tie-breaks, quota, the posterior computation, and the
  final selected probe are all deterministic Java, reproducible from persisted inputs. An LLM never
  computes the score, never ranks candidates, never chooses the probe, and never supplies the
  posterior.
- The **only** permitted AI participation is the already-governed M2-ADR-032 advisory boundary: an
  agent may propose **at most one** bounded next-probe *candidate* per interaction, referencing only
  governed evidence and authored misconceptions supplied to it; Java's independent, deterministic,
  fail-closed gate (M2-ADR-032 §4, a minimum 15-point validation list) decides whether that
  candidate is even admitted to the pool `INFORMATION_GAIN_V1` then scores. An accepted advisory
  candidate is an *input to* deterministic ranking, never a substitute for it.
- This ADR does not widen M2-ADR-032, does not authorize same-attempt LLM-driven questioning
  (M2-ADR-025 §10, M2-ADR-032 §6), and does not let an accepted recommendation feed any confidence
  computation (M2-ADR-032 §5).

### 6. M2-ADR-023 and M2-ADR-025 are preserved

- M2-ADR-023's protected question — *"what is the learner's authoritative gap / root cause, and how
  confident is the platform that it explains their weakness?"* — remains exclusively Java's, computed
  by named, versioned, deterministic calculators, never fed by anything this ADR authorizes.
  `INFORMATION_GAIN_V1` answers only *"which additional evidence is expected to be most
  discriminating"* — the same evidence-acquisition-versus-diagnosis line M2-ADR-032 §1 draws.
- M2-ADR-025 §10's guarantees (no LangGraph in selection, no same-attempt dynamic questioning, no
  LLM probe choice; deterministic and reproducible trigger, relationship lookup, ambiguity handling,
  candidate selection, priority, tie-breaks, quota, and provenance) apply to `V6` unchanged. `V6`
  adds a deterministic scoring layer; it removes none of those guarantees.

### 7. Scope: design only

This ADR authorizes the *design* of `DIAGNOSTIC_SELECTION_V6` and `INFORMATION_GAIN_V1` against the
constraints above. It does not authorize implementation, a migration, a contract change, a new
`SelectionReason` value, or any code change in this PR. A follow-up implementation milestone requires
its own separately reviewed design; a further ADR only if that design introduces a decision not
already governed here or elsewhere (the M2-ADR-026 §8 governance-rule pattern).

## Alternatives rejected

- **An LLM computes or chooses the information-gain-optimal probe.** Rejected: M2-ADR-023,
  M2-ADR-025 §10, M1-ADR-010 / M2-ADR-010; M2-ADR-032 already fixes the sole AI role and this ADR
  does not reopen it.
- **Rewrite `V2`–`V5` to be posterior-aware.** Rejected: the frozen composition order and `V2`'s
  frozen skill-grained input contract (M2-ADR-025 §1/§3).
- **Feed the posterior or the information-gain score into mastery or evidence confidence.** Rejected:
  M2-ADR-023 §2.
- **Introduce probability with an inline heuristic.** Rejected: M2-ADR-023 §2 — a named, versioned,
  frozen calculator only.
- **Make information gain a learned / ML model now.** Rejected: premature ML; a deterministic frozen
  construct first, as every other scored engine in this codebase.
- **Ship `V6` in this PR because the construct would otherwise sit unused.** Rejected: the
  M2-ADR-024 (#251 inert foundation) → M2-ADR-025 (#252 runtime) two-stage discipline.

## Consequences

- `V1`–`V5`, their composition order, `MAX_HYPOTHESIS_PROBES_PER_PACKET`,
  `core.diagnostic_probe_relationship`, `core.diagnostic_probe_provenance`, and every frozen
  calculator are unchanged by this ADR; a future PR altering them under this ADR is a defect against
  it.
- Any future implementation in which an LLM supplies the posterior, the information-gain score, the
  ranking, or the selected probe is a defect against §5, not an optimization.
- `INFORMATION_GAIN_V1` must ship with an `EngineVersionFreezeTests` frozen vector before any
  runtime use.
- The hypothesis posterior is a separate stream; a PR merging it into mastery, G2, G3, H5, or H7 is
  a defect against §4.
- **(Amendment 1)** `HYPOTHESIS_UNCERTAINTY_V1` must ship with an `EngineVersionFreezeTests` frozen
  vector reproducing [Amendment 1 §J](#j-golden-vectors)'s eight golden vectors before any consumer
  exists. Its Step-1 implementation is **inert**: no `DIAGNOSTIC_SELECTION_V1`–`V5` code, no runtime
  selector, no migration, and no contract change. A PR that wires it into selection, changes a band
  weight, the normalization procedure, the evidence boundary, or the canonical ordering without
  minting `HYPOTHESIS_UNCERTAINTY_V2` is a defect against Amendment 1.
- **(Amendment 1)** `HYPOTHESIS_UNCERTAINTY_V1` takes **no** misconception-relationship-graph
  (M2-ADR-033) input and **no** H7 longitudinal input; a PR adding either is a defect against
  Amendment 1 §F/§G.
- Adds a row to [`M2-ADR-register.md`](M2-ADR-register.md). `AdrRegisterTests` matches only
  `M1-ADR-\d{3}` filenames, so it is unaffected.

## Revisit triggers

- If the design PR concludes a deterministic information-gain construct cannot be made useful
  without model input, that is an AI-authority-boundary change requiring the same scrutiny
  M1-ADR-010 / M2-ADR-010 / M2-ADR-023 received — not a silent addition to `V6`.
- If `V2` is ever reworked to be objective- or posterior-aware natively, §2's wrapper mechanism
  should be reconsidered — the same revisit trigger M2-ADR-025 already carries for `V5`'s
  pool-restriction mechanism.
- If a genuine multi-hop hypothesis chain is wanted, M2-ADR-025 §4's one-hop horizon must be
  revisited first, deliberately, with its own decision.

## Note on the ADR register

Adds `M2-ADR-034` to [`docs/adr/M2-ADR-register.md`](M2-ADR-register.md)'s "Decisions originating in
this repository" table, immediately after `M2-ADR-033`, in the same row format already used for
`M2-ADR-016` through `M2-ADR-033`.

## Amendment 1 — `HYPOTHESIS_UNCERTAINTY_V1` (2026-09-10)

The original ADR (§3–§4) deferred the concrete hypothesis-uncertainty construct to "the design PR"
and offered a menu (posterior mass, KL divergence, posterior-variance reduction, or a bounded
deterministic rubric) without freezing priors, likelihoods, normalization, decimal policy, ordering,
or vectors. A governance review found this under-specified: two independent implementations could not
be guaranteed to produce the same numbers, and RAMALS's existing hypothesis-confidence construct
(`DiagnosticConfidenceCalculatorV1`, `DIAGNOSTIC_CONFIDENCE_V1`) is a *deliberate* rejection of
turning small discrete evidence counts into a decimal probability ("false precision"). This amendment
resolves every open choice, freezes the construct, and authorizes its inert implementation as
**Step 1** of a three-step plan. It changes no other section's constraints.

### A. Name and nature — not a posterior

The construct is **`HYPOTHESIS_UNCERTAINTY_V1`**. It is a **normalized relative
hypothesis-uncertainty distribution**, not a Bayesian posterior: it defines no prior, no likelihood
function, and no evidence-update operator, so "posterior" would misrepresent its mathematical
content (M2-ADR-023 §2's "never a probability" discipline; the same reason
`DiagnosticConfidenceResult` carries "deliberately no numeric score"). It answers exactly one
question — *"given the hypotheses that currently have directional evidence in this interaction, how
is relative corroboration distributed among them?"* — and nothing about ground truth, diagnosis, or
what to probe next. A genuinely Bayesian construct, if ever wanted, is a separate future
`HYPOTHESIS_POSTERIOR_V*` with its own ADR step; it is **not** what §4 authorizes.

The engine version identifier is the string `HYPOTHESIS_UNCERTAINTY_V1`. It is frozen under the
`EngineVersionFreezeTests` discipline (a `static final String … VERSION`, a frozen behaviour vector,
no tunable weight or threshold) exactly as every other scored engine in the codebase.

### B. Reuse of `DiagnosticConfidenceCalculatorV1` — valid, as the band source only

`DiagnosticConfidenceCalculatorV1` (`DIAGNOSTIC_CONFIDENCE_V1`, M2-ADR-023 §2 / H5) is reused
**verbatim and unmodified** — the exact-and-unmodified condition §4 sets. Its documented semantics:

| Property | `DiagnosticConfidenceCalculatorV1` |
|---|---|
| Input | `DiagnosticConfidenceInputs(supportingCount, contradictoryCount, inconclusiveCount)` — distinct evidence-observation counts for one hypothesis tuple |
| `SUPPORTING` | an incorrect scoreable probe response; raises corroboration |
| `CONTRADICTORY` | a correct scoreable probe response; weighs against |
| `INCONCLUSIVE` | **never participates** — contributes to neither count, "carried for audit completeness only" |
| Insufficient | `s == 0 && c == 0` -> `INSUFFICIENT_EVIDENCE`, explicitly *"categorically different from one actively contradicted; never collapsed into `LOW`"* |
| Thresholds | integer only: `c==0` -> `s` in {1,2,>=3} -> {LOW,MODERATE,HIGH}; `c>=1 && s>3c` -> HIGH; `c>=1 && s-c>=3` -> MODERATE; else LOW. The one constant is `3`. |
| Ordering | n/a — a pure per-tuple function, one call, no collection |
| `BigDecimal` | none — integer arithmetic, no numeric score in the result |
| Output meaning | *"how strongly the SUPPORTING/CONTRADICTORY evidence gathered so far for one hypothesis tuple agrees with itself in favor of that hypothesis"* — an **isolated, non-comparative, non-probabilistic corroboration tier** |

**Is its output valid as the sole input to a relative uncertainty distribution?** Only with the
two safeguards this amendment adds, because the band is *not* a cardinal quantity and *not*
comparative:

1. **`INSUFFICIENT_EVIDENCE` is not a point on the LOW/MODERATE/HIGH scale.** It means "unprobed /
   no directional evidence", which is *unresolved uncertainty*, not *low plausibility*. It is
   therefore **excluded from the distribution entirely** (§C, §D), never assigned belief mass.
2. **The three graded bands are treated as an ordinal scale with the minimal defensible linear
   cardinalization** (§C), not as ratio-scaled probabilities. The distribution expresses *relative
   corroboration rank*, and the amendment's output keeps the raw `band` alongside every normalized
   value so a consumer is never forced to read the number as more than that.

With those two safeguards the reuse is sound: `HYPOTHESIS_UNCERTAINTY_V1` calls
`DiagnosticConfidenceCalculatorV1.compute(...)` once per candidate to obtain its band, and does not
re-implement or alter any threshold.

### C. Band -> weight mapping — ordinal-linear `LOW = 1, MODERATE = 2, HIGH = 3`

`INSUFFICIENT_EVIDENCE` has **no weight** (it is not in the distribution — §D). For the three graded
bands:

| Mapping | Asserted semantics | Verdict |
|---|---|---|
| **`LOW=1, MODERATE=2, HIGH=3`** (ordinal-linear) | `MODERATE` corroboration counts twice `LOW`; `HIGH` three times `LOW`. Matches `DiagnosticConfidenceCalculatorV1`'s *own* structure: in uncontested support the bands are reached at exactly 1, 2, 3 observations, and the recurring policy constant is `3`. Smallest-integer linear embedding of a 3-tier ordinal scale. | **Chosen.** It asserts only the ordinal fact the bands actually encode, using the source construct's own spacing and constant. |
| `LOW=1, MODERATE=2, HIGH=4/8` (exponential doubling) | `HIGH` is 4x `LOW`; a single `HIGH` candidate outweighs any number of `LOW` candidates below a fixed count. | **Rejected.** Geometric belief spacing is a *policy choice* the source construct never establishes; "convenient to normalize / monotonic / simple" are implementation properties, not diagnostic semantics. |
| Authored probability-like weights (`0.15 / 0.35 / 0.50`, …) | A specific numeric belief per band. | **Rejected for V1.** Tuned decimals with no derivation — precisely the "`0.6234` no one could defend against `0.61`" false precision `DiagnosticConfidenceCalculatorV1`'s own rationale rejects. Would need an empirical or authored basis and its own ADR step. |
| Direct evidence-derived score (`s/(s+c)`, `s-c`, …) | A continuous confidence recomputed from the raw counts. | **Rejected for V1.** Not "reuse" of the frozen calculator — a parallel confidence calculator over the same inputs, which §4 forbids, and which `DiagnosticConfidenceCalculatorV1`'s javadoc argues against directly for small counts. |

The three weights `1, 2, 3` are the complete constant set of `HYPOTHESIS_UNCERTAINTY_V1`. There are
no other tunable numbers. `INCONCLUSIVE` evidence has no weight and no effect (§B, §F).

### D. Evidence sufficiency is kept separate from relative belief — a status model

Rather than force `INSUFFICIENT_EVIDENCE` onto the numeric scale, the construct returns a **status**
plus a **distribution over the evidenced subset only**:

```
status in { APPLICABLE, INSUFFICIENT_EVIDENCE, NOT_APPLICABLE }
```

- **`NOT_APPLICABLE`** — the candidate hypothesis set is empty. No distribution; `candidates` is
  empty.
- **`INSUFFICIENT_EVIDENCE`** — the candidate set is non-empty, but **no** candidate has directional
  (`SUPPORTING` or `CONTRADICTORY`) evidence in this interaction (every band is
  `INSUFFICIENT_EVIDENCE`). No distribution; each candidate is returned with its band,
  `participates = false`, and `normalizedValue = null`. The honest answer: the platform cannot say
  which hypothesis is more plausible.
- **`APPLICABLE`** — at least one candidate has directional evidence. The distribution is computed
  **only over the candidates whose band != `INSUFFICIENT_EVIDENCE`** ("participating" candidates).
  Non-participating candidates are still returned — with their band, `participates = false`, and
  `normalizedValue = null` — so a consumer (e.g. a future `INFORMATION_GAIN_V1` that wants to probe
  the *unknown* hypotheses) sees the full candidate set, but they carry **no belief mass**.

This directly resolves the reviewed failure mode: for `H1 -> HIGH`, `H2 -> INSUFFICIENT_EVIDENCE`,
`H3 -> INSUFFICIENT_EVIDENCE`, `HYPOTHESIS_UNCERTAINTY_V1` returns `status = APPLICABLE`,
`H1 = 1.0000`, `H2 = null`, `H3 = null` — **not** `0.8 / 0.1 / 0.1`. `0.1` of phantom diagnostic
support for an unprobed hypothesis is never produced.

### E. Candidate-set authority — the construct never creates candidates

`HYPOTHESIS_UNCERTAINTY_V1` evaluates an **already-authorized bounded candidate set** produced by
the existing deterministic diagnostic-hypothesis machinery (`DiagnosticService`'s miss walk ->
`ProbeRelationshipResolver` / `ProbeRelationshipService` resolution in the frozen
`HypothesisDrivenProbeDiagnosticSelector.RELATIONSHIP_TYPE_PRIORITY` order). No candidate may enter
the set because of an LLM suggestion, an embedding, semantic similarity, an M2-ADR-033
misconception-graph neighbour, graph centrality, or any model confidence. The construct adds no
eligibility mechanism and removes none; it takes the set as an input.

### F. Evidence temporal boundary — per-interaction only, no H7

For each candidate hypothesis `h`, the input counts `(supportingCount, contradictoryCount,
inconclusiveCount)` are the distinct governed **probe-response evidence observations for `h`'s
hypothesis tuple `(learner, h.triggerObjectiveId, h.targetObjectiveId, h.relationshipType)`,
produced within the single diagnostic interaction (attempt) the uncertainty is being computed
for**, classified by the existing frozen `HypothesisEvidenceOutcome`. Explicitly:

| Source | Participates in `HYPOTHESIS_UNCERTAINTY_V1`? |
|---|---|
| Diagnostic probe-response evidence in **this** interaction (`HypothesisEvidenceOutcome`) | **Yes** — the only input |
| Prior interactions / other attempts / the learner's cross-attempt H5 history | **No** |
| H6 diagnostic report projection | **No** (a read model, not an evidence source) |
| H7 longitudinal evidence projection (`LONGITUDINAL_EVIDENCE_V1`) | **No** |
| Misconception evidence (`MISCONCEPTION_EVIDENCE_V1`) / G3 misconception confidence | **No** — a separate stream (§4: "never merges into … G3") |
| `core.diagnostic_confidence_observation` accumulated cross-attempt totals | **No** — V1 uses per-interaction counts, which are a *tighter* scope than the persisted H5 identity |

The Step-1 assembler is responsible for producing per-interaction counts; the calculator consumes
counts only and never queries a repository, a clock, or a random source.

### G. M2-ADR-033 misconception-graph boundary — no participation in V1

```
MisconceptionGraphQueryService  --X--  HYPOTHESIS_UNCERTAINTY_V1
```

`HYPOTHESIS_UNCERTAINTY_V1` takes **no** `MISCONCEPTION_RELATED` or `MISCONCEPTION_PREREQUISITE_LINK`
edge as input and defines **no** edge-type contribution (no `SPECIALISES +x`, `CO_OCCURS_WITH +y`,
`PREREQUISITE_LINK +z`). Authored curriculum structure and evidence-derived learner belief state are
separate concerns; combining them is new algorithmic policy that a future ADR step must authorize on
its own merits (mirrors M2-ADR-033 §6 and this ADR §5's AI-authority discipline).

### H. Canonical ordering — a total order on hypothesis identity

Output order, residual-allocation tie-breaks, and the freeze vector are all governed by one
**total order** on `DiagnosticHypothesis`, independent of any collection or database order:

1. `relationshipType`, by the frozen `HypothesisDrivenProbeDiagnosticSelector.RELATIONSHIP_TYPE_PRIORITY`
   index — `ROOT_CAUSE_PROBE (0) < CONTRADICTION_CHECK (1) < PREREQUISITE_VALIDATION (2) <
   SAME_OBJECTIVE_CONFIRMATION (3)` (reused, not redefined);
2. then `targetObjectiveId`, by lowercase canonical UUID string, ascending (byte-for-byte the
   PostgreSQL `uuid` order);
3. then `authorizingRelationshipId`: `null` sorts before any non-null, otherwise lowercase canonical
   UUID string ascending (only `ROOT_CAUSE_PROBE` / `CONTRADICTION_CHECK` carry one);
4. then `triggerObjectiveId`, lowercase canonical UUID string ascending;
5. then `triggerItemVersionId`, lowercase canonical UUID string ascending.

Keys 1–5 are `DiagnosticHypothesis`'s five identity fields; no two distinct hypotheses compare
equal, so the order is total. No `HashSet` / `HashMap` / SQL row order can influence any output
(the discipline M2-ADR-024 §5 / M2-ADR-025 §4 already enforce).

### I. Exact decimal contract

- All arithmetic on `java.math.BigDecimal`. Comparisons use `compareTo`, never `equals`.
- **`scale = 4`** for every emitted value; representation of zero is `0.0000`, of one is `1.0000`;
  serialization is `toPlainString()` at scale 4.
- **`exact(h) = BigDecimal.valueOf(w(h)).divide(BigDecimal.valueOf(total), 20, RoundingMode.HALF_EVEN)`**
  — intermediate scale 20, `HALF_EVEN`. (`w(h)` in {1,2,3}, `total` in [1, 3n]; 20 digits is far
  beyond what any tie-break needs, and fixing it makes the intermediate reproducible.)
- **`floor4(h) = exact(h).setScale(4, RoundingMode.DOWN)`** (truncate toward zero to 4 dp).
- **`allocated = sum of floor4(h)`** over participating `h` (scale 4).
- **`deficit = 1.0000 - allocated`** — a non-negative multiple of `0.0001`;
  `D = deficit.movePointRight(4).intValueExact()` with `0 <= D < n` where `n = |participating|`
  (proof: each `floor4` discards `< 0.0001` and `sum of exact = 1` in the reals, so
  `allocated > 1 - n*0.0001`).
- **Residual allocation — deterministic largest-remainder (Hamilton).** `remainder(h) = exact(h) -
  floor4(h)` (scale 20, non-negative). Sort participating `h` by `remainder(h)` **descending**, ties
  broken by the §H canonical order **ascending**. Add exactly `new BigDecimal("0.0001")` to the
  first `D` candidates in that sorted order.
- **`normalizedValue(h)`** is the resulting scale-4 value for a participating `h`; **`null`** (not
  `0.0000`) for a non-participating `h` and for every `h` when `status != APPLICABLE`.
- **Invariant.** When `status == APPLICABLE`, `sum of normalizedValue(h)` over participating `h`
  `.compareTo(new BigDecimal("1.0000")) == 0`, exactly.
- **Zero denominator is unreachable in the division.** `total == 0` iff `participating` is empty iff
  `status` in `{ INSUFFICIENT_EVIDENCE, NOT_APPLICABLE }`, in which case no `exact(h)` is computed.
- Rationale for largest-remainder over "add the residual to the first candidate": the latter would
  make the canonical order part of the *cardinal* contract (the first hypothesis would be
  systematically inflated); largest-remainder minimizes total rounding distortion and uses canonical
  order only as a tie-break.

### J. Golden vectors

Eight normative vectors. Synthetic hypotheses `Ha, Hb, Hc` are given in §H canonical order
(all `ROOT_CAUSE_PROBE`, ascending `targetObjectiveId`). Evidence is written `(s, c, i)` =
`(supportingCount, contradictoryCount, inconclusiveCount)` for that hypothesis's tuple **in this
interaction**. Expected output lists `hypothesis -> band / participates / normalizedValue`.

| # | Case | Input | Expected `status` | Expected per-candidate output |
|---|---|---|---|---|
| 1 | Single candidate | `Ha (3,0,0)` | `APPLICABLE` | `Ha -> HIGH / true / 1.0000` |
| 2 | Two equal candidates | `Ha (2,0,0)`, `Hb (2,0,0)` | `APPLICABLE` | `Ha -> MODERATE / true / 0.5000`; `Hb -> MODERATE / true / 0.5000` |
| 3 | `HIGH` vs `LOW` | `Ha (4,0,0)`, `Hb (1,0,0)` | `APPLICABLE` | `Ha -> HIGH / true / 0.7500`; `Hb -> LOW / true / 0.2500` |
| 4 | `HIGH` vs `INSUFFICIENT_EVIDENCE` | `Ha (5,0,0)`, `Hb (0,0,0)` | `APPLICABLE` | `Ha -> HIGH / true / 1.0000`; `Hb -> INSUFFICIENT_EVIDENCE / false / null` |
| 5 | All `INSUFFICIENT_EVIDENCE` | `Ha (0,0,0)`, `Hb (0,0,0)`, `Hc (0,0,0)` | `INSUFFICIENT_EVIDENCE` | each -> `INSUFFICIENT_EVIDENCE / false / null` |
| 6 | All `INCONCLUSIVE` evidence | `Ha (0,0,4)`, `Hb (0,0,2)` | `INSUFFICIENT_EVIDENCE` | each -> `INSUFFICIENT_EVIDENCE / false / null` (identical to #5 — `INCONCLUSIVE` never participates; the count is echoed for audit and changes nothing) |
| 7 | Rounding / residual, 3 candidates | `Ha (3,0,0)`, `Hb (3,0,0)`, `Hc (1,0,0)` | `APPLICABLE` | weights `3,3,1`; `total 7`; `exact` `0.428571…, 0.428571…, 0.142857…`; `floor4` `0.4285, 0.4285, 0.1428`; `allocated 0.9998`; `deficit 0.0002` (`D=2`); remainders `0.00007143, 0.00007143, 0.00005714` -> +`0.0001` to `Ha, Hb` (tie broken by canonical order) -> `Ha -> HIGH / true / 0.4286`; `Hb -> HIGH / true / 0.4286`; `Hc -> LOW / true / 0.1428` (sum `1.0000`) |
| 8 | Input order permuted, identical output | #7's candidates supplied in any order (e.g. `Hc, Hb, Ha`) | `APPLICABLE` | byte-identical to #7, candidates emitted in §H canonical order `Ha, Hb, Hc` |

Worked answers for the four decision cases §K.4 enumerates:

- **Case A** (no candidate has directional evidence in the interaction) — identical to vectors 5/6:
  `status = INSUFFICIENT_EVIDENCE`, no distribution.
- **Case B** (some candidates have directional evidence, some do not) — identical to vector 4:
  `status = APPLICABLE`; distribution over the evidenced subset; the others `null`.
- **Case C** (all evidence `INCONCLUSIVE`) — identical to vector 6: `status = INSUFFICIENT_EVIDENCE`
  (`INCONCLUSIVE` contributes to neither count, per frozen `DiagnosticConfidenceCalculatorV1`).
- **Case D** (every candidate strongly contradicted, e.g. `Ha (1,3,0)`, `Hb (0,2,0)`) — both bands
  resolve to `LOW` (neither `s > 3c` nor `s - c >= 3`), both participate with weight `1`, so
  `status = APPLICABLE` with a **uniform** `0.5000 / 0.5000` distribution. **V1 expresses relative
  remaining plausibility among weakly-supported hypotheses; it does not model "all hypotheses
  refuted" as a distinct state.** The per-candidate `band` field (all `LOW`) carries that signal to
  a consumer. An absolute-refutation state would require a tuned "how contradicted is refuted"
  threshold this construct deliberately does not introduce — see Revisit triggers.

### K. Output contract

```
HypothesisUncertaintyResult:
  engineVersion : "HYPOTHESIS_UNCERTAINTY_V1"
  status        : HypothesisUncertaintyStatus            // APPLICABLE | INSUFFICIENT_EVIDENCE | NOT_APPLICABLE
  candidates    : List<CandidateUncertainty>             // canonical order (H); empty iff NOT_APPLICABLE

CandidateUncertainty:
  hypothesis      : DiagnosticHypothesis                 // the existing record — identity only
  band            : DiagnosticConfidenceBand             // from DiagnosticConfidenceCalculatorV1, unmodified
  participates    : boolean                              // band != INSUFFICIENT_EVIDENCE
  normalizedValue : BigDecimal | null                    // scale 4; null iff !participates or status != APPLICABLE
```

1. The result contains **no** raw learner answer, prompt text, model rationale, or chain-of-thought
   — governed identifiers, bands, and the normalized decimal only (M2-ADR-023 §2, this ADR §5).
2. `NOT_APPLICABLE` returns `candidates = []` — never an ambiguous empty object.
3. `INSUFFICIENT_EVIDENCE` returns every candidate with `participates = false`,
   `normalizedValue = null`.
4. The status of each of these cases is fixed by this amendment, not left to implementation
   judgement: **A** no participating candidate -> `INSUFFICIENT_EVIDENCE`; **B** mixed ->
   `APPLICABLE` over the participating subset; **C** all `INCONCLUSIVE` -> `INSUFFICIENT_EVIDENCE`;
   **D** all contradicted-to-`LOW` -> `APPLICABLE`, uniform (§J).

### L. Invariants (implementation test obligations)

Valid to assert (the construct guarantees them):

- determinism: identical input -> identical `HypothesisUncertaintyResult` (byte-identical
  serialization);
- input-order independence: permuting the input candidate list, or the evidence list, does not
  change any `hypothesis -> (band, participates, normalizedValue)` mapping or the emitted order;
- range: every `normalizedValue` is `>= 0.0000` and `<= 1.0000`;
- normalization: when `status == APPLICABLE`, the participating `normalizedValue`s sum to exactly
  `1.0000` (`compareTo`);
- band monotonicity of weight: a higher `band` (`LOW < MODERATE < HIGH`) never yields a **smaller**
  `rawWeight`, and — holding the rest of the participating set fixed — never a strictly smaller
  `normalizedValue`;
- reuse fidelity: for every candidate, `band` equals
  `DiagnosticConfidenceCalculatorV1.compute(new DiagnosticConfidenceInputs(s, c, i)).band()`.

**Not** to be asserted (the construct does not guarantee them): any probabilistic calibration;
that `normalizedValue` estimates `P(hypothesis is the true cause)`; cross-interaction stability;
that adding `SUPPORTING` evidence strictly increases a candidate's `normalizedValue` (it can leave
the band, hence the weight, unchanged — monotonic non-decreasing only).

### M. Staged plan (this ADR now distinguishes three independent steps)

| Step | Construct | Authorization |
|---|---|---|
| **Step 1** | `HYPOTHESIS_UNCERTAINTY_V1` — the frozen construct above | **Authorized by this amendment.** Inert: a pure calculator + a context assembler + `EngineVersionFreezeTests` vector + golden vectors. **No** migration, **no** contract change, **no** `SelectionReason`, **no** runtime wiring. No `DIAGNOSTIC_SELECTION_V1`–`V5` code changes. |
| **Step 2** | `INFORMATION_GAIN_V1` — per-probe deterministic score consuming Step 1's distribution verbatim (§3) | **Design only.** Its own separately reviewed design PR. |
| **Step 3** | `DIAGNOSTIC_SELECTION_V6` — replaces only `V5`'s step-(b) tiebreak with an information-gain ranking (§2) | **Design only.** Its own separately reviewed design PR; a further ADR only if it introduces a decision not already governed. |

After Step 1:

```
governed probe evidence (this interaction)
        |
        v
HYPOTHESIS_UNCERTAINTY_V1              (frozen; inert)
        |
        v
HypothesisUncertaintyResult
        |
        X   <- no INFORMATION_GAIN_V1
        X   <- no DIAGNOSTIC_SELECTION_V6
        X   <- DIAGNOSTIC_SELECTION_V1-V5 unchanged, do not read it
```

### N. AI boundary (restated, unchanged)

An LLM may propose diagnostic *content* only through the already-governed M2-ADR-032 advisory
boundary (<= 1 bounded candidate per interaction, admitted or rejected by Java's deterministic
fail-closed gate). An LLM **must never** supply a hypothesis-uncertainty value, a normalized weight,
a probability, an information-gain score, a ranking, or a selected probe; any such model-supplied
field is non-authoritative and rejected under existing governance (M2-ADR-023 §2, this ADR §5).
`HYPOTHESIS_UNCERTAINTY_V1` runs entirely in deterministic Java, reproducible from the persisted
governed evidence of one interaction.

### O. ADR diff summary (this amendment)

- **Header** — `Status` line gains `Amended — 2026-09-10` pointing here; the "authorizes no code"
  bullet becomes a scoped "Scope (as amended)" bullet that authorizes the inert
  `HYPOTHESIS_UNCERTAINTY_V1` foundation only and re-states that `V6` / `INFORMATION_GAIN_V1` /
  migrations / contracts / `SelectionReason` / `V1`-`V5` remain untouched.
- **§3** — one bullet added: `INFORMATION_GAIN_V1` consumes and never recomputes the frozen
  `HYPOTHESIS_UNCERTAINTY_V1` distribution; different uncertainty semantics require a new version.
- **§4** — an "Amended 2026-09-10" note added at the top pointing here; the original design-only
  bullets are unchanged and remain binding as framing.
- **Consequences** — two bullets added: the `EngineVersionFreezeTests` + golden-vector + inertness
  obligation for `HYPOTHESIS_UNCERTAINTY_V1`, and the no-M2-ADR-033-graph / no-H7 boundary.
- **New `## Amendment 1`** section (this one): §A name/nature, §B reuse analysis, §C weight mapping
  with rejected alternatives, §D status model, §E candidate-set authority, §F evidence boundary,
  §G graph boundary, §H canonical ordering, §I decimal contract + largest-remainder residual, §J
  eight golden vectors + the four decision cases, §K output contract, §L invariants, §M staged
  plan, §N AI boundary, §O this summary, §P a revisit trigger.
- **No change** to §1, §2, §5, §6, §7, or Alternatives rejected.
- **Companion doc edits (same PR):** `docs/adr/M2-ADR-register.md` row and note updated to reflect
  the ratified Step-1 construct; `docs/architecture/target-intelligence-loop.md` stage 7 updated to
  `HYPOTHESIS_UNCERTAINTY_V1` foundation ratified (implementation pending) with the adaptive use and
  stage 8 still `DESIGNED`.

### P. Revisit trigger added by this amendment

- If a concrete requirement emerges to distinguish *"every candidate hypothesis is actively
  refuted"* from *"relative plausibility among weak hypotheses"* (§J Case D), that needs an
  absolute-threshold decision — a `HYPOTHESIS_UNCERTAINTY_V2` or a companion construct with its own
  ADR step, not a silent change to V1's weights or status model.
