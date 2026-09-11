# M2-ADR-034: Diagnostic probe selection (`DIAGNOSTIC_SELECTION_V6` / `HYPOTHESIS_DISCRIMINATION_V1`, formerly proposed as "`INFORMATION_GAIN_V1`") — Steps 1–3 implemented

- **Status:** Proposed. **Amended — 2026-09-10** — see
  [Amendment 1](#amendment-1--hypothesis_uncertainty_v1-2026-09-10): ratifies the deterministic
  hypothesis-uncertainty construct §4 originally left to "the design PR", names it
  `HYPOTHESIS_UNCERTAINTY_V1`, freezes its complete mathematics and golden vectors, and authorizes
  its inert implementation as Step 1 (implemented 2026-09-11). **Amended again — 2026-09-11** — see
  [Amendment 2](#amendment-2--hypothesis_discrimination_v1-2026-09-11): ratifies the Step-2
  per-probe scoring construct this ADR's §3 called `INFORMATION_GAIN_V1`, finds true *expected*
  information gain undefensible from existing RAMALS semantics (no outcome-probability model
  exists or may be invented), and freezes instead a non-expectation deterministic construct named
  **`HYPOTHESIS_DISCRIMINATION_V1`** — its complete mathematics and golden vectors — authorizing its
  inert implementation as **Step 2** (implemented 2026-09-11). **Amended a third time — 2026-09-11**
  — see [Amendment 3](#amendment-3--diagnostic_selection_v6-runtime-semantics-freeze-2026-09-11):
  freezes the runtime semantics a `DIAGNOSTIC_SELECTION_V6` implementation must satisfy — which
  interaction supplies Step-1 evidence, the two independent V5 "collapses" and which one V6 replaces,
  bounded multi-hypothesis enumeration (ratified, not assumed — Amendment 2 §H's own theorem makes it
  mathematically necessary), activation and fallback rules, and twelve normative behavioral
  scenarios. Amendment 3's own text authorized no `DIAGNOSTIC_SELECTION_V6` code, migration, or
  runtime change at ratification. **Step 3 implemented — 2026-09-11** — `DIAGNOSTIC_SELECTION_V6`
  has since been implemented, in a separate PR, exactly to Amendment 3's frozen runtime semantics —
  no migration, and Amendment 1–3's own frozen mathematics/text are unchanged. **Amended a fourth
  time — 2026-09-11** — see
  [Amendment 4](#amendment-4--diagnostic_selection_v6-replayprovenance-correction-2026-09-11):
  corrects Amendment 3 §V's replay/reproducibility conclusion, which the same implementation-review
  round found does not hold under concurrent PostgreSQL transactions (§V's own ratified text is left
  historically intact, not rewritten); freezes that exact historical replay requires persisting
  `V6`'s decision-time source-attempt identity, actionable-hypothesis, and candidate-probe working
  set — never reconstructing any of them from `created_at` or from re-running a time-sensitive "most
  recent completed attempt" lookup; and freezes the resulting persisted-provenance design. Amendment
  4 authorizes **no** `DIAGNOSTIC_SELECTION_V6` code, migration, or runtime change — it is itself
  design-only, same as Amendments 1–3 were before their own implementation steps.
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
- **Scope (as amended 2026-09-11).** The original ADR authorized *design only*.
  [Amendment 1](#amendment-1--hypothesis_uncertainty_v1-2026-09-10) authorized implementation of the
  **inert `HYPOTHESIS_UNCERTAINTY_V1` foundation construct** (§4 as frozen there; **Step 1**,
  implemented). [Amendment 2](#amendment-2--hypothesis_discrimination_v1-2026-09-11) additionally
  authorizes implementation of the **inert `HYPOTHESIS_DISCRIMINATION_V1` scoring construct only**
  (§3 as frozen there; **Step 2**, implemented) — the identifier this ADR's §3 originally anticipated as
  `INFORMATION_GAIN_V1`; Amendment 2 explains why that name does not survive analysis.
  [Amendment 3](#amendment-3--diagnostic_selection_v6-runtime-semantics-freeze-2026-09-11) freezes
  the runtime semantics **Step 3** (`DIAGNOSTIC_SELECTION_V6`) must satisfy once implemented — which
  interaction supplies Step-1 evidence, bounded multi-hypothesis enumeration, activation, and
  fallback — but its own text **authorized no implementation of it** at ratification.
  **`DIAGNOSTIC_SELECTION_V6` has since been implemented (2026-09-11)**, in a separate PR, exactly
  to Amendment 3's frozen runtime semantics: no migration, no contract change, and no
  `SelectionReason` value were needed.
  `DIAGNOSTIC_SELECTION_V1`–`V5`, their composition order, `MAX_HYPOTHESIS_PROBES_PER_PACKET`,
  `core.diagnostic_probe_relationship` / `core.diagnostic_probe_provenance`, and every existing
  frozen calculator are untouched; each of Amendments 1–2 adds exactly one new frozen vector to
  `EngineVersionFreezeTests` and changes no existing one, Amendment 3 adds none (it authorizes no
  code), and no runtime selector consumes either construct.

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

> **Amended 2026-09-11 (Amendment 2) — two clauses in this original sketch are corrected, not just
> renamed.** (1) *"expected information gain per candidate (`INFORMATION_GAIN_V1`, frozen)"* —
> [Amendment 2 §A–§D](#amendment-2--hypothesis_discrimination_v1-2026-09-11) found no defensible
> outcome-probability model exists, rejected the expectation form, and froze a non-expectation
> deterministic discrimination score named **`HYPOTHESIS_DISCRIMINATION_V1`** instead. (2)
> *"candidate probes (from existing selection machinery + M2-ADR-032 advisory input, if any)"* —
> [Amendment 2 §E](#e-candidate-probe-authority-and-representation) restricts Step-2 candidate-probe
> eligibility to the existing deterministic V5/H4b resolution machinery **only**. Gate-acceptance
> under M2-ADR-032 keeps an advisory proposal within its own advisory/audit boundary; it does **not**
> by itself admit that proposal into `HYPOTHESIS_DISCRIMINATION_V1`'s candidate set. Promoting an
> accepted proposal into scoring/selection eligibility would be a new decision requiring its own
> separate, explicit ADR — this amendment authorizes no such widening. Every other arrow in this
> sketch is unaffected.

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

> **Amended 2026-09-11 (Amendment 3) — two clauses above are corrected/superseded, precisely.**
> (1) "`V6` acts only when a bounded, well-formed hypothesis set with a computed posterior exists" —
> [Amendment 3 §J](#amendment-3--diagnostic_selection_v6-runtime-semantics-freeze-2026-09-11) now
> defines this exactly (6 numbered conditions, including a mathematically-derived requirement of at
> least two participating hypotheses). (2) the bullet above treats "step (b)" as one undifferentiated
> tiebreak — Amendment 3 §D splits this into two independent collapses (hypothesis collapse and
> probe collapse) and Amendment 3 §E/§H freeze which one `V6` may widen and how. Every other clause
> above (composition order, no `V1`–`V4` change, quota unchanged, never more than one probe) remains
> binding, unamended.

### 3. `INFORMATION_GAIN_V1` — a named, versioned, frozen, deterministic construct

> **Amended 2026-09-11.** [Amendment 2](#amendment-2--hypothesis_discrimination_v1-2026-09-11)
> resolves exactly the fork this section anticipates: analysis found **no defensible deterministic
> outcome-probability model** exists or may be invented (RAMALS has outcome *classification* via
> `HypothesisEvidenceOutcome`, never outcome *probability*). **Amendment 2 supersedes this section
> wherever it refers to:** the identifier `INFORMATION_GAIN_V1`; expected-information-gain semantics;
> Shannon entropy / KL divergence / posterior interpretation; outcome probabilities; or language
> describing Step 2 as an information-gain engine. **The sole Step-2 engine identifier is
> `HYPOTHESIS_DISCRIMINATION_V1`; no code may define an `INFORMATION_GAIN_V1` version constant.** The
> bullets below are retained as **historical framing only** — they record the design question exactly
> as originally posed, not the frozen answer — and are marked inline where superseded. Every other
> governance constraint below (reuse `HYPOTHESIS_UNCERTAINTY_V1` verbatim and never recompute it;
> deterministic, reproducible, already-authoritative inputs only; no learned/fitted/tuned values; an
> explicit documented tie-break, never SQL/row order; "evidence-acquisition value only, never a
> diagnosis") remains binding, and is exactly what [Amendment 2 §D–§K](#d-selected-construct--two-world-total-variation-distance)
> freezes for the new construct.

- **Historical — identifier superseded by Amendment 2.** Follows the `EngineVersionFreezeTests`
  discipline: a `static final String … VERSION = "INFORMATION_GAIN_V1"` identifier, a frozen
  behaviour vector, and no tunable threshold or weight that can change silently across commits
  (M2-ADR-023 §2; the `DiagnosticConfidenceCalculatorV1` precedent). **The frozen identifier is
  `HYPOTHESIS_DISCRIMINATION_V1`; no `INFORMATION_GAIN_V1` constant may ever be defined.**
- **Inputs are already-authoritative and persisted:** the diagnostic hypothesis set and its
  provenance (`DiagnosticHypothesis`, `core.diagnostic_probe_relationship`,
  `core.diagnostic_probe_provenance`); existing diagnostic/causal confidence
  (`core.diagnostic_confidence_observation`, `DiagnosticConfidenceCalculatorV1`); prerequisite-graph
  distance; evidence volume and corroborating-versus-contradictory counts (the same input family
  M2-ADR-023 §2 already enumerates); and the candidate probe pool with each probe's possible
  *deterministically scoreable* outcomes.
- **Historical — the fork below is resolved by Amendment 2 §B/§D.** RAMALS has no outcome-probability
  model, so the expectation branch this bullet describes never applies; the frozen answer is the
  non-expectation discrimination score. **A deterministic outcome model is part of the freeze — an
  expectation needs one, and it may not be learned.** An *expected*-information-gain score requires,
  for each candidate probe, the
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
- **Historical — Amendment 2 §I freezes the actual output semantics** (a bounded total-variation
  discrimination score, never an expected-information-gain value). **Output:** a deterministic
  real-valued score per candidate probe (an expected-information-gain value under the frozen outcome
  model above, or the non-expectation discrimination score), and a total order over candidates with
  ties broken by an explicit, documented, deterministic key — never by SQL row order (the discipline
  M2-ADR-024 §5 and M2-ADR-025 §4 already enforce). The score is evidence-acquisition value only. It
  is never a diagnosis, never a learner-facing number, never mastery, and never root-cause truth.
- **Historical — Amendment 2 §D freezes the two-world total-variation-distance construct.** The
  entropy / KL-divergence / posterior-variance-reduction options this bullet lists were considered
  and rejected (Amendment 2 §C). The design PR chooses and freezes the concrete method — entropy
  reduction over the hypothesis posterior under the frozen outcome model, expected KL divergence,
  expected posterior-variance reduction, or a bounded deterministic scoring rubric. The brief's
  constraint is adopted verbatim: *do not over-engineer this into an ML system prematurely.*
- **Binding — identifier corrected by Amendment 2: this is `HYPOTHESIS_DISCRIMINATION_V1`, not
  `INFORMATION_GAIN_V1`.** `INFORMATION_GAIN_V1` consumes, and never recomputes, the frozen
  hypothesis-uncertainty distribution (Amendment 1). It reads `HYPOTHESIS_UNCERTAINTY_V1`'s output
  verbatim; it may not re-derive it with different band weights, a different evidence boundary, or a
  different normalization. If the `INFORMATION_GAIN_V1` design needs different uncertainty semantics, that is a
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

> **Amended 2026-09-11 (Amendment 2) — correction, not a widening.** The second bullet above
> pre-dates the Step-2 freeze and reads as if gate acceptance promotes a proposal into the scored
> pool. It does not. [Amendment 2 §E](#e-candidate-probe-authority-and-representation) freezes the
> actual boundary: `HYPOTHESIS_DISCRIMINATION_V1`'s candidate-probe set is drawn **only** from the
> existing deterministic V5/H4b resolution machinery. An M2-ADR-032 gate acceptance keeps a proposal
> within its own advisory/audit boundary and does **not** by itself admit it into Step-2 eligibility.
> Promoting an accepted proposal into scoring or selection would require its own separate, explicit
> ADR — this ADR does not do so.

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
  vector, plus tests reproducing [Amendment 1 §J](#j-golden-vectors)'s eleven golden vectors and
  [§Q](#q-input-validation-and-context-isolation-fail-closed)'s validation reason codes, before any
  consumer exists. Its Step-1 implementation is **inert**: no `DIAGNOSTIC_SELECTION_V1`–`V5` code, no
  runtime selector, no migration, and no contract change. A PR that wires it into selection, changes
  a band weight, the normalization procedure, the residual rule, the evidence boundary, the
  de-duplication identity, or the canonical ordering without minting `HYPOTHESIS_UNCERTAINTY_V2` is a
  defect against Amendment 1.
- **(Amendment 1)** `HYPOTHESIS_UNCERTAINTY_V1` takes **no** misconception-relationship-graph
  (M2-ADR-033) input and **no** H7 longitudinal input; a PR adding either is a defect against
  Amendment 1 §F/§G.
- **(Amendment 2)** `HYPOTHESIS_DISCRIMINATION_V1` must ship with an `EngineVersionFreezeTests`
  frozen vector, plus tests reproducing [Amendment 2 §Q](#q-golden-vectors)'s eleven golden vectors
  and its validation reason codes, before any consumer exists. Its Step-2 implementation is
  **inert**: no `DIAGNOSTIC_SELECTION_V1`–`V5` or `V6` code, no runtime selector, no migration, and
  no contract change. It consumes `HYPOTHESIS_UNCERTAINTY_V1` (and the exact
  `HypothesisUncertaintyContext` that produced it) verbatim — it recomputes no band, weight,
  normalization, or candidate hypothesis mass; a `HYPOTHESIS_UNCERTAINTY_V2` is required before
  Step 2 may consume different uncertainty semantics. A PR that wires it into selection, changes the
  scoring formula, the decimal contract, the tie-break, or the candidate-probe boundary without
  minting `HYPOTHESIS_DISCRIMINATION_V2` is a defect against Amendment 2.
- **(Amendment 2)** `HYPOTHESIS_DISCRIMINATION_V1` takes **no** misconception-relationship-graph
  (M2-ADR-033) input, defines **no** edge-type weight, and discovers **no** candidate probe of its
  own (LLM, embedding, or unrestricted curriculum search) — every candidate probe is drawn **only**
  from the same deterministic, already-governed resolution `DIAGNOSTIC_SELECTION_V5` uses
  (`ProbeRelationshipResolver`/`ProbeRelationshipService`). An M2-ADR-032 proposal admitted by its own
  independent gate stays within that gate's advisory/audit boundary and is **not** eligible to enter
  `HYPOTHESIS_DISCRIMINATION_V1`'s candidate set — promoting it into scoring/selection eligibility
  requires its own separate, explicit ADR. A PR adding a new probe-discovery mechanism, a
  graph-derived weight, or admitting an M2-ADR-032 proposal into this candidate set is a defect
  against Amendment 2 §D/§E/§M.
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

Eleven normative vectors — the implementation oracle. Synthetic hypotheses `Ha, Hb, Hc` are given in
§H canonical order (all `ROOT_CAUSE_PROBE`, ascending `targetObjectiveId`). Unless a row says
otherwise, per-candidate evidence is written `(s, c, i)` =
`(supportingCount, contradictoryCount, inconclusiveCount)` — the **de-duplicated** counts for that
hypothesis's tuple **in this one interaction** (§R), already classified by `HypothesisEvidenceOutcome`
and passed to `DiagnosticConfidenceCalculatorV1.compute(...)` verbatim. Expected output lists
`hypothesis -> band / participates / normalizedValue`. Every vector is independent of live DB, wall
clock, randomness, LLM, and any external service.

| # | Case | Input | Expected `status` | Expected per-candidate output |
|---|---|---|---|---|
| 1 | No candidate | `candidates = []` | `NOT_APPLICABLE` | `candidates = []` (no distribution, not an empty-object ambiguity) |
| 2 | Single candidate | `Ha (3,0,0)` | `APPLICABLE` | `Ha -> HIGH / true / 1.0000` |
| 3 | Two equal candidates | `Ha (2,0,0)`, `Hb (2,0,0)` | `APPLICABLE` | `Ha -> MODERATE / true / 0.5000`; `Hb -> MODERATE / true / 0.5000` |
| 4 | `HIGH` vs `LOW` | `Ha (4,0,0)`, `Hb (1,0,0)` | `APPLICABLE` | `Ha -> HIGH / true / 0.7500`; `Hb -> LOW / true / 0.2500` |
| 5 | `HIGH` vs `INSUFFICIENT_EVIDENCE` | `Ha (5,0,0)`, `Hb (0,0,0)` | `APPLICABLE` | `Ha -> HIGH / true / 1.0000`; `Hb -> INSUFFICIENT_EVIDENCE / false / null` (**not** `0.9xxx / 0.0xxx`) |
| 6 | All `INSUFFICIENT_EVIDENCE` | `Ha (0,0,0)`, `Hb (0,0,0)`, `Hc (0,0,0)` | `INSUFFICIENT_EVIDENCE` | each -> `INSUFFICIENT_EVIDENCE / false / null` |
| 7 | All `INCONCLUSIVE` evidence | `Ha (0,0,4)`, `Hb (0,0,2)` | `INSUFFICIENT_EVIDENCE` | each -> `INSUFFICIENT_EVIDENCE / false / null` (identical to #6 — `INCONCLUSIVE` never participates; the count is echoed for audit and changes nothing) |
| 8 | Rounding / residual, 3 candidates | `Ha (3,0,0)`, `Hb (3,0,0)`, `Hc (1,0,0)` | `APPLICABLE` | weights `3,3,1`; `total 7`; `exact` `0.428571…, 0.428571…, 0.142857…`; `floor4` `0.4285, 0.4285, 0.1428`; `allocated 0.9998`; `deficit 0.0002` (`D=2`); remainders `0.00007143, 0.00007143, 0.00005714` -> +`0.0001` to `Ha, Hb` (tie broken by canonical order) -> `Ha -> HIGH / true / 0.4286`; `Hb -> HIGH / true / 0.4286`; `Hc -> LOW / true / 0.1428` (sum `1.0000`) |
| 9 | Input order permuted, identical output | #8's candidates and each candidate's evidence list supplied in any order (e.g. `Hc, Hb, Ha`) | `APPLICABLE` | byte-identical to #8, candidates emitted in §H canonical order `Ha, Hb, Hc` |
| 10 | Duplicate evidence de-duplicated (§R) | `Ha` raw observation list `[obs-1: SUPPORTING, obs-2: SUPPORTING, obs-1: SUPPORTING]` (one governed observation id reaching the assembler twice) | `APPLICABLE` | de-dup by observation id -> distinct set `{obs-1, obs-2}` -> `(s,c,i) = (2,0,0)` -> `Ha -> MODERATE / true / 1.0000`. (Without §R de-dup the count would be `s=3` -> `HIGH` — a wrong band; the vector pins the de-dup.) |
| 11 | Mixed sufficient / insufficient, 3 candidates | `Ha (4,0,0)`, `Hb (1,0,0)`, `Hc (0,0,0)` | `APPLICABLE` | `Ha -> HIGH / true / 0.7500`; `Hb -> LOW / true / 0.2500`; `Hc -> INSUFFICIENT_EVIDENCE / false / null` (distribution is over `{Ha, Hb}` only; `Hc` is represented but unscored) |

Worked answers for the decision cases §K.4 enumerates:

- **Case A — no candidate hypotheses** — vector 1: `status = NOT_APPLICABLE`, `candidates = []`, no
  distribution.
- **Case B — candidates exist but none has participating (directional) evidence** — vectors 6/7:
  `status = INSUFFICIENT_EVIDENCE`, every candidate `participates = false` / `normalizedValue =
  null`, no distribution. Not a uniform prior — V1 manufactures no belief from absent evidence.
- **Case C — all evidence `INCONCLUSIVE`** — vector 7: identical to Case B
  (`INCONCLUSIVE` contributes to neither count, per frozen `DiagnosticConfidenceCalculatorV1`).
- **Case D — some candidates have sufficient evidence, others do not** — vectors 5 and 11:
  `status = APPLICABLE`; the distribution is normalized **over the participating subset only**; a
  non-participating candidate is **represented but unscored** (`participates = false`,
  `normalizedValue = null`) — it receives neither zero mass nor prior mass, and it does not make the
  whole result insufficient.
- **Case E — every candidate strongly contradicted** (e.g. `Ha (1,3,0)`, `Hb (0,2,0)`) — both bands
  resolve to `LOW` (neither `s > 3c` nor `s - c >= 3`), both participate with weight `1`, so
  `status = APPLICABLE` with a **uniform** `0.5000 / 0.5000` distribution. **V1 expresses relative
  remaining plausibility among weakly-supported hypotheses; it does not model "all hypotheses
  refuted" as a distinct state.** The per-candidate `band` field (all `LOW`) carries that signal to
  a consumer. An absolute-refutation state would require a tuned "how contradicted is refuted"
  threshold this construct deliberately does not introduce — see §P.

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
   judgement (§J worked answers): **A** no candidate hypotheses -> `NOT_APPLICABLE`; **B** candidates
   exist but none has directional evidence -> `INSUFFICIENT_EVIDENCE`; **C** all `INCONCLUSIVE` ->
   `INSUFFICIENT_EVIDENCE`; **D** some candidates sufficient, some not -> `APPLICABLE` over the
   participating subset, non-participating candidates represented-but-unscored (`null`, never zero
   mass and never prior mass); **E** every candidate contradicted-to-`LOW` -> `APPLICABLE`, uniform
   over the participating set. A non-participating candidate never causes the whole result to be
   insufficient — only *zero* participating candidates does that.

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

> **Amended 2026-09-11 (Amendment 2):** Step 2's mathematics are now frozen as
> `HYPOTHESIS_DISCRIMINATION_V1`, not `INFORMATION_GAIN_V1` — see
> [Amendment 2](#amendment-2--hypothesis_discrimination_v1-2026-09-11). Step 2 is **implemented
> (2026-09-11), inert**; it stays out of this Step-1 amendment's authorization above, which remains
> scoped to Step 1 only.
>
> **Amended 2026-09-11 (Amendment 3, plus implementation):** Step 3's runtime semantics are frozen
> by [Amendment 3](#amendment-3--diagnostic_selection_v6-runtime-semantics-freeze-2026-09-11), and
> `DIAGNOSTIC_SELECTION_V6` is now **implemented (2026-09-11)**, exactly to that frozen
> specification, in a separate PR.

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
  eleven golden vectors + the five decision cases, §K output contract, §L invariants, §M staged
  plan, §N AI boundary, §O this summary, §P a revisit trigger, §Q input validation + context
  isolation (fail closed), §R evidence identity + de-duplication.
- **No change** to §1, §2, §5, §6, §7, or Alternatives rejected.
- **Companion doc edits (same PR):** `docs/adr/M2-ADR-register.md` row and note updated to reflect
  the ratified Step-1 construct; `docs/architecture/target-intelligence-loop.md` stage 7 updated to
  `HYPOTHESIS_UNCERTAINTY_V1` foundation ratified (implementation pending) with the adaptive use and
  stage 8 still `DESIGNED`.

### P. Revisit trigger added by this amendment

- If a concrete requirement emerges to distinguish *"every candidate hypothesis is actively
  refuted"* from *"relative plausibility among weak hypotheses"* (§J Case E), that needs an
  absolute-threshold decision — a `HYPOTHESIS_UNCERTAINTY_V2` or a companion construct with its own
  ADR step, not a silent change to V1's weights or status model.

### Q. Input validation and context isolation (fail closed)

`HYPOTHESIS_UNCERTAINTY_V1`'s `calculate(...)` is a pure function of an already-assembled
`HypothesisUncertaintyContext`. It performs **deterministic input validation** and **never repairs
malformed authoritative diagnostic data** (M2-ADR-023 §2; the M2-ADR-032 fail-closed discipline).
Each check raises a stable typed reason; none is silently normalized. A *valid empty state*
(`NOT_APPLICABLE` / `INSUFFICIENT_EVIDENCE`, §D) is categorically distinct from an *invalid context*.

Validated, with the reason code the Step-1 implementation must use:

| Condition | Reason code | Behaviour |
|---|---|---|
| The same hypothesis identity (§H key) appears twice in the candidate set | `DUPLICATE_HYPOTHESIS` | reject |
| An evidence input references a hypothesis not in the candidate set | `EVIDENCE_FOR_UNKNOWN_HYPOTHESIS` | reject |
| A negative `supportingCount` / `contradictoryCount` / `inconclusiveCount` | `NEGATIVE_EVIDENCE_COUNT` | reject (also enforced by `DiagnosticConfidenceInputs`' own constructor) |
| A context `interactionId` that does not match the interaction a candidate's evidence was drawn from | `EVIDENCE_INTERACTION_MISMATCH` | reject |
| Two candidates whose objectives resolve to **different curriculum domains** | `CROSS_DOMAIN_CANDIDATE_SET` | reject |
| An evidence observation whose owning domain differs from its hypothesis tuple's domain | `CROSS_DOMAIN_EVIDENCE` | reject |
| A repeated governed observation id within one hypothesis tuple's evidence list (see §R) | `DUPLICATE_EVIDENCE_OBSERVATION` | reject |
| An empty / null hypothesis identity field required by the §H order | `MALFORMED_HYPOTHESIS_IDENTITY` | reject |

**Context isolation is normative, not advisory.** `HYPOTHESIS_UNCERTAINTY_V1` may never combine a
Kafka-domain hypothesis with unrelated-domain evidence, and — because V1 is interaction-bound (§F) —
may never combine evidence from two diagnostic interactions. The candidate set and every evidence
observation in one `calculate(...)` call belong to **one learner, one diagnostic interaction, one
curriculum domain**; the assembler establishes that boundary and the calculator re-checks it and
fails closed. A hypothesis's domain is resolved from its objectives' authoritative curriculum
context, never inferred.

### R. Evidence identity and de-duplication

The authoritative identity of a governed evidence observation is its **observation id** — the
primary key of the persisted row the diagnostic-evidence pipeline writes, the same identity H5's
`DiagnosticConfidenceService` already counts "distinct evidence observations" by. It is **not** the
`(hypothesis, outcome)` pair and **not** the probe item id.

The assembler reduces each hypothesis tuple's evidence, for this interaction, to the **set of
distinct observation ids**, then classifies each once via `HypothesisEvidenceOutcome` into the
`(supportingCount, contradictoryCount, inconclusiveCount)` triple the calculator consumes. One
observation reaching the assembler through more than one projection (e.g. an H5 read *and* a
probe-provenance read) is **deterministically de-duplicated by observation id** and influences a
hypothesis exactly once (golden vector 10). Counting it twice because it arrived through two
projections is a defect. The calculator does no de-duplication of its own, but §Q's
`DUPLICATE_EVIDENCE_OBSERVATION` check rejects a context whose evidence list still carries a
repeated observation id, so a mis-assembled context fails closed rather than double-counting.

## Amendment 2 — `HYPOTHESIS_DISCRIMINATION_V1` (2026-09-11)

§3 anticipated a named, versioned, frozen `INFORMATION_GAIN_V1` construct and explicitly permitted
two possible shapes: a true expected-information-gain score under a frozen deterministic outcome
model, or, failing that, "a non-expectation deterministic discrimination score." This amendment
resolves the fork: analysis found that RAMALS has deterministic outcome *classification*
(`HypothesisEvidenceOutcome`) but no deterministic outcome *probability*, and none may be invented
(M2-ADR-023 §2). True expected information gain is therefore not defensible. This amendment freezes
the non-expectation alternative instead, names it accurately, and authorizes its inert
implementation as **Step 2** of the three-step plan Amendment 1 §M introduced. It changes no other
section's constraints and does not touch `HYPOTHESIS_UNCERTAINTY_V1` (Step 1, already implemented)
or `DIAGNOSTIC_SELECTION_V6` (Step 3, still design-only).

### A. Name and nature — not information gain

The construct is **`HYPOTHESIS_DISCRIMINATION_V1`**, not `INFORMATION_GAIN_V1`. It defines no prior,
no likelihood, no outcome probability, and is not an expectation over anything — "information gain"
would misrepresent its mathematical content, the same discipline that named
`HYPOTHESIS_UNCERTAINTY_V1` rather than `HYPOTHESIS_POSTERIOR_V1` (Amendment 1 §A). It answers
exactly one question — *"across this probe's deterministically reachable outcomes, how differently
would the hypothesis-uncertainty distribution end up?"* — and nothing about which outcome is likely,
what the learner will actually answer, or which hypothesis is true.

The engine version identifier is the string `HYPOTHESIS_DISCRIMINATION_V1`, frozen under the
`EngineVersionFreezeTests` discipline exactly as `HYPOTHESIS_UNCERTAINTY_V1` is.

### B. The outcome-model problem — classification exists, probability does not

`HypothesisEvidenceOutcome.classify(itemType, isCorrect)` is deterministic and hypothesis-agnostic:
non-scoreable → `INCONCLUSIVE`; scoreable + incorrect → `SUPPORTING`; scoreable + correct →
`CONTRADICTORY`. This is outcome **classification**: given a response, it deterministically says
what kind of evidence it is. It is not, and cannot be read as, outcome **probability**: nothing in
RAMALS states or calibrates `P(SUPPORTING | hypothesis, probe)` for any hypothesis or probe, and
none may be authored, learned, fitted, or LLM-generated (M2-ADR-023 §2's existing, unmodified
prohibition). Without `P(outcome | hypothesis, probe)`, the classical expected-information-gain
formula

```
Expected(P) = uncertainty(now) - Σ_outcome P(outcome) * uncertainty(after outcome)
```

has no defensible `P(outcome)` term. §7's "the design PR cannot express a defensible deterministic
outcome model" clause is therefore the operative one: this amendment does not use an expectation
form.

### C. Rejected alternatives

| Approach | Why rejected |
|---|---|
| **True expected information gain** (§B formula) | Requires `P(outcome | hypothesis, probe)`, which does not exist and may not be invented. |
| **Shannon entropy** (`H = -Σ p_i log p_i`) over the current distribution, or KL divergence between before/after distributions | Same missing-probability problem for the "after" term (still needs `P(outcome)` to weight the outcomes into a single expected reduction); additionally, `Math.log(double)` risks cross-JVM floating-point reproducibility RAMALS's `BigDecimal`-first convention exists to avoid, for no benefit once probabilities don't exist. Not selected. |
| **Posterior-variance reduction** | "Posterior" language Amendment 1 §A already rejected for the very same reason (no prior/likelihood/update exists); would misname the construct the same way. |
| **Graph-derived weighting** (M2-ADR-033 edge types as numeric contributions) | Authored curriculum structure is not automatically numeric evidence (Amendment 1 §G's boundary, reaffirmed here); would be new algorithmic policy with no separate ratification. |

### D. Selected construct — two-world total variation distance

Each candidate probe has a small, **deterministically enumerable set of reachable outcome worlds**
(never a probability distribution over them):

- **Scoreable** probe → two reachable worlds: the world where its target hypothesis gains one more
  `SUPPORTING` observation, and the world where it gains one more `CONTRADICTORY` observation.
- **Non-scoreable** probe → exactly one reachable world: `INCONCLUSIVE`, which — per
  `DiagnosticConfidenceCalculatorV1`'s own frozen semantics — never changes any band, so this world
  is identical to the current one.

For a scoreable probe `P` targeting hypothesis `H_p` (already a member of the current bounded
candidate hypothesis set — §E/§L), construct two **hypothetical** `HypothesisUncertaintyContext`s,
each identical to the current one except for exactly one additional synthetic
`HypothesisEvidenceInput` for `H_p` (§F's exact synthetic-id rule), and call
**`HYPOTHESIS_UNCERTAINTY_V1.calculate(...)` verbatim** on each to obtain `resultSupporting` and
`resultContradictory`. Because both hypothetical contexts differ from the current one, and from each
other, only in `H_p`'s own evidence, `resultSupporting.candidates()` and
`resultContradictory.candidates()` contain **exactly the same hypotheses**, in the same canonical
order (Amendment 1 §H) — no other hypothesis's band can change, since no other hypothesis's evidence
changed.

**Score.** Let `valueSupporting(h)` / `valueContradictory(h)` be each hypothesis's own
`normalizedValue` in the two hypothetical results, treating a non-participating hypothesis's value
as exactly `0.0000` (it carries no belief mass in either world — Amendment 1 §D). Then:

```
Score(P) = ( Σ_h |valueSupporting(h) - valueContradictory(h)| ) / 2
```

— the **total variation distance** between the two hypothetical distributions: half their L1
distance. For a non-scoreable probe (one reachable world), `Score(P) = 0.0000` exactly, by
construction — there is no second world to differ from, so there is no possible variance to detect
(prompt's "probe with `INCONCLUSIVE` effect for every hypothesis" case, §Q vector 5: this is not a
special case in the formula, only a fact about how many worlds exist).

**Why this is not information gain.** Both worlds are given equal standing in this formula, but not
because they are believed equally *likely* — likelihood is exactly the quantity §B shows does not
exist. `Score(P)` measures **separability**: whether the two deterministically reachable outcomes
would leave the platform in meaningfully different relative-belief states, never which one is
expected, never learner correctness, never ground truth.

**Consequence, derived, not asserted.** Because `HYPOTHESIS_UNCERTAINTY_V1`'s weight function depends
only on *band* (`LOW=1, MODERATE=2, HIGH=3` — Amendment 1 §C), not on raw evidence counts,
`Score(P) > 0` **exactly when** `P`'s two reachable outcomes would move `H_p` into *different bands*.
Two corollaries follow directly from `DiagnosticConfidenceCalculatorV1`'s own frozen thresholds, not
from anything invented here:

- **A probe's first-ever observation on a previously unprobed hypothesis always scores `0.0000`.**
  `(s,c) = (0,0)` plus one `SUPPORTING` gives `(1,0)` → `LOW`; plus one `CONTRADICTORY` gives `(0,1)`
  → `LOW` (`s > 3c` is `0 > 3`, false; `s - c >= 3` is `-1 >= 3`, false) — the **same** band either
  way, so the same weight, so the same normalized value in both worlds.
- **Every probe targeting the sole participating hypothesis in a single-participant candidate set
  scores `0.0000`** — see §L.

### E. Candidate-probe authority and representation

`HYPOTHESIS_DISCRIMINATION_V1` **discovers no probe**. Every candidate probe originates from the
same deterministic, already-governed resolution `DIAGNOSTIC_SELECTION_V5` already uses — for each
candidate hypothesis in the input's own set, its own unseen candidate items as
`ProbeRelationshipResolver` / `ProbeRelationshipService` already resolve them (verified, scoreable-
or-not, no-repeat-exclusion already applied). No LLM, no embedding neighbour, no unrestricted graph
traversal, and no free curriculum search may add a candidate (Amendment 1 §E's discipline, restated
for probes rather than hypotheses).

**An accepted M2-ADR-032 advisory proposal does not enter this candidate set.** M2-ADR-032's gate
decides only whether a proposal is well-formed and bounded enough to remain within its own advisory
boundary — acceptance is an audit/evaluation outcome, not a promotion into deterministic probe
eligibility:

```
LLM
  |
  v
ADR-032 proposal
  |
  v
deterministic gate
  |
  v
ACCEPTED
  |
  v
audit / evaluation only
  X                            <- no path from here into HYPOTHESIS_DISCRIMINATION_V1
HYPOTHESIS_DISCRIMINATION_V1
```

`HYPOTHESIS_DISCRIMINATION_V1`'s candidate-probe pool is drawn **only** from the existing
deterministic, bounded V5/H4b candidate-resolution machinery described above. Nothing in this
amendment — no assembler, no future step — may include an accepted M2-ADR-032 proposal in that pool.
If RAMALS later wants an accepted advisory proposal promoted into selection eligibility, that is a new
decision requiring its own separate, explicit ADR; this amendment authorizes no such widening of
M2-ADR-032.

A candidate probe's authoritative shape:

```
CandidateProbe:
  probeItemVersionId : UUID              -- the item this probe would present
  hypothesis         : DiagnosticHypothesis  -- must equal one candidate in the input's own set
  scoreable          : boolean           -- from the item's own AssessmentItemType.scoreable()
```

No generic map. No probe-specific weight, priority, or authored metadata beyond this — the score is
computed entirely from the hypothetical re-evaluation (§D), not from any property stamped on the
probe itself.

### F. Input contract — consumes `HYPOTHESIS_UNCERTAINTY_V1` verbatim, never reinterpreted

```
HypothesisDiscriminationContext:
  baseContext  : HypothesisUncertaintyContext   -- the exact context Step 1 was computed from
  baseResult   : HypothesisUncertaintyResult    -- Step 1's own output from that exact context
  candidates   : List<CandidateProbe>
```

**`baseResult` must equal `HYPOTHESIS_UNCERTAINTY_V1.calculate(baseContext)` exactly** (§Q's
`BASE_RESULT_MISMATCH`) — this amendment never accepts a hand-constructed or stale result paired
with a different context. Step 2 **never recomputes a band, a weight, a normalization, or candidate
hypothesis mass on its own**: every distribution it ever produces — the baseline and both
hypothetical worlds — comes from calling `HYPOTHESIS_UNCERTAINTY_V1.calculate(...)`, unmodified. If a
future step needs different uncertainty semantics, that is `HYPOTHESIS_UNCERTAINTY_V2`, never a
reinterpretation inside `HYPOTHESIS_DISCRIMINATION_V1`.

**Synthetic evidence identity, frozen.** For a candidate probe `P` and a reachable outcome `o`, the
synthetic `HypothesisEvidenceInput` added to build a hypothetical context uses:

```
observationId = UUID.nameUUIDFromBytes(
    (probeItemVersionId.toString() + ":" + o.name()).getBytes(UTF_8))
hypothesis    = P.hypothesis()
outcome       = o
interactionId = baseContext.interactionId()
domainCode    = the domainCode baseContext's own CandidateHypothesis carries for P.hypothesis()
```

`UUID.nameUUIDFromBytes` (RFC 4122 name-based, version 3, MD5) is deterministic and reproduces
identically across JVMs. This id is never persisted and never collides with a real governed
observation id in practice (real ids are UUIDv7); it exists only so the hypothetical context can be
validated and computed by `HYPOTHESIS_UNCERTAINTY_V1` exactly as any other context would be.

### G. Status model

```
status in { SCORABLE, NOT_APPLICABLE }
```

- **`NOT_APPLICABLE`** — `baseResult.status()` is `NOT_APPLICABLE` or `INSUFFICIENT_EVIDENCE`. There
  is no participating uncertainty mass to redistribute, so "how much of the current mass would this
  probe separate" is undefined — not zero, undefined. No score is manufactured; `probes` is empty.
  This is a **derived** consequence of the score's own semantics (§I), not an arbitrary carry-over
  from Step 1's status names.
- **`SCORABLE`** — `baseResult.status()` is `APPLICABLE`. Every supplied candidate probe is scored
  (§D); zero supplied candidate probes is a valid, successful `SCORABLE` result with `probes = []`
  (the same "valid empty state" discipline Amendment 1 §D holds for `NOT_APPLICABLE`/
  `INSUFFICIENT_EVIDENCE`).

No artificial uniform distribution is ever synthesized for a `NOT_APPLICABLE` base.

### H. One-hypothesis / sole-participant handling

If the candidate hypothesis set has **exactly one participating hypothesis** in `baseResult`
(`status = APPLICABLE`, one entry with `participates = true`), every probe targeting it scores
`Score(P) = 0.0000`, provably: a sole participant's `normalizedValue` is `1.0000` in *any* world
where it is still the sole participant, regardless of its own band, because normalization divides its
weight by itself. Since adding one observation of either outcome to the sole participant never
un-participates it (any directional evidence keeps `s + c >= 1`, never `INSUFFICIENT_EVIDENCE`
again), both hypothetical worlds have it at `1.0000` — `TVD = 0`. This is **not** treated as
`NOT_APPLICABLE`: `status` stays `SCORABLE` (a genuine baseline participating mass exists — exactly
one hypothesis' worth), and the scores are correctly, provably `0.0000`, not undefined.

### I. Score semantics and range

`Score(P)` is **half the total variation distance between the two deterministically reachable
post-outcome hypothesis-uncertainty distributions** — for example, `0.6000` means *"60% of the
normalized uncertainty mass ends up allocated differently depending on which of this probe's two
reachable outcomes occurs."* It is explicitly **not**: a probability, an expectation, expected
learner correctness, a measure of diagnostic truth, or a claim about which outcome is likely.

**Range:** `0.0000 <= Score(P) <= 1.0000`, provable from the standard total-variation-distance
property (half the L1 distance between two distributions that each sum to exactly `1.0000` — the
Amendment 1 §I exact-sum invariant both hypothetical results independently satisfy — is bounded in
`[0, 1]`, with `0` iff the two distributions coincide). `0.0000` means the two reachable outcomes
would leave the distribution identical (no discriminating value). `1.0000` would mean the two
outcomes' distributions share no participating mass at all (never observed to be reachable under
the frozen `{1, 2, 3}` band-weight granularity in practice — see §Q's vectors for the range actually
achieved — but the formula's mathematical range is `[0, 1]` regardless of how much of it is
practically reachable, and no rescaling is applied merely to make the practical maximum look like
`1.0000`).

### J. Decimal contract

- `java.math.BigDecimal` throughout; comparisons via `compareTo`, never `equals`.
- `valueSupporting(h)` / `valueContradictory(h)` are already scale-4 outputs of
  `HYPOTHESIS_UNCERTAINTY_V1` (or exactly `BigDecimal.ZERO` for a non-participating hypothesis, per
  §D) — their differences and sum need no intermediate scale beyond what exact `BigDecimal` addition
  and subtraction already preserve.
- **Final division by 2** is the only rounding point: `score = sum.divide(BigDecimal.valueOf(2), 4,
  RoundingMode.HALF_EVEN)`. Unlike Amendment 1's normalization, no Hamilton allocation applies here:
  each probe's score is independent — there is no "must sum to `1.0000` across probes" invariant to
  protect, so ordinary `HALF_EVEN` rounding to scale 4 is the complete rounding contract.
- Zero is `0.0000`; one is `1.0000` (never reached in the golden vectors, but a valid representable
  value per §I).

### K. Ranking is a separate, frozen contract

`HYPOTHESIS_DISCRIMINATION_V1` returns **scores**, one per candidate probe, in no particular
priority order of its own beyond §L's canonical emission order. Ranking — "which probe should be
tried first" — is a deterministic total order over the returned scores:

```
score DESCENDING
  -> hypothesis canonical order ASCENDING (Amendment 1 §H, reused verbatim)
    -> probeItemVersionId ASCENDING (lowercase UUID string)
```

Never SQL row order, never `HashMap`/`HashSet` order, never input-list order, never UUID randomness.
This ranking is a read-only view over the scored result; `HYPOTHESIS_DISCRIMINATION_V1` does not
itself select or execute a probe (§O).

### L. Canonical emission order and cross-probe determinism

Probes are emitted in the result in this order: primary key the probe's own `hypothesis` by
Amendment 1 §H's canonical order; secondary key `probeItemVersionId` ascending (lowercase UUID
string). Supplying `candidates` in any input order, or supplying the underlying hypothesis set in
any order, produces the identical emitted list and identical scores — proven by golden vectors 8–9.

### M. No graph weighting (reaffirmed)

`MisconceptionGraphQueryService ── X ── HYPOTHESIS_DISCRIMINATION_V1`, restating Amendment 1 §G for
Step 2: no `MISCONCEPTION_RELATED` or `MISCONCEPTION_PREREQUISITE_LINK` edge is read, and no
edge-type numeric contribution (`CO_OCCURS_WITH = 0.5`, `SPECIALISES = 0.7`, `CONTRASTS_WITH = 0.9`,
or any other) is defined. Authored curriculum structure remains distinct from evidence-derived
belief state.

### N. Candidate-probe validation (fail closed)

| Condition | Reason code | Behaviour |
|---|---|---|
| `baseResult` does not equal `HYPOTHESIS_UNCERTAINTY_V1.calculate(baseContext)` | `BASE_RESULT_MISMATCH` | reject |
| A candidate probe's `hypothesis` does not match any hypothesis in `baseContext.candidates()` | `PROBE_FOR_UNKNOWN_HYPOTHESIS` | reject |
| A candidate probe is missing `probeItemVersionId` or `hypothesis` | `MALFORMED_CANDIDATE_PROBE` | reject |
| The same `(probeItemVersionId, hypothesis)` pair appears twice in `candidates` | `DUPLICATE_CANDIDATE_PROBE` | reject |
| A candidate probe's own domain (via its `hypothesis`) differs from `baseContext.domainCode()` for that hypothesis | `CROSS_DOMAIN_CANDIDATE_PROBE` | reject |

Fail-closed, exactly Amendment 1 §Q's discipline: never repaired, never silently dropped, never a
partial result. A valid empty state (`NOT_APPLICABLE`, or `SCORABLE` with `probes = []`) is never one
of these.

### O. Step separation — Step 2 is inert

```
HYPOTHESIS_UNCERTAINTY_V1
        |
        v
HYPOTHESIS_DISCRIMINATION_V1        (frozen; inert)
        |
        v
scores + deterministic ranking (§K)
        |
        X   <- DIAGNOSTIC_SELECTION_V1-V5 unchanged, do not read it
        X   <- no DIAGNOSTIC_SELECTION_V6 (Step 3, still design-only)
```

`HYPOTHESIS_DISCRIMINATION_V1` selects nothing and executes nothing. No `DIAGNOSTIC_SELECTION_V1`–
`V5` code, `SelectionReason` value, or runtime selector may consume its output under this amendment.
Wiring the ranked output into `V5`'s step-(b) tiebreak is exactly, and only, Step 3
(`DIAGNOSTIC_SELECTION_V6`), which remains design-only and is not authorized here.

### P. No persistence

`HYPOTHESIS_DISCRIMINATION_V1` is pure compute-on-read: no migration, no table, no append-only score
ledger. Its inputs (`HypothesisUncertaintyContext`, `HypothesisUncertaintyResult`, the candidate
probe list) and its engine version already make any output fully reconstructable; nothing here needs
its own persisted provenance. A future step that wires this into runtime selection may revisit
persistence for audit — this amendment does not authorize it.

### Q. Golden vectors

Eleven normative vectors — the implementation oracle. Hypotheses `Ha`, `Hb`, `Hc` are the same
canonical-order fixtures Amendment 1 §J uses (all `ROOT_CAUSE_PROBE`, ascending `targetObjectiveId`).
`(s, c)` is a hypothesis's own current `(supportingCount, contradictoryCount)` in `baseContext`
(`inconclusiveCount` omitted where `0`). A probe is written `P(hypothesis, scoreable?)`.

| # | Case | Base `(s,c)` per hypothesis | Probe | Expected `status` | Expected score |
|---|---|---|---|---|---|
| 1 | Two equal hypotheses, most-separating available probe | `Ha=(2,0)` MODERATE, `Hb=(2,0)` MODERATE (base: `Ha=0.5000, Hb=0.5000`) | `P(Ha, scoreable)` | `SCORABLE` | World-S: `Ha=(3,0)` HIGH -> `Ha=0.6000,Hb=0.4000`. World-C: `Ha=(2,1)` LOW -> `Ha=0.3333,Hb=0.6667`. **`0.2667`** |
| 2 | Two equal hypotheses, non-scoreable probe | same base as #1 | `P(Ha, non-scoreable)` | `SCORABLE` | one reachable world -> **`0.0000`** |
| 3 | Uneven base distribution | `Ha=(4,0)` HIGH, `Hb=(1,0)` LOW (base: `Ha=0.7500, Hb=0.2500`) | `P(Hb, scoreable)` | `SCORABLE` | World-S: `Hb=(2,0)` MODERATE -> `Ha=0.6000,Hb=0.4000`. World-C: `Hb=(1,1)` LOW (unchanged band) -> `Ha=0.7500,Hb=0.2500`. **`0.1500`** |
| 4 | Three hypotheses, 1-vs-2 split, probe on the "1" side | `Ha=(3,0)` HIGH, `Hb=(2,0)` MODERATE, `Hc=(1,0)` LOW (base: `Ha=0.5000, Hb=0.3333, Hc=0.1667`) | `P(Ha, scoreable)` | `SCORABLE` | World-S: `Ha=(4,0)` HIGH (unchanged band) -> same as base. World-C: `Ha=(3,1)` LOW -> weights `1,2,1` -> `Ha=0.2500,Hb=0.5000,Hc=0.2500`. **`0.2500`** |
| 5 | Probe inconclusive for its hypothesis (any base) | `Ha=(2,0)` MODERATE, `Hb=(1,0)` LOW, `Hc=(4,0)` HIGH | `P(Ha, non-scoreable)` | `SCORABLE` | one reachable world -> **`0.0000`** (not a special case -- same rule as #2) |
| 6 | One participating hypothesis only | `Ha=(3,0)` HIGH, sole candidate (base: `Ha=1.0000`) | `P(Ha, scoreable)` | `SCORABLE` | World-S: `Ha=(4,0)` HIGH, sole -> `1.0000`. World-C: `Ha=(3,1)` LOW, sole -> `1.0000`. Identical -> **`0.0000`** (§H) |
| 7 | Two probes, identical score -> deterministic tie-break | base as #1 (`Ha=Hb=`MODERATE`=0.5000`) | `P(Ha, scoreable)` and `P(Hb, scoreable)` | `SCORABLE` | both score **`0.2667`** by symmetry; ranking emits `P(Ha)` before `P(Hb)` (`Ha` precedes `Hb` in canonical order) |
| 8 | Shuffled hypothesis order -> same result | same as #4, `baseContext.candidates()` supplied as `[Hc, Hb, Ha]` | `P(Ha, scoreable)` | `SCORABLE` | identical to #4's **`0.2500`**, emitted in canonical order `Ha, Hb, Hc` regardless |
| 9 | Shuffled probe order -> same result | same as #4 | `[P(Ha), P(Hb), P(Hc)]` supplied in any permutation | `SCORABLE` | each probe's own score is unchanged by list order; emission order is always §L's canonical order, never input order |
| 10 | Insufficient uncertainty input | `Ha=(0,0)`, `Hb=(0,0)`, `Hc=(0,0)` (base `status = INSUFFICIENT_EVIDENCE`) | `P(Ha, scoreable)` | **`NOT_APPLICABLE`** | no scores computed, regardless of candidate probes supplied |
| 11 | Malformed/missing hypothesis-probe relation | base as #1 | a probe whose `hypothesis` is not `Ha` or `Hb` (any third, unrelated hypothesis) | -- | rejected: `PROBE_FOR_UNKNOWN_HYPOTHESIS` |

### R. Invariants (implementation test obligations)

Valid to assert:

- determinism: identical input -> identical result (byte-identical serialization);
- input-order independence: permuting `candidates`, or the underlying hypothesis set in
  `baseContext`, does not change any probe's score or the emitted (canonical) order;
- range: every score is `>= 0.0000` and `<= 1.0000`;
- reuse fidelity: `resultSupporting`/`resultContradictory` for every hypothetical world equal
  `HYPOTHESIS_UNCERTAINTY_V1.calculate(...)` called directly on the same hypothetical context --
  never a value computed any other way;
- single-world triviality: a non-scoreable probe's score is always exactly `0.0000`;
- sole-participant triviality: every probe targeting a single-participant candidate set's only
  participant scores exactly `0.0000` (§H).

Not to be asserted: that a higher score means a more *likely* useful probe in any probabilistic
sense; that the score predicts learner correctness; that scores across different base contexts are
comparable (each is scoped to its own interaction's own baseline mass).

### S. AI boundary (restated, and corrected — no candidate-eligibility widening)

An LLM **must never** supply an outcome probability, a hypothesis probability, an entropy value, a
discrimination score, a probe ranking, or a selected probe. The only permitted AI participation
remains the already-governed M2-ADR-032 advisory boundary — an agent may propose at most one bounded
candidate per interaction, and Java's independent, deterministic, fail-closed gate decides whether it
is accepted. **Gate acceptance keeps that proposal within its own advisory/audit boundary; it is not,
and never becomes, an input candidate probe to `HYPOTHESIS_DISCRIMINATION_V1`** (§E). Every candidate
`HYPOTHESIS_DISCRIMINATION_V1` scores is drawn solely from the existing deterministic V5/H4b
resolution. `HYPOTHESIS_DISCRIMINATION_V1` runs entirely in deterministic Java, reproducible from the
persisted governed evidence of one interaction plus that frozen candidate-probe resolution.

### T. Performance boundary

With `H` candidate hypotheses and `P` candidate probes (each targeting exactly one hypothesis), the
construct performs at most `2P` calls to `HYPOTHESIS_UNCERTAINTY_V1.calculate(...)` (one or two
hypothetical worlds per probe), each itself `O(H log H)` (canonical sort) — no database access, no
HTTP call, no LLM invocation, and no unbounded graph traversal occurs inside the scorer. `H` and `P`
are already bounded by the existing V5/H4b candidate-resolution machinery (§E); this amendment adds
no new unbounded input.

### U. ADR diff summary (this amendment)

- **Title / Header** — the document title and `Status` line corrected to name
  `HYPOTHESIS_DISCRIMINATION_V1` as the sole Step-2 identifier (`INFORMATION_GAIN_V1` kept only as
  "formerly proposed as"); the "Scope (as amended)" bullet names Step 2's authorization explicitly.
- **§1** — a superseding note added after the original target-loop sketch: (a) the diagram's
  "expected information gain (`INFORMATION_GAIN_V1`, frozen)" step is corrected to
  `HYPOTHESIS_DISCRIMINATION_V1`; (b) the diagram's "candidate probes ... + M2-ADR-032 advisory input"
  clause is corrected — Step-2 candidate eligibility never includes an M2-ADR-032 proposal.
- **§3** — the "Amended 2026-09-11" note rewritten to state precisely **what is superseded**
  (the `INFORMATION_GAIN_V1` identifier, expected-information-gain semantics, entropy/KL/posterior
  interpretation, outcome probabilities, "information-gain engine" framing) versus **what remains
  binding** (verbatim Step-1 reuse, deterministic-inputs-only, no learned values, explicit tie-break,
  evidence-acquisition-only output). The bullets below are now individually marked historical,
  binding, or corrected — no bullet is left silently contradicting Amendment 2.
- **§5** — a note added correcting the pre-existing "admitted to the pool `INFORMATION_GAIN_V1` then
  scores" framing: gate acceptance under M2-ADR-032 never by itself admits a proposal into Step-2
  candidate eligibility.
- **Consequences** — two bullets added (`EngineVersionFreezeTests` + golden-vector + inertness
  obligation; no-graph / no-new-candidate-discovery boundary); the no-new-candidate-discovery bullet
  is corrected to state explicitly that an M2-ADR-032 proposal does **not** enter
  `HYPOTHESIS_DISCRIMINATION_V1`'s candidate set.
- **New `## Amendment 2`** section (this one): §A name/nature, §B the outcome-model finding, §C
  rejected alternatives, §D the selected two-world TVD construct with its derived corollaries, §E
  candidate-probe authority and shape (corrected: M2-ADR-032 proposals excluded from eligibility, with
  the frozen boundary diagram), §F input contract (verbatim Step-1 reuse + synthetic-evidence
  formula), §G status model, §H one-hypothesis handling, §I score semantics/range, §J decimal
  contract, §K ranking/tie-break, §L canonical emission order, §M no-graph-weighting, §N validation
  reason codes, §O step separation/inertness, §P persistence decision, §Q eleven golden vectors, §R
  invariants, §S AI boundary (corrected: same M2-ADR-032 exclusion as §E), §T performance boundary,
  §U this summary, §V a revisit trigger, §W a known V1 limitation (no probe-specific psychometric
  quality).
- **No change** to §2, §4, §6–§7 (except the Amendment-1 pointer already present in §4), Alternatives
  rejected, or Amendment 1 in any way. **No change** to the TVD formula, the synthetic
  SUPPORTING/CONTRADICTORY worlds, Step-1 reuse, the `BigDecimal` contract, any golden-vector
  mathematics, the tie-break, the score range, the no-persistence decision, or the no-graph-weighting
  decision — this round of edits corrects only the M2-ADR-032 candidate-eligibility boundary, the
  stale `INFORMATION_GAIN_V1` normative language, and adds the §W limitation.
- **Companion doc edits (same PR):** `docs/adr/M2-ADR-register.md` row corrected to remove the same
  M2-ADR-032 candidate-promotion language; `docs/architecture/target-intelligence-loop.md`'s
  top-level loop diagram and stage 7–9 detail updated to `HYPOTHESIS_DISCRIMINATION_V1` throughout,
  with "expected information gain" retained only as rejected historical rationale.

### V. Revisit trigger added by this amendment

- If a genuine, non-invented outcome-probability model ever becomes available (e.g. a calibrated,
  authored item-difficulty model reviewed and ratified under its own ADR), a true
  expected-information-gain construct may be introduced as its own new version
  (`HYPOTHESIS_DISCRIMINATION_V2` or a distinctly named `INFORMATION_GAIN_V1`) — never a silent
  reinterpretation of the `HYPOTHESIS_DISCRIMINATION_V1` score frozen here.
- If RAMALS later wants an accepted M2-ADR-032 advisory proposal promoted into
  `HYPOTHESIS_DISCRIMINATION_V1`'s candidate-probe eligibility, that is its own AI-authority-boundary
  decision requiring a separate, explicit ADR (the same scrutiny M1-ADR-010 / M2-ADR-010 / M2-ADR-023
  / M2-ADR-032 received) — never a silent widening of §E/§S here.

### W. Known limitation — no probe-specific psychometric quality (V1)

`HYPOTHESIS_DISCRIMINATION_V1` does not model probe-specific psychometric quality. Two scoreable
candidate probes that target the same hypothesis and are evaluated against the same base context can
therefore receive **the same score** — the construct has no mechanism to distinguish them further.

The V1 score measures exactly one thing: the deterministic sensitivity of the hypothesis-uncertainty
distribution to the probe's two synthetic scoreable evidence outcomes (§D). It does **not** measure,
and must never be interpreted as measuring:

- probability of learner correctness on the probe;
- question difficulty;
- an item discrimination parameter (in the psychometric / IRT sense);
- calibrated assessment quality of any kind;
- probability that the probe will resolve the diagnosis.

This is intentional for V1, not an oversight to be patched with an ad hoc weight. Introducing any of
the above would require an authored or calibrated psychometric input — new authoritative data this
amendment does not have and may not invent (M2-ADR-023 §2) — and its own explicit ADR decision, never
a silent addition inside `HYPOTHESIS_DISCRIMINATION_V1`'s existing frozen formula.


## Amendment 3 — `DIAGNOSTIC_SELECTION_V6` runtime-semantics freeze (2026-09-11)

**Docs-only. Authorizes no code.** This amendment does not implement `DIAGNOSTIC_SELECTION_V6`, does
not modify `DiagnosticService`, does not modify any selector, `ProbeRelationshipService`,
`HypothesisUncertaintyCalculatorV1`, or `HypothesisDiscriminationCalculatorV1`, and adds no migration,
API, or `SelectionReason` value. Its sole purpose is to freeze the runtime semantics the
`docs/adr/M2-ADR-034-step3-v6-discovery-report.md` discovery report found unspecified, so that a
future, separately reviewed implementation PR has no algorithmic choice left to invent. Amendments 1
and 2 are unchanged; this amendment supersedes only the specific §2 clauses named in §CC below.

### A. What this amendment corrects in the discovery report

The discovery report's first draft over-concluded that Step 1 "necessarily sees zero evidence" at
V6 selection time, and that V6 is therefore unconditionally inert. That is too strong. The newly
created *destination* attempt indeed has zero responses at selection time — but
`HypothesisUncertaintyContextAssembler.assemble(UUID interactionId, List<DiagnosticHypothesis>
candidates)` and `HypothesisUncertaintyRepository.findPerInteractionEvidence` both accept *any*
attempt id as `interactionId`, and `DiagnosticService.resolveHypothesisProbeSelection` already reads
from `repository.findMostRecentCompletedAttempt(...)` — the *immediately preceding completed source
attempt* — which may itself carry governed probe-response evidence (if it was itself a
hypothesis-driven attempt whose own probe was answered). M2-ADR-034 as merged does not say which of
these two attempts supplies Step-1 evidence for a V6 decision. That silence is a governance gap, not
proof of permanent inertness — §C below closes it.

### B. Governing question this amendment exists to answer

> Which interaction supplies `HYPOTHESIS_UNCERTAINTY_V1` evidence when `DIAGNOSTIC_SELECTION_V6`
> is deciding a probe for a *new* attempt, and does the current V5 walk even produce more than one
> candidate hypothesis to discriminate between?

Two separate, previously-conflated questions follow from this, corresponding to the two independent
collapses V5 performs today (§D):

- **Decision 1 (§C):** which interaction's evidence does Step 1 read?
- **Decision 2 (§E):** does V6 keep V5's existing single-hypothesis collapse, or does it enumerate a
  bounded multi-hypothesis set?

### C. Decision 1 — the source interaction is authoritative for Step-1 evidence

**Frozen:**

```
sourceInteractionId =
    the immediately preceding completed assessment attempt,
    selected by the existing V5 source-attempt rule
    (repository.findMostRecentCompletedAttempt(learnerId, assessmentVersionId))
```

`HYPOTHESIS_UNCERTAINTY_V1` for a V6 decision is computed with `HypothesisUncertaintyContext
.interactionId() = sourceInteractionId` — **never** the id of the destination attempt being created.
This is not a silent substitution: it is the one interaction this rule names, and no other attempt
may be substituted for it (not the destination attempt, not any older completed attempt, not an
aggregate across several attempts).

**Rationale** (each independently sufficient, together decisive):

- It is a single interaction — no cross-attempt aggregation of the kind Amendment 1 §F already
  forbids ("no cross-attempt H5") is introduced. The evidence read is exactly what
  `HypothesisUncertaintyRepository.findPerInteractionEvidence` already reads for one `attempt_id`,
  unmodified.
- It is the interaction that already exists and already contains governed responses (if any) —
  the destination attempt, by construction, has none yet (its own packet is selected, in this exact
  method call, before any of its items are presented).
- It temporally aligns evidence with hypothesis authorization: `DiagnosticHypothesis` candidates for
  this round are themselves derived from misses *within* the source attempt (`repository
  .findIncorrectItemVersionIdsInPresentationOrder(sourceAttempt.id())`) — using that same attempt's
  id as the evidence scope keeps the hypothesis-authorization boundary and the evidence-observation
  boundary as the *same* interaction, rather than splitting them across two attempts for no
  documented reason.
- It requires no H5/H7-style cross-attempt aggregation machinery to be built or reasoned about.

**What this does NOT do:** it does not relax Amendment 1 §F's "per-interaction only" freeze — it
answers *which* one interaction, using a rule (the existing V5 source-attempt selection) that already
exists and is already frozen by M2-ADR-025. It does not widen Step 1's own contract in any way; Step 1
still receives one `interactionId` and computes over it exactly as before.

### D. The two independent V5 collapses (discovery correction)

`DiagnosticService.resolveHypothesisProbeSelection` performs two collapses that Amendment 2 §2's
original text described together, under one label ("step (b)'s deterministic tiebreak"), but which
must be analyzed and frozen separately:

```
previous completed attempt misses (presentation_order)
        |
        v
  [ COLLAPSE A -- hypothesis collapse ]
  for each miss, for each RELATIONSHIP_TYPE_PRIORITY entry:
    probeRelationshipService.resolve(...)
    return at the FIRST CANDIDATES_AVAILABLE hit
        |
        v
  exactly one DiagnosticHypothesis
        |
        v
  [ COLLAPSE B -- probe collapse ]
  ProbeResolution.candidates()  (a bounded, ordered List<ProbeCandidateItem>)
        |
        v
  .get(0)  -- first item, no ranking
        |
        v
  one selected probe
```

- **Collapse A** discards every other miss and every other relationship type the instant one
  resolves. No bounded multi-hypothesis list is ever materialized.
- **Collapse B** discards every other candidate item within the *one* hypothesis Collapse A kept,
  by taking the first element of an already-ordered list — a real, bounded list exists here before
  the collapse.

This distinction matters because Amendment 2's `HYPOTHESIS_DISCRIMINATION_V1` computes separability
**across a hypothesis distribution**. If V6 leaves Collapse A untouched and only replaces Collapse B,
the candidate hypothesis set handed to Step 1 always has exactly one member — and Amendment 2 §H
already proves, as frozen mathematics, that **a sole participating hypothesis normalizes to exactly
`1.0000` in every reachable world, so every candidate probe targeting it scores exactly `0.0000`,
unconditionally**. Fixing Decision 1 alone does not change this: a one-element candidate set is
provably inert regardless of which interaction supplies its evidence. Therefore Decision 1 and
Decision 2 are **jointly necessary** — neither alone makes V6 capable of doing anything.

### E. Decision 2 — bounded multi-hypothesis enumeration is ratified (not assumed)

**This is a real policy expansion, and Amendment 2's own §H theorem is why it cannot be left
unratified.** Preserving Collapse A exactly as it stands today (Mode 1: keep V5's
first-actionable-hypothesis policy, only replace Collapse B) is **mathematically certain**, not
merely likely, to leave `HYPOTHESIS_DISCRIMINATION_V1` permanently at `0.0000` for every candidate,
by direct, provable consequence of Amendment 2 §H. Ratifying Mode 1 would freeze a V6 that behaves
identically to V5 in every case, forever — which is not an acceptable outcome for a Step-3
authorization whose entire purpose is to let discrimination scoring influence selection.

**Frozen: Mode 2.** `DIAGNOSTIC_SELECTION_V6`'s hypothesis discovery walks the **same existing
deterministic authority** (`ProbeRelationshipService.resolve`) across **every** (miss, relationship
type) pair — it does not stop at the first hit — and considers every `DiagnosticHypothesis` whose
resolution outcome is `CANDIDATES_AVAILABLE` for admission into a bounded **authorized hypothesis
set**, subject to two further gates this amendment freezes precisely: de-duplication (§F), a hard
cardinality bound (below), and — critically — actionability (§H/§I): a hypothesis is a member of the
*relationship-authorized* set the instant `resolve` returns `CANDIDATES_AVAILABLE`, but it enters
`DIAGNOSTIC_SELECTION_V6`'s own **working set** (the set that actually reaches
`HYPOTHESIS_UNCERTAINTY_V1`/`HYPOTHESIS_DISCRIMINATION_V1`) only if it clears §H's actionability
test too. §H defines that test in full; this section defines only the walk and the cardinality
bound.

```
for each miss in findIncorrectItemVersionIdsInPresentationOrder(sourceAttempt.id()), in order:
  for each type in HypothesisDrivenProbeDiagnosticSelector.RELATIONSHIP_TYPE_PRIORITY, in order:
    resolution = probeRelationshipService.resolve(miss, type, learnerId)
    if resolution.outcome() != CANDIDATES_AVAILABLE:
      continue
    if resolution.hypothesis() already admitted (§F de-duplication):
      continue                              -- a duplicate never consumes a working-set slot
    eligibleProbes = resolution.candidates() (destination-eligibility-filtered per §H)
    if eligibleProbes is empty:
      continue                              -- relationship-authorized but not actionable; never
                                                consumes a working-set slot either (§H)
    admit resolution.hypothesis(), with eligibleProbes, into the V6 working set
    if |V6 working set| == MAX_AUTHORIZED_HYPOTHESES_V6:
      stop walking -- the bound is reached
```

No new authority is introduced — this is the identical, already-governed `ProbeRelationshipService
.resolve` call V5 already makes, called across the full (miss × type) domain instead of stopping
early. This satisfies the candidate-authority constraint verbatim: "enumerate more results from the
same existing deterministic authority, not introduce a new hypothesis-generation authority." Only an
*actionable* hypothesis — one that survives §H's test — ever consumes a bound slot; a
relationship-authorized-but-non-actionable or duplicate hypothesis is inspected and discarded
without narrowing the budget available to hypotheses walked later.

**A real, explicit bound is frozen — not derived from mutable configuration.** An earlier draft of
this amendment bounded the set by `(misses in the source attempt) × |RELATIONSHIP_TYPE_PRIORITY|`
and cited the *default* adaptive-packet configuration
(`singleChoiceTarget(5) + fillBlankTarget(2) = 7`) as if it were a governing ceiling. That was wrong:
`AdaptiveDiagnosticFormProperties`'s target sizes are ordinary mutable `@ConfigurationProperties`
fields with setters and no frozen maximum — a future configuration change could raise them with no
ADR review at all, silently moving Amendment 3's own bound. **Frozen instead:**

```
MAX_AUTHORIZED_HYPOTHESES_V6 = 4
```

**Derivation (not an invented number):**

1. *Current diagnostic packet quotas.* `MAX_HYPOTHESIS_PROBES_PER_PACKET = 1` (frozen, M2-ADR-025
   §6) already caps the *externally visible* effect of this whole feature area to exactly one probe,
   regardless of how many hypotheses are considered internally — the bound here governs internal
   computation, not the packet.
2. *Historical/configuration expectations.* Adaptive packet item-count properties
   (`singleChoiceTarget`/`fillBlankTarget`) are mutable configuration, explicitly rejected above as a
   basis for an ADR-level bound. The **only** compile-time-fixed cardinality anywhere in this exact
   feature area is `HypothesisDrivenProbeDiagnosticSelector.RELATIONSHIP_TYPE_PRIORITY.size() == 4`
   (a `List.of(...)`, four relationship-type semantics, never a fifth without a new ADR).
3. *Step-2 complexity.* Amendment 2 §T already bounds `HYPOTHESIS_DISCRIMINATION_V1`'s own cost to
   `2P` calls into Step 1 per candidate probe, each `O(H log H)` — cheap at any realistic `H`; raw
   CPU cost is not the binding constraint on this bound.
4. *Worst-case `H x P`.* With `H <= MAX_AUTHORIZED_HYPOTHESES_V6 = 4` and `P` bounded, as it already
   is under Amendment 2, by however many verified/scoreable/unseen items exist for one target
   objective (small and curriculum-bounded, not newly bounded by this amendment), worst-case Step-2
   cost is a small constant multiple (at most 4x) of a quantity Amendment 2 already accepts as
   trivial.
5. *Smallest operationally sufficient bound.* `4` is the smallest bound that does not depend on any
   mutable configuration and is not an arbitrarily chosen ceiling — it is exactly the cardinality of
   the one frozen enumeration this walk is already built from. This is deliberately conservative,
   matching M2-ADR-025 §6's own "conservative on purpose" ethos for this exact feature area. If
   operational experience later shows 4 is insufficient, that is a new, explicit, separately
   reviewed decision (minting a versioned successor constant, e.g.
   `MAX_AUTHORIZED_HYPOTHESES_V6_2`, or a distinctly named policy) — never a silent constant bump.

**Critical framing, stated precisely:** this is **not** a statement that hypotheses beyond the
`MAX_AUTHORIZED_HYPOTHESES_V6`-th admitted one are ineligible, unauthorized, or wrong. It is a
computation/governance bound on the size of `DIAGNOSTIC_SELECTION_V6`'s own working set — every
hypothesis `ProbeRelationshipService.resolve` authorizes remains exactly as authorized as it is
today; this amendment only limits how many of them one `V6` decision considers at once. Once the
bound is reached, the walk **stops admitting additional hypotheses** to the working set (it does not
need to keep walking for admission purposes, though nothing forbids continuing to walk purely for
observability/logging); relationship authority itself, `V5`'s own fallback semantics, and every
other selector are completely unaffected.

**Determinism.** Admission order is exactly the enumeration order already frozen: source attempt
`presentation_order`, then `RELATIONSHIP_TYPE_PRIORITY` index. De-duplication (§F) is checked
**before** a candidate consumes a working-set slot — a duplicate never counts twice against
`MAX_AUTHORIZED_HYPOTHESES_V6`, and a non-actionable relationship-authorized hypothesis (§H) never
consumes a slot at all.

### F. Hypothesis de-duplication

The same `DiagnosticHypothesis` identity may be reachable from more than one (miss, type) pair (e.g.
two different misses independently resolving to the same target objective under the same
relationship type). **Frozen:** de-duplication is by exact `DiagnosticHypothesis` record equality
(`triggerItemVersionId, triggerObjectiveId, relationshipType, targetObjectiveId,
authorizingRelationshipId` — all five fields), the same identity Amendment 1 §H's canonical order
already keys on. A repeat is admitted once. **No fuzzy, semantic, or embedding-based
deduplication; no graph-based collapsing of "related" hypotheses into one.** Two hypotheses that
differ in even one field (e.g. two different relationship types both landing on the same target
objective) are distinct members of the set.

**Ordering relative to §E's bound and §H's actionability test, frozen precisely:** for a given
`(miss, type)` walk step, de-duplication is checked **first** (a repeat of an already-admitted
identity is discarded immediately, consuming no working-set slot and requiring no actionability
re-check), then §H's actionability test runs, and only a hypothesis that is both novel and
actionable ever consumes one of the `MAX_AUTHORIZED_HYPOTHESES_V6` slots §E freezes. A duplicate
never consumes a slot, whether or not it would have been actionable.

### G. Hypothesis ordering — three distinct orders, not one

This amendment distinguishes three orders that must not be conflated:

1. **Enumeration order** (governs admission into, and de-duplication within, the authorized
   hypothesis set): `presentation_order` of the triggering miss, then `RELATIONSHIP_TYPE_PRIORITY`
   index of the relationship type — exactly the order Collapse A already walks in today. This order
   determines nothing about the final `HypothesisUncertaintyContext`/`HypothesisUncertaintyResult`
   output shape; it only determines which hypothesis is admitted first when duplicates are found
   walking two different (miss, type) pairs to the same identity (§F) — the first-walked instance's
   provenance fields are kept.
2. **Uncertainty canonical output order** (governs `HypothesisUncertaintyResult.candidates()` and
   every downstream Step-1/Step-2 emission): Amendment 1 §H's frozen five-key comparator
   (`RELATIONSHIP_TYPE_PRIORITY` index → `targetObjectiveId` → `authorizingRelationshipId` (nulls
   first) → `triggerObjectiveId` → `triggerItemVersionId`) — **unchanged, reused verbatim**. This is
   *not* the same key sequence as enumeration order (enumeration is keyed by *miss* presentation
   order first; canonical order is keyed by *relationship type priority* first) — they will usually
   disagree, and that is expected and correct: enumeration decides *which* hypotheses get in, §H
   decides how they are *presented*.
3. **Discrimination tie-break order** (Amendment 2 §K, unchanged, reused verbatim): score
   descending → hypothesis canonical order (item 2 above) ascending → `probeItemVersionId` ascending.

**No UUID-random or database-row order governs any of the three.** Nothing in this amendment
replaces §H's canonical order with a raw UUID sort; enumeration order and canonical order remain
two deliberately different, both-deterministic sequences serving different purposes.

### H. Decision 3 — probe enumeration, and the relationship-authorized / actionable hypothesis distinction

**Frozen: YES**, for a hypothesis under consideration, V6 evaluates the *entire*
`ProbeResolution.candidates()` list — not `.get(0)` — filtered by nothing beyond what
`ProbeRelationshipResolver`/`ProbeRelationshipService` already compute (verified/scoreable/published
item state) **and** the same destination-attempt eligibility V5 already applies today (§I). **No new
eligibility rule is introduced or invented** — this only stops discarding the tail of an
already-bounded, already-filtered list.

**Two distinct terms, frozen precisely, and never conflated:**

- **Relationship-authorized hypothesis**: any `DiagnosticHypothesis` for which
  `ProbeRelationshipService.resolve(...)` returns `CANDIDATES_AVAILABLE`. This is a statement about
  curriculum authority alone (a `PUBLISHED` relationship or curriculum-native relation exists) and
  says nothing about whether the destination attempt currently being created can actually act on it.
- **`V6`-actionable hypothesis**: a relationship-authorized hypothesis for which **at least one** of
  its own `ProbeResolution.candidates()` items survives every existing destination-attempt
  eligibility rule (§I) — chiefly, the destination attempt's own `unseenPool`/exposure state. Only
  actionable hypotheses enter `DIAGNOSTIC_SELECTION_V6`'s working set; only working-set members ever
  reach `HYPOTHESIS_UNCERTAINTY_V1` or `HYPOTHESIS_DISCRIMINATION_V1` for a given `V6` decision.

**Why this distinction is required, not cosmetic:** if a relationship-authorized-but-non-actionable
hypothesis (call it `H2`) were nonetheless admitted to `HypothesisUncertaintyContext.candidates()`,
it could carry directional evidence from the source interaction and consume a real share of the
normalized uncertainty mass in Amendment 1's own distribution — measurably shifting the normalized
values of `H1`/`H3` (hypotheses `V6` genuinely *can* act on) even though `V6` can never select any
action for `H2` itself, since none of its candidates survived destination eligibility. Admitting a
hypothesis `V6` cannot act on to influence a decision about hypotheses it can act on would be an
unintended, undocumented coupling between curriculum authority and destination-attempt state. This
amendment closes that gap: **a hypothesis that clears relationship authorization but has zero
surviving destination candidates is never placed into `V6`'s Step-1 candidate set at all** — not
merely excluded from Step 2's candidate-probe list, excluded from Step 1's own input entirely.

**This does not narrow relationship-authorization itself.** `ProbeRelationshipResolver`/
`ProbeRelationshipService` are untouched; `H2` is exactly as relationship-authorized after this
amendment as before it. "Actionable" is a `V6`-local, Step-3-scoped filter on which
relationship-authorized hypotheses `V6`'s own working set admits for *this* decision — it makes no
claim about `H2`'s domain truth, and a later decision (a different destination attempt, different
exposure state) could find `H2` actionable when this one did not.

**Frozen order** (restated from §E/§F, gathered here for reference):

```
resolve relationship-authorized hypothesis (ProbeRelationshipService.resolve)
        |
        v
exact-identity de-duplication (§F) -- a duplicate consumes no slot, skips the rest
        |
        v
derive eligibleProbes = resolution.candidates() (destination eligibility applied, §I)
        |
        v
eligibleProbes empty?  --yes-->  relationship-authorized only; NOT admitted to the V6 working set;
        |                        consumes no MAX_AUTHORIZED_HYPOTHESES_V6 slot
        no
        v
admit hypothesis + eligibleProbes to the V6 working set; consume one bound slot (§E)
```

Each surviving `ProbeCandidateItem` for an admitted, actionable hypothesis becomes exactly one
`CandidateProbe(probeItemVersionId, hypothesis, scoreable)`.

### I. Candidate-eligibility preservation

V6 may not widen or narrow existing V5 candidate eligibility, except for the one explicit change
this amendment authorizes (Collapse B no longer discards candidates 2..N of an actionable
hypothesis' own list, and Collapse A no longer discards relationship-authorized hypotheses beyond
the first — subject to §E's cardinality bound and §H's actionability filter). Everything else about
eligibility is preserved verbatim:

- relationship validity (`PUBLISHED` rows only, per M2-ADR-024 §1);
- verification/publication state and scoreability (`ProbeRelationshipRepository.itemsForObjective`'s
  own "every verified, scoreable item" filter, unchanged);
- learner exposure / no-repeat exclusion (`exposedLogicalItemIds`, unchanged — §V freezes the exact
  as-of-selection semantics this exclusion must use for replay);
- same-domain scoping (enforced independently by both `ProbeRelationshipResolver`'s own objective
  resolution and by `HypothesisUncertaintyCalculatorV1`'s own `CROSS_DOMAIN_CANDIDATE_SET`
  validation);
- curriculum/assessment-version scoping (`assessmentVersionId` threaded through unchanged);
- ambiguity behavior (`AMBIGUOUS_TARGET_OBJECTIVE` still yields no hypothesis, exactly as today —
  Amendment 3 does not touch `ProbeRelationshipResolver` at all);
- trigger eligibility (`TriggerItemHasNoObjectiveException` /
  `TriggerItemHasAmbiguousObjectiveException` still `break` out of that miss's inner loop to the
  next miss, unchanged).

**Frozen:** if a `ProbeResolution`'s candidate item is **not** present in the destination attempt's
own `unseenPool` (the same check `skillCodeOfItem` already performs today), that candidate is
excluded when computing `eligibleProbes` (§H) exactly as V5 excludes it from consideration today —
**it is never silently reintroduced** just because V6's enumeration is broader. If excluding it
leaves the hypothesis with zero surviving candidates, the hypothesis itself is excluded from the
working set per §H — it is never retained in `HypothesisUncertaintyContext.candidates()` merely
because it was relationship-authorized.

### J. Activation rule — frozen, with mathematical justification

**Frozen:** `DIAGNOSTIC_SELECTION_V6` overrides `V5`'s own first-eligible tiebreak with a
discrimination-ranked choice only when **all** of the following hold for the attempt being created:

1. a valid immediately preceding completed source interaction exists (§C);
2. the `V6`-actionable hypothesis set (§H) is not empty;
3. the governed candidate-probe set across that working set (§H) is not empty;
4. `HYPOTHESIS_UNCERTAINTY_V1.calculate(...)` returns `status = APPLICABLE`;
5. **at least two hypotheses participate** in that Step-1 result (`participates = true` on ≥2
   candidates);
6. `HYPOTHESIS_DISCRIMINATION_V1.calculate(...)` returns `status = SCORABLE`; **and**
7. the resulting maximum discrimination score across the working set is strictly greater than
   `0.0000`.

If **any** condition fails, `V6` makes **no** discrimination-driven choice for this attempt: `V5`'s
existing selection (Collapse A's first-actionable hypothesis under today's `resolve` order, Collapse
B's `resolution.candidates().get(0)`) is used exactly as it is today, unchanged.

**Condition 5 is required, not optional, and is derived directly from Amendment 2 §H's own frozen
proof, not chosen for convenience:** a sole participating hypothesis normalizes to `1.0000` in
*every* reachable world (§H), so **every** candidate probe targeting it scores exactly `0.0000` —
with fewer than two participants, discrimination is not merely unlikely to help, it is
mathematically certain to be a no-op. Requiring ≥2 participants is the only way to avoid computing a
result that Amendment 2's own math already guarantees is `0.0000`, and makes the ADR's own original
wording — "a bounded, well-formed hypothesis set... exists" — precise.

**Condition 7 is stated as its own numbered condition, not folded silently into "SCORABLE," because
`SCORABLE` and "carries genuine separating power" are different facts.** `HYPOTHESIS_DISCRIMINATION_V1
.status() == SCORABLE` means only that Step 2 *computed* a score for every candidate — it says
nothing about whether any of those scores is non-zero. **`maxScore == 0.0000` is not a Step-2
failure, an invalid result, or evidence of a malformed context** — it is a perfectly valid `SCORABLE`
result that happens to carry no separating power for this particular working set (see the
mathematical note below for exactly when this occurs). Condition 7 exists precisely to distinguish
"Step 2 ran successfully and found nothing to prefer" from "Step 2 found a genuine preference" —
**Step 3 declining to override `V5` on a `SCORABLE`-but-all-zero result is a deliberate `V6` policy
choice (§K), never a rejection of Step 2's output.**

**A sharper mathematical consequence, offered as supporting analysis (not a new rule to ratify, and
not a claim about candidate *counts*):** condition 5 alone does not guarantee a *particular*
candidate probe scores above `0.0000`, and **the number of eligible candidate probes — for one
hypothesis or across the whole working set — never by itself determines any candidate's score.**
Because `(1,0)` and `(0,1)` both map to `LOW` under `DiagnosticConfidenceCalculatorV1`'s own frozen
thresholds, a probe whose *target hypothesis currently has zero prior directional evidence* always
produces identical `World-S`/`World-C` distributions (every other hypothesis's weight is invariant
between the two worlds, and the target's own weight is identically `1` in both) — so **that specific
probe scores exactly `0.0000`, independent of how many other hypotheses participate and independent
of how many candidate probes exist for it or for any other hypothesis.** Conversely, a hypothesis
with only **one** surviving eligible candidate probe, sitting inside a working set where ≥2
hypotheses participate, is not thereby guaranteed a `0.0000` score either: if that one candidate's
*target hypothesis already carries at least one prior directional observation* (a re-probe, not a
first-time probe), and the two possible next observations would move it into different confidence
bands, its score can be strictly positive. **Non-zero discrimination is reachable only for a
candidate probe that re-probes an already-evidenced hypothesis** — never determined by how many
candidates happen to exist. This is a direct, provable consequence of the already-frozen Step 1 and
Step 2 mathematics — this amendment changes neither formula, it only names the consequence so a
future implementer does not mistake "few candidates" or "condition 5 satisfied" for "discrimination
is guaranteed trivial" in either direction. §P applies this specifically to the case where the
*whole working set* has exactly one eligible candidate.

### K. Zero-score semantics

**Frozen:**

```
maxScore > 0.0000   -> use Step-2 ranking (Amendment 2 §K, verbatim) to choose the probe
maxScore == 0.0000  -> preserve the existing V5 selection exactly (Collapse A's first-actionable
                       hypothesis, Collapse B's resolution.candidates().get(0))
```

No canonical-order or `probeItemVersionId` tie-break is used to break a universal `0.0000` tie into a
V6-driven choice that differs from what V5 would already have chosen — doing so would be a real
behavior change with zero underlying diagnostic signal, and is explicitly rejected. This is condition
7 of §J failing, not an error: **`maxScore == 0.0000` never means Step 2's result is invalid; it
means Step 3 declines to override `V5` because the (valid) result carries no separating power.**

**Does this conflict with Amendment 2's frozen ranking contract? No.** Amendment 2 §K freezes how to
rank a *valid, already-computed* `HYPOTHESIS_DISCRIMINATION_V1` result when it is used — it does not,
and was never asked to, mandate that every `SCORABLE` result must be *acted on*. Step 3 (this
amendment) makes a Step-3-level decision — whether discrimination-driven selection activates at all
— that is orthogonal to, and does not mutate, Step 2's own frozen scoring or ranking. `§K`'s ranking
remains exactly what it always was: the correct way to rank a Step-2 result that carries genuine
separating power. This amendment adds nothing to, and removes nothing from, that ranking; it only
decides when a Step-3 consumer is permitted to *use* it.

**Distinguishing the all-zero case from a genuine positive-score tie, precisely:** if `maxScore ==
0.0000`, §K's fallback applies and Amendment 2 §K's ranking is never consulted at all (there is
nothing to rank into a decision — every candidate is equally uninformative). If `maxScore > 0.0000`
and **more than one** candidate shares that maximum, Amendment 2 §K's frozen canonical tie-break
(hypothesis canonical order ASC, then `probeItemVersionId` ASC) resolves it exactly as already
frozen — this is condition 7 having *passed*, with an ordinary tie among genuinely informative
scores, not the all-zero fallback case. The two situations must never be conflated: one is "nothing
to choose between" (fallback to `V5`), the other is "several equally good choices" (Step-2's own
frozen tie-break decides).

### L. `NOT_APPLICABLE` semantics (Step 1)

**Frozen:** if `HYPOTHESIS_UNCERTAINTY_V1.status == NOT_APPLICABLE` (empty `V6`-actionable hypothesis
working set — activation condition 2 already failed), V6 performs no discrimination override and
preserves exact V5 selection behavior. This is never a failure of attempt creation; it is the same
"degrades to no adjustment" outcome M2-ADR-025 §2 already guarantees for V5 itself.

### M. `INSUFFICIENT_EVIDENCE` semantics (Step 1)

**Frozen:** if `HYPOTHESIS_UNCERTAINTY_V1.status == INSUFFICIENT_EVIDENCE` (every actionable
hypothesis in the working set lacks directional evidence in the source interaction — the common
case for a freshly authorized set per §J's mathematical note), no distribution is fabricated, no
Step-2 scoring is attempted (activation condition 4 fails), and V5 behavior is preserved exactly. No
uniform or synthetic uncertainty is ever substituted, consistent with Amendment 1 §D/§G's own
discipline.

### N. Validation / internal-failure semantics — expected outcomes vs. corruption

**Frozen distinction:**

- **Expected control outcomes** — no source interaction, an empty `V6`-actionable hypothesis working
  set (including every relationship-authorized hypothesis turning out non-actionable), an empty
  candidate-probe set, `NOT_APPLICABLE`/`INSUFFICIENT_EVIDENCE` (Step 1), fewer than two
  participants, `NOT_APPLICABLE` (Step 2), and every candidate scoring `0.0000` (`maxScore ==
  0.0000`) — are normal, frequent results of a correctly-functioning system and always resolve to
  "preserve exact V5 selection," never an error. `maxScore == 0.0000` in particular is never treated
  as invalid (§J/§K) — it is a valid `SCORABLE` result that carries no separating power.
- **Invariant/corruption validation failures** — `HypothesisUncertaintyValidationException` /
  `HypothesisDiscriminationValidationException` of any reason code (`BASE_RESULT_MISMATCH`,
  `DUPLICATE_EVIDENCE_OBSERVATION`, any `CROSS_DOMAIN_*` code, `MALFORMED_*`,
  `DUPLICATE_CANDIDATE_PROBE`, `PROBE_FOR_UNKNOWN_HYPOTHESIS`) indicate a context was assembled
  incorrectly — a defect in the code that builds `HypothesisUncertaintyContext`/
  `HypothesisDiscriminationContext` for V6, not a legitimate diagnostic state. **Frozen: these fail
  the attempt-creation transaction closed** (propagate the exception; do not catch-and-fall-back to
  V5). Silently masking a validation exception by falling back to V5 would hide a real integrity
  defect in whatever assembles V6's inputs, exactly the outcome Amendment 1 §Q's and Amendment 2
  §N's own fail-closed disciplines exist to prevent from being papered over. This is consistent with
  existing RAMALS governance: nowhere in this codebase does a caller catch a
  `*ValidationException` from a frozen calculator and silently substitute a different result: Step 1
  and Step 2 are designed to make a malformed caller-assembled context visibly break the caller, not
  quietly degrade.

### O. Empty hypothesis / candidate semantics

**Frozen:** no relationship-authorized hypotheses resolve at all → the `V6`-actionable working set is
empty → V5 unchanged (activation condition 2 fails). Relationship-authorized hypotheses exist but
none is actionable (§H — every one's own candidates are excluded by destination eligibility), or an
actionable hypothesis exists but the working set's combined candidate-probe set is otherwise empty →
V5 unchanged (activation condition 2 or 3 fails, as applicable). No synthetic candidate or synthetic
hypothesis is ever manufactured to satisfy activation; no LLM, MCP, or any other fallback authority
is ever consulted to produce one.

### P. One-candidate-probe semantics — corrected

**An earlier draft of this section made a mathematically incorrect claim and is corrected here.**
It asserted that a single governed candidate probe across the whole working set must score
`0.0000`, citing Amendment 2 §H. That citation was wrong: **Amendment 2 §H proves `0.0000` for a
sole *participating hypothesis* — it says nothing about the *count of candidate probes*.** With two
or more participating hypotheses, a single eligible probe that happens to re-probe an
already-evidenced hypothesis can legitimately receive a strictly positive discrimination score (§J's
sharper mathematical note). **Amendment 3 makes no claim, and never did intend to claim, what
discrimination score any specific probe would receive merely from a candidate count.**

**What is actually true, and is what this section freezes:** if the working set's combined candidate
set (across every actionable hypothesis) contains **exactly one** governed candidate probe, that
probe is the only possible action `V6` could ever select — no ranking can change the outcome,
because there is nothing else to rank it against. **Frozen policy (unchanged from the earlier
draft): `DIAGNOSTIC_SELECTION_V6` selects that sole eligible probe directly and does not invoke
`HYPOTHESIS_UNCERTAINTY_V1`/`HYPOTHESIS_DISCRIMINATION_V1` for it.** The corrected rationale:

> There is only one eligible action, therefore scoring cannot affect the selected probe. Skipping
> Step 1/Step 2 in this case is an optimization and a semantic simplification — selecting the one
> available action is exactly what a ranking over a one-element set would produce regardless of its
> score — never a mathematical assertion that the probe's discrimination score must be zero.

**A structural note, offered for completeness, not as a new rule:** because §H requires every
actionable hypothesis to contribute at least one of its own surviving candidates to the working set,
a combined candidate-probe count of exactly one forces the actionable-hypothesis count to also be at
most one — which already independently fails §J's condition 5 (`≥2` participating hypotheses). This
section's policy and §J's activation gate therefore agree on the outcome (`V5`'s exact selection is
used) for this specific case, via two independently correct reasons — this section's "nothing to
rank" optimization, and §J's "fewer than two participants" gate — **neither of which is "the score
must be `0.0000`."** Calling the calculators anyway "for audit" is explicitly rejected as an
alternative here, to keep §J as the single, consistent gate for every other case.

### Q. Final ranking when V6 is genuinely active

**Frozen:** Amendment 2 §K's ranking, reused verbatim: `score DESC → hypothesis canonical order ASC
(Amendment 1 §H) → probeItemVersionId ASC`. **No additional term is ever added** — not difficulty,
not a misconception-graph weight, not a relationship-type bonus, not an AI-supplied confidence, not
a mastery multiplier, not a psychometric parameter of any kind (Amendment 2 §W's own documented V1
limitation).

### R. `RELATIONSHIP_TYPE_PRIORITY` is order/governance only, never a score

**Frozen, for clarity:** `RELATIONSHIP_TYPE_PRIORITY` participates in exactly two places under this
amendment — (1) the enumeration order that decides which (miss, type) pairs are walked and in what
sequence (§E), and (2) as one term inside `DiagnosticHypothesis`'s own canonical order (Amendment 1
§H, unchanged). **It is never converted into a numeric weight, bonus, or score contribution
anywhere in this amendment, in Step 1, or in Step 2.** A relationship type appearing earlier in the
priority list confers no discrimination-score advantage; its only effect is which hypothesis is
admitted first when a de-duplication tie must be broken (§F) and how results are ordered for
presentation (§G).

### S. `DIAGNOSTIC_SELECTION_V6` version identifier

**Frozen:** when `DIAGNOSTIC_SELECTION_V6` is eventually implemented, it mints its own
`SELECTION_POLICY_VERSION = "DIAGNOSTIC_SELECTION_V6"` string constant, following the exact
precedent every one of `V1`–`V5` already set, recorded on `core.assessment_version
.selection_policy_version` / `core.assessment_attempt.selection_policy_version` exactly as V5's
value is today. `V1`–`V5` are not mutated into `V6` behavior; `V6` is a new, separately selectable
policy value through the existing selection-policy mechanism `DiagnosticService.selectForm` already
dispatches on. This freezes the *decision* that a distinct identifier is required (Amendment-3
Decision 5 from the discovery report); it does not itself add the constant, since no code changes
under this amendment.

### T. `V6`'s relationship to `V5` — additive composition, not a rewrite

```
V3 prerequisite cap (adjustForPrerequisites)
        |
        v
V4 regression handling (adjustForRegressions)
        |
        v
V5 deterministic hypothesis/candidate authority
   (ProbeRelationshipResolver / ProbeRelationshipService -- UNCHANGED, reused verbatim)
        |
        v
V6 discrimination override IF §J's activation conditions are satisfied
   (chooses which already-authorized Selection to hand to V5's own adjustForHypothesisProbe)
        |
        v
HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe   [V5 proper -- UNCHANGED]
        |
        v
AdaptiveDiagnosticSelector.select(...)                              [V2 -- UNCHANGED]
```

`V5` continues to supply all candidate authority (relationship resolution, eligibility, exposure
filtering) unchanged. `V6` changes only *which* already-authorized `Selection` value is chosen when
its own activation conditions hold; `HypothesisDrivenProbeDiagnosticSelector` itself is never
modified, and its own `EngineVersionFreezeTests` hash is unaffected by anything `V6` does, because
that hash is computed by calling `adjustForHypothesisProbe` directly with a hand-built `Selection` —
independent of how the `Selection` was produced.

### U. Provenance

**Frozen minimum auditable fields** for a `V6`-driven decision, all already representable by the
existing `core.diagnostic_probe_provenance` / `core.assessment_attempt` schema with **no migration**:
`selection_policy_version = "DIAGNOSTIC_SELECTION_V6"` (attempt-level, existing column), source
attempt id (existing `source_attempt_id` FK), selected hypothesis (existing
`source_item_version_id`/`source_objective_id`/`relationship_type`/`target_objective_id`/
`authorizing_relationship_id` columns — a hypothesis's identity is already fully reconstructable
from these), and selected probe item id (existing `item_version_id` column). **Preference: no
migration.** The engine versions of Step 1/Step 2 and the winning discrimination score are **not**
persisted per-row, consistent with Amendment 2 §P's own decision ("compute-on-read... nothing here
needs its own persisted provenance") — they are fully reconstructable by replay (§V) from already-
persisted, immutable evidence, given the deterministic inputs below. If a future audit requirement
demands recording the score itself without relying on replay, that is a new decision requiring its
own schema change and its own review — not authorized here.

### V. Replay / reproducibility

**Corrected.** An earlier draft of this section claimed the same historical evidence "fully"
reconstructs a `V6` decision, then separately conceded that a later replay could see a *larger*
exposure set than the original decision did — two statements that directly contradict each other,
since the candidate set a `V6` decision (and, today, a `V5` decision) considers is itself filtered by
exposure. That contradiction is resolved below, not carried forward.

**Frozen: `destinationExposureCutoff`.** A `V6` decision's candidate pool must use the learner's
exposure state **as of immediately before the destination attempt's own item-selection decision** —
never the exposure state at whatever later moment a replay happens to run.

```
destinationExposureCutoff = the destination attempt's own core.assessment_attempt.created_at value
```

This is available at decision time without any new column: `DiagnosticService.createAttempt` already
inserts the destination attempt's row (`repository.insertAttempt(...)`) — fixing its `created_at` —
**before** `selectForm`/the hypothesis walk runs, so `destinationExposureCutoff` is already a known,
persisted, immutable value by the moment any `V6` decision would be made.

**Verdict: YES, exposure state as of `destinationExposureCutoff` is reconstructable from already-
persisted data, with no migration**, using columns that already exist:

```sql
SELECT DISTINCT lin.logical_item_id
FROM core.assessment_attempt_item ai
JOIN core.assessment_attempt a ON a.id = ai.attempt_id
JOIN core.assessment_item_lineage lin ON lin.item_version_id = ai.item_version_id
WHERE a.learner_id = ?
  AND a.created_at < ?   -- destinationExposureCutoff
```

This is the same join `AssessmentRepository.findLearnerExposedLogicalItemIds` already performs
(`core.assessment_attempt_item` → `core.assessment_attempt` → `core.assessment_item_lineage`), with
one added predicate on `core.assessment_attempt.created_at` — a column that already exists
(`V005__assessment_and_attempts.sql`, `NOT NULL DEFAULT CURRENT_TIMESTAMP`, never updated by any
trigger or statement in this codebase; only `updated_at` is touched by
`trg_assessment_attempt_touch_updated_at`) and is never mutated after insert. **No new column, no
new table, no migration.**

**Why today's real-time V5 decision needs no code change, and only a replay/audit tool does.** At
the moment `V5` (or a future `V6`) actually makes its decision for a brand-new destination attempt,
that attempt's own items do not exist in `core.assessment_attempt_item` yet, and no later attempt has
been created yet either — so today's unbounded `findLearnerExposedLogicalItemIds(learnerId)` query
and the cutoff-bounded query above return **identical** results at that exact moment. The two queries
only diverge for a query issued **later** — after the destination attempt has been completed and/or
the learner has taken further attempts — which is exactly the situation a replay or audit tool runs
in, and exactly why replay must use the cutoff-bounded form, not the caller's own real-time query.

**One residual, narrow, pre-existing caveat, stated precisely and not used to excuse the fix above:**
`findLearnerExposedLogicalItemIds` is scoped to `learner_id` only, not to one assessment version, and
`findActiveAttempt`'s one-active-attempt invariant is scoped to `(learnerId, assessmentVersionId)` —
so a learner could in principle have a second attempt concurrently `IN_PROGRESS` under a *different*
assessment version at a `created_at` interleaved with the destination attempt's own. This is a
property of the exposure/no-repeat model shared by `V1`–`V5` today, not a new inconsistency `V6`
introduces, and it does not affect the cutoff-bounded reconstruction's correctness for the single
assessment version a given `V6` decision is scoped to.

**Frozen replay inputs**, all already persisted or already frozen:

- the source attempt id (persisted, immutable per M2-ADR-025's provenance trigger);
- the ordered miss list for that source attempt (`presentation_order`, persisted, immutable);
- the `V6`-actionable hypothesis working set (§E/§H) — reconstructable deterministically by
  re-running the same (miss × `RELATIONSHIP_TYPE_PRIORITY`) walk, bounded by
  `MAX_AUTHORIZED_HYPOTHESES_V6`, against `PUBLISHED`-only relationship rows (immutable once
  published, M2-ADR-024 §1) and the exposure state **as of `destinationExposureCutoff`** above —
  never the exposure state current at replay time;
- the governed candidate-probe set for that working set (§H) — reconstructable the same way;
- the source interaction's governed evidence (`core.diagnostic_probe_provenance` join
  `core.assessment_response`, both immutable once written);
- `HYPOTHESIS_UNCERTAINTY_V1` and `HYPOTHESIS_DISCRIMINATION_V1`'s own engine-version identifiers
  (frozen, hashed by `EngineVersionFreezeTests`);
- `DIAGNOSTIC_SELECTION_V6`'s own version identifier (§S) once minted.

Given these, and using `destinationExposureCutoff` (not current exposure state) for every exposure
read, a `V6` decision is fully and exactly reconstructable without persisting the score itself.

> **Post-merge correction flagged — 2026-09-11 (implementation-review finding, not yet a ratified
> amendment).** This section's own "Verdict: YES, ... fully and exactly reconstructable ... with no
> migration" is **not correct under concurrent PostgreSQL transactions**, and this note records that
> defect rather than silently rewriting the ratified text above (M2-ADR-034's own discipline: an
> amendment, once ratified, is corrected by a new dated amendment, never edited in place).
>
> `created_at` is stamped at `INSERT` (transaction-statement) time, not at commit time. Under
> PostgreSQL's default `READ COMMITTED` isolation (RAMALS configures no isolation override anywhere;
> this is confirmed, not assumed), row visibility is decided by *commit order*, not by which
> `created_at` value a row happens to carry. §V's own "residual, narrow, pre-existing caveat"
> paragraph above considered a concurrent cross-version attempt interleaving with the destination
> attempt's own `created_at` and concluded it "does not affect the cutoff-bounded reconstruction's
> correctness" — that conclusion is wrong. Concretely: nothing in this codebase serializes attempt
> creation for one learner across different `assessment_version_id`s (`uq_assessment_attempt_one_active`
> is scoped per version; no advisory lock exists anywhere in the codebase). If a concurrent attempt's
> `INSERT` fixes an earlier `created_at` but does not *commit* until after the destination decision's
> own live exposure read, the live decision correctly never sees it (ordinary `READ COMMITTED`
> isolation, exactly as this section claims) — but a later replay using `created_at <
> destinationExposureCutoff` **will** wrongly include it, since `created_at` carries no commit-order
> information. Executable proof: `AssessmentItemLineagePersistenceIntegrationTests
> #concurrentUncommittedAttemptCreatesADestinationExposureCutoffReplayDivergence`, run against a real
> PostgreSQL 18.1 instance.
>
> **Governance disposition.** Exact replay cannot be restored by further refining the `created_at`
> query — the defect is that no timestamp column can encode commit-visibility order at all. The
> leading candidate fix is persisting sufficient immutable decision-time inputs (the actionable
> hypothesis working set and candidate-probe set actually computed, keyed to the destination
> attempt) so replay consumes a persisted snapshot rather than re-deriving exposure from `created_at`
> at all. That fix requires a schema migration, which directly contradicts this section's own
> ratified "no new column, no new table, no migration" verdict — so it is **not implemented by this
> note or by the PR that added it**. **A new M2-ADR-034 amendment (Amendment 4) is required to
> correct §V and authorize the persistence-based fix before `DIAGNOSTIC_SELECTION_V6` may honestly
> claim exact historical replay.** [Amendment
> 4](#amendment-4--diagnostic_selection_v6-replayprovenance-correction-2026-09-11), below, is that
> correction. Until its own persisted-snapshot design is implemented,
> `AssessmentRepository#findLearnerExposedLogicalItemIdsBefore` remains available for its real,
> narrower value (correct reconstruction in the common, non-concurrent-interleaving case) but must
> not be described or relied upon as an exact MVCC-safe replay of a `V6` decision — see that method's
> own corrected javadoc.

### W. M2-ADR-032 isolation (reaffirmed)

```
LLM
  |
  v
ADR-032 proposal
  |
  v
deterministic gate
  |
  v
ACCEPTED
  |
  v
advisory / audit only
  X                    <- no path into V6's relationship-authorized set, actionable working set, or
                          candidate-probe set
DIAGNOSTIC_SELECTION_V6
```

Reaffirmed exactly as Amendment 2 §E/§S/§V already freeze for Step 2: an accepted
`DiagnosticProbeProposal` never enters `V6`'s relationship-authorized set, its `V6`-actionable
working set (§E/§H), or its candidate-probe set (§H). Gate acceptance under M2-ADR-032 is an
audit/evaluation outcome only. Promoting an
accepted proposal into `V6` eligibility would require its own separate, explicit ADR — this
amendment authorizes no such widening, and nothing in §E/§H's enumeration touches
`io.ramals.learningplatform.diagnosticassessment` in any way.

### X. M2-ADR-033 isolation (reaffirmed)

```
MisconceptionGraphQueryService
        X                              <- no hypothesis, no candidate, no weight, no score
DIAGNOSTIC_SELECTION_V6
```

Reaffirmed exactly as M2-ADR-033 §6 already names ("or a future `V6`") and as Amendments 1/2 §G/§M
already freeze for Step 1/Step 2: no `MISCONCEPTION_RELATED`/`MISCONCEPTION_PREREQUISITE_LINK` edge,
and no edge-type weight of any kind, participates in hypothesis admission (§E), candidate
construction (§H), uncertainty (Step 1), discrimination (Step 2), or ranking (§Q).

### Y. AI boundary (reaffirmed)

```
Java candidate authority (ProbeRelationshipResolver / Service, unchanged)
        |
        v
Java uncertainty (HYPOTHESIS_UNCERTAINTY_V1, unchanged)
        |
        v
Java discrimination (HYPOTHESIS_DISCRIMINATION_V1, unchanged)
        |
        v
Java selection (V6 activation + fallback, this amendment)
```

An LLM or MCP tool may never: add a hypothesis to the authorized set (§E); add a candidate probe to
the eligible set (§H); assign, adjust, or override any uncertainty or discrimination value; rank
candidates; or override the selected probe. This is the same boundary M2-ADR-023 §2, M2-ADR-025 §10,
M2-ADR-032 §3, and Amendment 2 §S already draw — restated here for `V6` specifically, not widened.

### Z. Transactionality

**Frozen:** a `V6` decision executes entirely inside the existing `DiagnosticService.createAttempt`
`@Transactional` boundary, on one coherent repository snapshot, synchronously, in the same call
stack that already resolves candidates, constructs hypotheses, and persists the packet and
provenance today. No new transaction boundary, no asynchronous selection, and no eventual-consistency
window between scoring and persistence is introduced.

### AA. Performance bound — corrected

**An earlier draft of this section bounded `H` by `(misses in source attempt) x 4` and cited the
default adaptive-packet configuration as if it were a frozen ceiling.** That configuration
(`AdaptiveDiagnosticFormProperties.singleChoiceTarget`/`fillBlankTarget`) is ordinary mutable
`@ConfigurationProperties` state with setters and no frozen maximum — a future configuration change
could raise it with no ADR review, silently invalidating that bound. Corrected below using §E's
actually-frozen `MAX_AUTHORIZED_HYPOTHESES_V6` constant instead.

**Frozen bound:**

```
H <= MAX_AUTHORIZED_HYPOTHESES_V6 = 4     (§E; independent of any mutable configuration)
P <= however many verified, scoreable, unseen items are tagged to one hypothesis's target
     objective — already small and curriculum-bounded, exactly as Amendment 2 §T already accepts
```

`HYPOTHESIS_DISCRIMINATION_V1`'s own frozen performance boundary (Amendment 2 §T) already bounds its
cost to `2P` calls into Step 1 per candidate probe, each `O(H log H)`. With `H` fixed at a
governance-frozen constant, worst-case Step-2 cost is a small, bounded multiple (at most `4x`) of a
quantity Amendment 2 already treats as trivial — **entirely independent of adaptive-packet
configuration, miss count, or any other mutable value.**

The `(miss × type)` **walk** performed to populate the working set (§E) is not itself bounded by
`MAX_AUTHORIZED_HYPOTHESES_V6` in terms of `ProbeRelationshipService.resolve` calls issued — the walk
may still inspect every `(miss, type)` pair before finding `MAX_AUTHORIZED_HYPOTHESES_V6` actionable
hypotheses, or may stop earlier once the bound is reached. This does not affect `H`/`P` (the
Step-1/Step-2 computation size this section bounds) — it affects only how many `resolve` calls (each
already-bounded-cost per M2-ADR-024/025) are issued before the working set is finalized, a
consideration orthogonal to this section's `H`/`P` bound. **No unbounded `all misses x all
relationships x all candidates` walk without a working-set cap is ratified** — the walk is
exhaustive only up to `MAX_AUTHORIZED_HYPOTHESES_V6` admissions, over a domain that is itself
finite (bounded by the source attempt's own, already-persisted miss count), not open-ended.

### BB. Required golden scenarios (design-level; frozen expected behavior, not yet implemented)

These are normative scenarios a future implementation PR's tests must reproduce exactly. They are
not implemented here — no test exists yet, since no `V6` code exists yet.

| # | Scenario | Frozen expected behavior |
|---|---|---|
| V6-1 | No previous completed source attempt | V5-equivalent behavior (activation condition 1 fails) |
| V6-2 | Source attempt exists, no relationship-authorized hypothesis resolves | V5-equivalent behavior (condition 2 fails) |
| V6-3 | Step 1 returns `INSUFFICIENT_EVIDENCE` | V5-equivalent behavior (§M) |
| V6-4 | Exactly one participating hypothesis | V5-equivalent behavior (condition 5 fails; §J's mathematical proof) |
| V6-5 | ≥2 participants, but every candidate probe scores `0.0000` (`maxScore == 0.0000`) | V5-equivalent behavior (§K) — a valid `SCORABLE` result, not an error |
| V6-6 | ≥2 participants, two or more unequal positive scores | Highest-scoring candidate selected (§Q) |
| V6-7 | `maxScore > 0.0000`, a positive-score tie among two or more candidates | Amendment 2 §K's frozen canonical tie-break resolves it — never confused with V6-5's all-zero fallback (§K) |
| V6-8 | Same inputs, misses/candidates supplied in permuted order | Identical selection (enumeration is order-independent by construction; §G) |
| V6-9 | An accepted M2-ADR-032 proposal exists for this learner/interaction | Proposal does not enter the relationship-authorized set, the `V6`-actionable working set, or the candidate-probe set (§W) |
| V6-10 | A related misconception exists in the M2-ADR-033 graph | Graph has zero effect on selection (§X) |
| V6-11 | A malformed/inconsistent Step-1 or Step-2 context (any validation reason code) | Fails the attempt-creation transaction closed (§N) — never silently falls back |
| V6-12 | Identical replay of the same historical evidence, **using `destinationExposureCutoff` (§V) for exposure state, not the exposure state current at replay time** | Identical selected probe (§V) |
| V6-A | A working set with ≥2 actionable/participating hypotheses, in which one specific hypothesis `H1` has exactly one eligible candidate probe targeting an *already-evidenced* hypothesis | `H1`'s sole candidate is scored normally by Step 2 and may receive a strictly positive score (§J/§P) — **no assertion that having only one candidate for `H1` forces `0.0000`**; contrast with §P's own scenario, where the working set's *combined* candidate total across every hypothesis is exactly one (which independently forces condition 5 to fail, per §P's structural note) |
| V6-B | A relationship-authorized hypothesis `H2` resolves `CANDIDATES_AVAILABLE`, but every one of `H2`'s own candidate probes is excluded by destination-attempt eligibility (e.g. all already exposed) | `H2` is relationship-authorized but **not actionable** (§H) — it does not enter `HypothesisUncertaintyContext.candidates()` at all, and never influences `H1`/`H3`'s normalized values |
| V6-C | More relationship-authorized *and* actionable hypotheses are discovered than `MAX_AUTHORIZED_HYPOTHESES_V6` (§E) | Only the first `MAX_AUTHORIZED_HYPOTHESES_V6` unique actionable hypotheses, in frozen enumeration order (miss `presentation_order` then `RELATIONSHIP_TYPE_PRIORITY`), enter the working set; later ones are not admitted and do not affect the decision (§E) — this is a computation bound, not an eligibility judgment about the ones excluded |
| V6-D | A historical `V6` decision is replayed after the learner has taken further attempts that expose additional items | The replay uses the exposure state **as of the original decision's `destinationExposureCutoff`** (§V), not the learner's current exposure state — candidate set and selected probe are identical to the original decision |

> **V6-12 / V6-D correction — 2026-09-11.** Both scenarios above assume §V's `destinationExposureCutoff`
> mechanism achieves exact reconstruction. It does not, under concurrent transactions — see the
> post-merge correction note at the end of §V. Until Amendment 4 corrects §V, V6-12/V6-D are frozen
> *targets*, not properties the current `created_at`-bounded mechanism actually guarantees.

### CC. ADR diff summary (this amendment)

- **Header** — a third `Amended` line added pointing to this amendment; the title's "Steps 1–2
  implemented and inert, Step 3 design-only" suffix is unchanged (this amendment still authorizes no
  `V6` code).
- **§2 (original ADR body)** — superseded, precisely, only where it says: "`V6` acts only when a
  bounded, well-formed hypothesis set with a computed posterior exists" (now defined exactly by §J);
  and where it frames the two collapses as a single undifferentiated "step (b)" (now split into
  Collapse A/B, §D, each separately addressed by §E/§H). Every other clause of §2 (composition order
  unchanged, no `V1`–`V4` change, quota unchanged, no widening beyond one probe) remains binding,
  unamended.
- **No change** to Amendment 1 or Amendment 2 in any way — their frozen mathematics, golden vectors,
  decimal contracts, and validation reason codes are untouched. This amendment only says *when* and
  *with what inputs* a future `V6` may call them.
- **Review-round corrections (same PR, before merge — this amendment's own draft edited directly,
  not superseded, since it had not yet been ratified):**
  - **§E** rewritten: the hypothesis-cardinality bound is now the explicit, derivation-justified
    `MAX_AUTHORIZED_HYPOTHESES_V6 = 4`, replacing an earlier draft that cited mutable
    `AdaptiveDiagnosticFormProperties` configuration defaults as if they were a frozen ceiling.
  - **§F/§G** clarified: de-duplication and actionability are both checked before a hypothesis
    consumes a working-set slot; enumeration order vs. canonical order vs. tie-break order restated
    against the new working-set vocabulary.
  - **§H/§I** rewritten to introduce and freeze the **relationship-authorized vs. `V6`-actionable**
    hypothesis distinction: a hypothesis with zero surviving destination-eligible candidates is
    excluded from `HypothesisUncertaintyContext.candidates()` entirely, not merely from Step 2's
    candidate-probe list — preventing a non-actionable hypothesis from consuming uncertainty mass
    that could alter actionable hypotheses' normalized values.
  - **§J/§K** restructured: condition 7 (`maxScore > 0.0000`) is now an explicit, numbered activation
    condition, with an explicit statement that `maxScore == 0.0000` is a valid `SCORABLE` result, not
    an error, and a clarified distinction between the all-zero fallback case and an ordinary
    positive-score tie (Amendment 2 §K's own frozen tie-break).
  - **§P corrected — a mathematical error is fixed.** The earlier draft claimed Amendment 2 §H
    proves a single candidate probe scores `0.0000`; §H proves this only for a sole *participating
    hypothesis*, never from a candidate *count*. The frozen policy (select the sole eligible probe
    directly without invoking Step 1/2) is unchanged; the rationale is corrected to "there is only
    one action to choose, so ranking cannot change the outcome" — Amendment 3 makes no claim about
    what score that probe would receive.
  - **§V corrected — an internal contradiction is fixed.** The earlier draft claimed full
    reconstructability while separately conceding exposure state could grow between the original
    decision and a later replay. `destinationExposureCutoff` (the destination attempt's own,
    already-persisted, immutable `created_at`) is now frozen as the exposure-state boundary a replay
    must use, with the exact reconstruction query cited against real, already-existing columns
    (`core.assessment_attempt.created_at`, `core.assessment_attempt_item`,
    `core.assessment_item_lineage`) — no migration required.
  - **§AA corrected**: the performance bound now uses `MAX_AUTHORIZED_HYPOTHESES_V6` instead of a
    mutable-configuration-derived figure.
  - **§BB expanded**: four new golden scenarios (`V6-A` through `V6-D`) covering the corrected
    one-candidate semantics, non-actionable-hypothesis exclusion, the working-set bound, and
    cutoff-based replay; existing scenarios' section references updated to match the renumbered
    activation conditions.
- **Companion doc edits (same PR):** `docs/adr/M2-ADR-034-step3-v6-discovery-report.md` corrected
  (evidence-timing conclusion, two-collapse analysis, source-interaction ambiguity, the four
  review-round corrections above, revised Amendment-3-now-resolved decision list);
  `docs/adr/M2-ADR-register.md` and `docs/architecture/target-intelligence-loop.md` updated to record
  this amendment's ratified, design-only status — `V6` remains unimplemented.

### DD. Revisit triggers added by this amendment

- If operational experience shows `MAX_AUTHORIZED_HYPOTHESES_V6 = 4` (§E) is insufficient (e.g.
  curriculum content grows dense enough that a single miss routinely produces more actionable
  hypotheses than the bound admits), that is a new, separate, explicit decision minting a versioned
  successor constant — never a silent bump of the frozen value.
- If a genuine multi-round adaptive diagnostic session model is ever introduced (allowing more than
  one hypothesis-driven probe per learner across a longer arc, so that a hypothesis could
  accumulate ≥2 observations within one governed evidence window), the §J mathematical note's
  practical consequence (only a re-probed, already-evidenced hypothesis can ever score above
  `0.0000`) should be revisited against that new model — it is a direct consequence of today's
  quota-of-one, per-interaction-only architecture, not an immutable property of
  `HYPOTHESIS_DISCRIMINATION_V1` itself.
- If RAMALS later wants `V6`'s decision to persist the discrimination score or engine versions
  per-row rather than relying on replay (§U/§V), that is a new decision requiring its own schema
  change and its own review, not authorized here.
- If the exposure/no-repeat model is ever scoped per-assessment-version rather than per-learner
  (closing §V's narrow cross-domain-concurrency caveat), that is its own decision affecting
  `V1`–`V5` equally, not specific to `V6` and not implied by this amendment.

## Amendment 4 — `DIAGNOSTIC_SELECTION_V6` replay/provenance correction (2026-09-11)

**Status of this amendment: Proposed.** It corrects Amendment 3 §V's replay/reproducibility
conclusion and freezes the persisted-provenance design needed to make exact historical replay
actually true. **It authorizes no code and no migration** — the implementation PR that follows
ratification is where the schema in §L below is actually created.

### A. What this amendment corrects, and what it does not

Amendment 3 §V is **not rewritten**. It remains, verbatim, the historical record of what was
ratified on 2026-09-11 — including its now-incorrect "Verdict: YES, ... fully and exactly
reconstructable ... with no migration" conclusion. Rewriting a ratified amendment as though its
error never existed would make the ADR's own history untrustworthy; that is precisely the
discipline "No change to Amendment 1 or Amendment 2 in any way" (Amendment 2's own diff summary)
already commits this document to. Amendment 4 instead **prospectively supersedes** exactly the
following clauses of §V, and nothing else in Amendments 1–3:

- the frozen mechanism `destinationExposureCutoff = the destination attempt's own
  core.assessment_attempt.created_at value` **as an exact-replay boundary** (superseded by §G below;
  the underlying frozen *requirement* — replay must reflect exposure as of decision time, never
  exposure state current at replay time — is not superseded, only the *mechanism* that was claimed
  to satisfy it);
- the "Verdict: YES, exposure state ... is reconstructable from already-persisted data, with no
  migration" conclusion (superseded — see §B–§C: it is not reconstructable from `created_at` alone,
  under concurrency);
- the "residual, narrow, pre-existing caveat" paragraph's conclusion that a concurrent,
  differently-versioned attempt "does not affect the cutoff-bounded reconstruction's correctness"
  (superseded — it does; see §B);
- the "Frozen replay inputs" list's claim that the `V6`-actionable working set and candidate-probe
  set are "reconstructable deterministically by re-running the same ... walk" against
  `destinationExposureCutoff` (superseded by §F–§G: re-running the walk, at any later time, against
  any exposure boundary expressed only as a timestamp, cannot reproduce the original decision);
- the closing claim that "a `V6` decision is fully and exactly reconstructable without persisting
  the score itself" (the "without persisting the score" clause is **not** superseded — Amendment 4
  agrees no score should be persisted, see §U — but "fully ... reconstructable" without persisting
  *anything else either* is superseded by §F–§G).

**Not touched by this amendment:** Amendment 1's and Amendment 2's own frozen mathematics, golden
vectors, decimal contracts, and validation reason codes; Amendment 3's activation/fallback/
enumeration/bound rules (§E–§P), which govern *live* `V6` selection and remain exactly as ratified;
`DIAGNOSTIC_SELECTION_V1`–`V5`; `HYPOTHESIS_UNCERTAINTY_V1`; `HYPOTHESIS_DISCRIMINATION_V1`; the
`DIAGNOSTIC_SELECTION_V6` policy identifier itself (see §Y — this amendment does not mint a
successor). **Live `V6` selection was never derived from `created_at`-bounded reconstruction and is
unaffected by anything in this amendment.**

### B. The concurrency defect, frozen as an architectural fact

This is expected PostgreSQL `READ COMMITTED` behavior, not an implementation bug to be patched
inside the query — it is a structural property of MVCC that no refinement of a timestamp predicate
can escape.

```
Transaction A                              Transaction B
--------------                             --------------
INSERT attempt A (created_at = t0)
  -- uncommitted --
                                            INSERT destination attempt B (created_at = t1, t1 > t0)
                                            B's own live exposure read
                                              (findLearnerExposedLogicalItemIds)
                                              runs now, on a separate snapshot --
                                              A is uncommitted, so A's items
                                              are correctly invisible to B
COMMIT (after B's live read already ran)

-- time passes --

Historical replay of B, using
  created_at < B.created_at (= t1):
  A.created_at = t0, and t0 < t1,
  so A now qualifies -- A's items
  are now committed and visible
  -> replay WRONGLY includes A's items
```

Therefore: **the original decision-time exposure set (what B's live read actually saw) is not equal
to the later `created_at`-bounded reconstruction (what a replay query returns).** `created_at`
orders *row-creation statement time*; it carries zero information about *commit order*, and commit
order — not creation order — is what determines what a `READ COMMITTED` transaction can see. This
divergence requires no clock skew, no adversarial timing, and no rare race: it is the ordinary,
correct behavior of any two transactions where the one with the numerically smaller `created_at`
happens to commit second. Nothing in this codebase prevents that ordering — see
`docs/adr/M2-ADR-034-amendment-4-replay-provenance-discovery.md` for the exact invariants checked.

**Executable proof (M2-ADR-034 Step 3 implementation review, PR #277):**
`AssessmentItemLineagePersistenceIntegrationTests
#concurrentUncommittedAttemptCreatesADestinationExposureCutoffReplayDivergence`, run against a real
PostgreSQL 18.1 instance (this repository's own CI image), reproduces exactly the sequence above and
asserts both halves: the live read excludes A; the cutoff-bounded replay wrongly includes it.

### C. Why no timestamp, sequence, or UUID ordering can solve this

Amendment 4 explicitly rejects every one of the following as a sufficient exact-replay exposure
boundary, and requires that no future `V6`-family implementation reach for one of these as a
"simpler fix" without first re-litigating this section:

| Candidate boundary | Why it fails |
|---|---|
| `assessment_attempt.created_at` | §B: creation-statement time, not commit-visibility time. |
| `assessment_attempt.updated_at` | Strictly worse — mutated by `trg_assessment_attempt_touch_updated_at` on every status transition (e.g. completion), so it does not even consistently mean "when this attempt was created," let alone "when it became visible." |
| Application wall-clock timestamp (`Instant.now()` captured in Java) | Same class of defect as `created_at`, plus additional non-monotonicity from clock adjustments, GC pauses, and multi-instance clock skew across however many application instances are running. |
| `SELECT CURRENT_TIMESTAMP` read separately at decision time | Still a statement-time value from inside the *deciding* transaction, not a record of *other* transactions' commit order relative to it — does not change the analysis in §B at all, only where the timestamp is captured. |
| A `BIGSERIAL`/sequence column | PostgreSQL sequence values are allocated at statement-execution time, before commit, exactly like `created_at` — a transaction can allocate a lower sequence value and commit *after* a transaction that allocated a higher one. Sequences do not encode commit order either; this is documented PostgreSQL behavior, not an oversight specific to this schema. |
| UUID ordering (including `UuidV7.generate()`, already used throughout this schema) | A time-ordered UUID embeds a generation timestamp captured at allocation time, in the generating process — the identical failure mode as `created_at`, one layer further from the database. An unordered UUIDv4 carries no time signal at all. |
| "Attempt insertion order inferred from timestamps" (any of the above, combined) | Restates the same defect; combining several statement-time signals does not manufacture a commit-order signal none of them individually carries. |

**None of these records the original transaction's visibility snapshot**, because none of them is
*computed from* transaction commit order — they are all computed from statement-execution order,
which concurrency structurally decouples from commit order.

**PostgreSQL internal MVCC identifiers (`xmin`/`xmax`/`txid`) are also rejected as a durable
application-level provenance mechanism**, for reasons independent of whether they could technically
solve §B: they are 32-bit values that wrap around and are periodically recycled by `VACUUM`
(`FREEZE`), they are not portable across a `pg_dump`/restore or a future database migration, and
depending on them would couple this repository's application-level reproducibility guarantee to a
PostgreSQL storage-engine implementation detail this codebase relies on nowhere else. **RAMALS must
not make exact educational decision replay depend on ephemeral or internal MVCC metadata.** If a
future, separate ADR ever proposes such coupling for a different purpose, it must justify that
coupling on its own terms; Amendment 4 does not pre-authorize it.

### D. Governing principle (the central Amendment-4 decision)

> **If a `V6` selection input depends on transient decision-time database state — whether *which
> rows were visible* (MVCC visibility, §B) or *which row a time-sensitive query would return* (e.g.
> "the most recent completed attempt," §F) — and that state cannot later be reconstructed exactly,
> the relevant authoritative decision input must be persisted at decision time.**

Replay reconstructs from **persisted authoritative decision inputs**, never by attempting to
resurrect an old PostgreSQL MVCC snapshot through a timestamp, sequence, or internal identifier, and
never by re-running a "most recent"/"latest" style query that can legitimately return a different
answer once more time has passed and more data exists. This is the corrected replacement for §V's
"reconstructable ... with no migration" claim.

### E. Category A — inputs that stay reconstructable, unpersisted

Verified against this repository's actual immutability guarantees, not assumed. **Every item below
reconstructs the *content* of an already-identified source attempt — never the identity of *which*
attempt that was.** See §F for why that identity is a separate, Category B fact.

- **A known source attempt's own row.** Once `sourceAttemptId` is known, `core.assessment_attempt`
  rows are never deleted (every FK referencing one is `ON DELETE RESTRICT`), so the row it names is
  permanent regardless of any later status transition — it cannot disappear or be reassigned to a
  different learner or assessment version out from under a past decision. **This says nothing about
  how replay learns which id to use in the first place** — that is not reconstruction, it is lookup
  of an already-persisted fact (§F).
- **That known source attempt's ordered misses.** `findIncorrectItemVersionIdsInPresentationOrder` joins
  `core.assessment_attempt_item` (write-once; `trg_assessment_attempt_touch_updated_at` aside,
  nothing updates or deletes a row here — V045's own comment: "Written once at attempt creation and
  immutable thereafter") to `core.assessment_response` (immutable — `core.protect_assessment_response`
  rejects `UPDATE`/`DELETE` unconditionally). The source attempt is `COMPLETED` before it can be used
  as a `V6` source at all, so both joined tables are already fixed by the time any `V6` decision
  reads them.
- **The source interaction's governed probe evidence.** `core.diagnostic_probe_provenance` is
  immutable (`trg_probe_provenance_guard` rejects `UPDATE`/`DELETE` unconditionally), and it is
  scoped to `source_attempt_id`, joined to the same immutable `assessment_response`.
- **Published relationship rows' own content**, once published. `core.diagnostic_probe_relationship`
  cannot be updated or deleted once `status = 'PUBLISHED'` (`trg_probe_relationship_immutable`) — a
  row a `V6` decision resolved against cannot later change or disappear. **This is necessary but not
  sufficient** — see §F: new rows can still be *published* later, changing what a fresh `resolve()`
  call finds, without changing any row a past decision actually used.
- **`HYPOTHESIS_UNCERTAINTY_V1` / `HYPOTHESIS_DISCRIMINATION_V1`'s own engine-version identifiers**
  — frozen, hashed by `EngineVersionFreezeTests` (see §R for what this does and does not guarantee
  across an engine-version change).
- **`DIAGNOSTIC_SELECTION_V6`'s own selection-policy identifier**, written once, immutably, to
  `assessment_attempt.selection_policy` at `insertAttempt` time.

None of the above needs duplicating into a new table. Persisting any of it would be exactly the
"arbitrary derived state when recomputation is safe" this repository's own principle (cited in the
implementation-review discovery) warns against.

**The source attempt's *contents* are reconstructable only after the exact historical source
attempt has been identified. The *identity* of that source attempt is not reconstructed by
replay: it is persisted in the replay-input header as authoritative decision-time provenance
(§F).** Treating "the row is permanent once you know its id" as though it meant "the id itself can
be rediscovered later" is exactly the conflation this section corrects — the two are different
claims, and only the first one is true of `sourceAttemptId`.

### F. Category B — decision-time visibility-dependent inputs (must be persisted)

Three things a `V6` decision depends on are **not** safely reconstructable later:

1. **Which source attempt the decision actually used** (`sourceAttemptId` itself) — the most
   fundamental of the three, since the other two are only meaningful once the source attempt is
   known. See "Source attempt identity" immediately below.
2. **The `V6`-actionable hypothesis working set** — which relationship-authorized hypotheses survive
   the de-duplication and destination-eligibility (exposure) filter, up to `MAX_AUTHORIZED_HYPOTHESES_V6`.
3. **The surviving candidate-probe set** for that working set, after destination-eligibility
   (exposure) filtering.

#### Source attempt identity

`V6`'s source attempt is chosen using semantics equivalent to
`repository.findMostRecentCompletedAttempt(learnerId, assessmentVersionId)` — the *immediately
preceding completed* attempt for the same assessment version (Amendment 3 §C). **This lookup is
itself time-sensitive: which attempt is "most recent" changes as more attempts complete.**

```
Attempt A completes
        |
        v
Destination attempt B is created; V6 uses A as its source
        |
        v
Attempt C completes later (same learner, same assessment version)
        |
        v
Historical replay of B: re-running findMostRecentCompletedAttempt(...) now returns C, not A
```

Re-running the same lookup at replay time can therefore return a **different** attempt than the one
the original decision actually used. This is a defect independent of, and additional to, the two
exposure-related reasons below — it requires no concurrency at all, only ordinary, sequential,
later learner activity.

> **Frozen rule: `sourceAttemptId` is authoritative decision-time provenance and MUST be persisted
> in the `V6` replay-input snapshot header.** For `NO_SOURCE_ATTEMPT`, the persisted `NULL` is
> itself the authoritative fact, not an absence of one (§J). **Historical replay MUST NOT invoke
> `findMostRecentCompletedAttempt(...)`, or any equivalent "latest completed attempt" discovery, to
> determine the historical source attempt** — it reads the persisted `sourceAttemptId` from the
> snapshot header and uses exactly that value, including when that value is `NULL` (§O).

#### Working set and candidate-probe set

Both of these depend on `ProbeRelationshipService.resolve(...)`'s exposure check at the exact
moment the original decision ran, made against the source attempt the decision used. Re-running
that same walk at a later time — even given the *correct* `sourceAttemptId` — can diverge from the
original for **two further, independent reasons**, either alone sufficient to require persistence:

- **§B's MVCC defect** — a concurrent attempt's commit timing can make yesterday's live decision and
  today's re-derivation disagree about which items were exposed, in either direction, with no way to
  recover which was true at decision time from `created_at` alone.
- **Curriculum growth.** `core.diagnostic_probe_relationship` rows are immutable once published
  (§E), but **new rows can be published after a `V6` decision runs.** A `resolve()` call made today
  can find a hypothesis or candidate a `resolve()` call made at the original decision time
  structurally could not have found, because it did not exist yet. Re-deriving "the working set"
  from current data therefore does not even reproduce a *correct* historical snapshot in the
  no-concurrency case — it can silently widen it. **Replay must reproduce what `V6` considered
  then, never what `V6` would consider now** (§P).

### G. Preferred persisted replay boundary

**Persist which source attempt the decision used, and the exact `V6`-actionable working set and its
surviving candidate probes as they existed at the original destination-attempt decision — not the
learner's entire exposure history, and not a snapshot of "everything published so far."**

```
destination attempt
    |
source attempt              (sourceAttemptId persisted in the header, §F -- an identity fact,
                              never re-derived at replay; NULL iff NO_SOURCE_ATTEMPT)
    |
V6 actionable hypothesis 1  (full DiagnosticHypothesis identity -- §H)
    |-- candidate probe A   (probeItemVersionId -- §I)
    |-- candidate probe B
    |
V6 actionable hypothesis 2
    |-- candidate probe C
```

This is the smallest boundary that closes all three §F defects. The working set and candidate-probe
set are properties of *this exact computation's output*, not of the learner's exposure history at
large — persisting every exposed item across the learner's history would be strictly larger, would
still need updating on every future attempt (defeating "smallest necessary"), and would not by
itself capture *which* hypotheses/candidates the exposure filter actually admitted. The source
attempt's identity is a single scalar fact (one id, or `NULL`) sitting alongside it on the same
header row — not a separate, larger structure, and not optional: without it, the working set and
candidate-probe set would themselves be ambiguous about which source attempt they were derived
from.

### H. Exact hypothesis identity (no collapsing)

Every persisted hypothesis row must carry the complete, frozen `DiagnosticHypothesis` identity —
the same five fields Amendment 1's canonical order and `V6`'s own exact-identity de-duplication
already require:

```
triggerItemVersionId
triggerObjectiveId
relationshipType
targetObjectiveId
authorizingRelationshipId
```

**Never** collapse to `(targetObjectiveId, relationshipType)` or any other partial key, and never
semantically de-duplicate two persisted rows that differ in any of the five fields. Doing either
would silently change what "the same hypothesis" means between live selection and replay, breaking
compatibility with Amendment 1's canonical order and `V6`'s own dedup semantics (M2-ADR-034
Amendment 3 §F).

### I. Exact candidate-probe identity

Every persisted candidate probe must be bound, at minimum, to:

```
destinationAttemptId    (on the header row it belongs to -- sec L)
sourceAttemptId          (on the same header row -- sec F/L; authoritative, may be NULL)
full DiagnosticHypothesis identity (sec H) of the owning hypothesis  (on the candidate row itself)
probeItemVersionId                                                    (on the candidate row itself)
```

`destinationAttemptId` and `sourceAttemptId` need not be repeated on every candidate row — §L's
schema carries both once, on the header row, and each candidate row references it by FK. What
matters is that every candidate is unambiguously traceable to exactly one `(destinationAttemptId,
sourceAttemptId)` pair, however the columns are physically arranged; the implementation PR is free
to denormalize this onto the candidate rows themselves if that is more convenient, provided the
values still originate from the one persisted header, never from a fresh lookup.

**Storage/admission ordinal: audit-only, never authoritative.** An admission ordinal (which
enumeration pass admitted this hypothesis, or its position among a hypothesis's own candidates) may
be persisted for audit legibility, but `HYPOTHESIS_DISCRIMINATION_V1`'s own frozen `RANKING_ORDER`
(score `DESC` → hypothesis canonical order → probe UUID) is already independent of input-list order
— proven by this repository's own existing "permuting the candidate list does not change the
result" tests for both Step 1 and Step 2. Persisted order carrying authority over final selection
would be a **new** selection semantic Amendment 4 does not introduce and explicitly forbids;
replay must re-derive the ranking from `RANKING_ORDER` applied to the persisted (unordered) set, not
from row-storage order.

**`scoreable`: persist it, marked always-true-today.** Every real candidate reaching a `V6` working
set is scoreable by construction (`ProbeRelationshipRepository.itemsForObjective` returns only
verified, scoreable items) — the same finding Amendment 2's own discovery report already made for
Step 2's `CandidateProbe.scoreable`. Persisting the flag costs one boolean column and protects a
future implementation change to that invariant from silently corrupting historical replay without
being noticed; it is not expected to ever read `false` for a real row.

### J. Snapshot scope — when it must exist

**Whenever `selection_policy_version = DIAGNOSTIC_SELECTION_V6`, exactly one snapshot header row
must be persisted for that attempt — regardless of the final outcome.** `V6`'s own `select()` method
already runs unconditionally for every `DIAGNOSTIC_SELECTION_V6` attempt (it is what decides `NO_SOURCE_ATTEMPT`, `NO_ACTIONABLE_HYPOTHESES`, and every other fallback in the first place), so this
adds no new evaluation path — it persists the result of a computation that already happens:

- no source attempt → header row, `sourceAttemptId = NULL`, zero hypothesis/candidate rows;
- source attempt exists, zero relationship-authorized hypotheses → header row, zero hypothesis/
  candidate rows;
- relationship-authorized but zero actionable (all excluded by exposure) → header row, zero rows;
- the single-candidate-total optimization, every Step-1/Step-2 fallback, and genuine activation →
  header row plus exactly the hypothesis/candidate rows the working set actually contained.

**Why not only when `V6` activates:** replay must reproduce *why* `V6` did not activate, not only
the cases where it did — an audit or dispute that asks "why was the learner not shown a
discrimination-ranked probe here" needs the same working set a "why was probe X chosen" audit needs.

**Distinguishing "`V6` evaluated an empty set" from "no snapshot exists (pre-Amendment-4 data)":**
the header row's mere *existence*, keyed uniquely to `destinationAttemptId`, is the signal. A
pre-Amendment-4 `V6` attempt has no header row at all; every post-Amendment-4 `V6` attempt has
exactly one, even when its own hypothesis/candidate rows are empty. This is why a versioned header
(§K), not merely populated child rows, is required.

### K. Snapshot contract identifier and versioning

A dedicated contract-version identifier, distinct from `DIAGNOSTIC_SELECTION_V6` (the *selection
policy*) and from `HYPOTHESIS_UNCERTAINTY_V1`/`HYPOTHESIS_DISCRIMINATION_V1` (the *engines*), is
required so schema evolution, audit interpretation, and "is exact replay guaranteed for this
attempt" can each be reasoned about independently:

```
DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1
```

(repository-native form and exact hosting class/constant name to be fixed by the implementation PR,
following the existing `SELECTION_POLICY_VERSION` / `ENGINE_VERSION` / `POLICY_VERSION` constant
convention — not by inventing a new one). This identifier must **never** be conflated with or embed
into `HYPOTHESIS_UNCERTAINTY_V1`/`HYPOTHESIS_DISCRIMINATION_V1`'s own frozen strings — it versions
the *snapshot contract*, not either engine's mathematics, which Amendment 4 does not touch.

### L. Logical schema (design only — no migration in this PR)

Two tables are sufficient; a hypothesis row is not modeled separately from its candidate-probe rows
because a `V6`-actionable hypothesis has, by its own definition (Amendment 3 §H), at least one
surviving candidate — so no hypothesis-only row would ever exist, and merging costs only mild,
bounded repetition of five identity columns across at most `MAX_AUTHORIZED_HYPOTHESES_V6 = 4`
hypotheses' worth of candidates.

```
core.diagnostic_selection_replay_input          (conceptual name -- not final)
  id                          UUID PK
  destination_attempt_id      UUID  NOT NULL UNIQUE FK -> core.assessment_attempt(id)
  source_attempt_id           UUID  NULL     FK -> core.assessment_attempt(id)
    -- Authoritative decision-time provenance (sec F) -- WHICH attempt V6 actually used, fixed at
    -- the moment of decision. NULL if and only if NO_SOURCE_ATTEMPT (sec J). Never re-derived by
    -- re-running findMostRecentCompletedAttempt(...) at replay time (sec O) -- that lookup is
    -- itself time-sensitive and can return a different (later) attempt than the one originally
    -- used. Not a cache of a value replay could otherwise compute; the only record of it.
  snapshot_contract_version   VARCHAR        NOT NULL  -- "DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1"
  relationship_authorized_count INTEGER      NOT NULL
  actionable_hypothesis_count INTEGER        NOT NULL
  candidate_probe_count       INTEGER        NOT NULL
  participating_hypothesis_count INTEGER     NOT NULL
  step1_status                VARCHAR        NULL
  step2_status                VARCHAR        NULL
  activated                   BOOLEAN        NOT NULL
  fallback_reason             VARCHAR        NULL   -- one of V6FallbackReason; audit value, see §T
  created_at                  TIMESTAMPTZ    NOT NULL DEFAULT CURRENT_TIMESTAMP
  CHECK (activated = (fallback_reason IS NULL))

core.diagnostic_selection_replay_candidate_probe   (conceptual name -- not final)
  id                          UUID PK
  replay_input_id             UUID  NOT NULL FK -> core.diagnostic_selection_replay_input(id)
  admission_ordinal           INTEGER NOT NULL   -- audit only, never authoritative (sec I)
  probe_item_version_id       UUID  NOT NULL FK -> core.assessment_item_version(id)
  scoreable                   BOOLEAN NOT NULL   -- always true today (sec I)
  trigger_item_version_id     UUID  NOT NULL FK -> core.assessment_item_version(id)
  trigger_objective_id        UUID  NOT NULL FK -> core.learning_objective(id)
  relationship_type           VARCHAR NOT NULL
  target_objective_id         UUID  NOT NULL FK -> core.learning_objective(id)
  authorizing_relationship_id UUID  NULL  FK -> core.diagnostic_probe_relationship(id)
  -- same composite-FK consistency discipline core.diagnostic_probe_provenance already holds
  -- itself to: (probe_item_version_id, target_objective_id) and
  -- (trigger_item_version_id, trigger_objective_id) each FK into
  -- core.assessment_item_objective(item_version_id, objective_id).
```

Both tables: append-only (no `UPDATE`/`DELETE` path — enforced the same way
`trg_probe_provenance_guard` enforces it for `core.diagnostic_probe_provenance`), destination-attempt
scoped, FK-backed at every reference, normalized (not an opaque JSON blob — this repository's own
convention, seen throughout `core.diagnostic_probe_provenance` and every other audit table, is
relational rows with composite FKs, not JSON columns, for exactly this kind of governed audit
record), and written only while the destination attempt is `IN_PROGRESS` (mirroring
`trg_probe_provenance_guard`'s own check). **The implementation PR fixes final table/column names
against whatever this repository's Flyway numbering is at that time; nothing here is final.**

### M. Atomicity

```
destination attempt creation
  +
V6 replay-input snapshot persistence
  +
selected assessment packet
  +
diagnostic probe provenance (when a probe is actually chosen)
```

must occur inside the same, already-existing `DiagnosticService.createAttempt` `@Transactional`
method — no new transaction boundary, no async write, no eventual-consistency window. If snapshot
persistence fails for any reason, the whole attempt-creation transaction fails closed: **no
`DIAGNOSTIC_SELECTION_V6` attempt may exist whose replay-input snapshot is missing or partial**,
since this amendment defines the snapshot as the authoritative replay record — a `V6` attempt
without one would silently fall back to the pre-Amendment-4, non-exact disposition (§X), which must
never happen for an attempt created after this amendment's implementation ships.

### N. Idempotency

`createAttempt`'s existing idempotency (`uq_assessment_attempt_idempotency`,
`findByIdempotency`/`findActiveAttempt` short-circuiting before `selectForm` ever runs) already
guarantees `selectDiagnosticSelectionV6Form` — and therefore snapshot persistence — executes **at
most once** per attempt actually inserted. The snapshot header's own `UNIQUE(destination_attempt_id)`
constraint is defense-in-depth on top of that, not the primary mechanism. A repeated
`Idempotency-Key` request must not, and structurally cannot, recompute the snapshot, overwrite it,
append duplicate rows, or change the candidate set — it returns the existing attempt without
re-invoking `V6` at all, exactly as it does today for every other selector.

### O. Replay algorithm

```
load destination attempt
        |
verify selection_policy_version == DIAGNOSTIC_SELECTION_V6
        |
load the persisted replay-input snapshot for this destination attempt
  (absence => pre-Amendment-4 attempt; exact replay not guaranteed -- sec X; stop here)
        |
read the snapshot's own persisted sourceAttemptId -- never re-derived (sec F)
        |
if sourceAttemptId is NULL: reproduce NO_SOURCE_ATTEMPT directly; do not search for a source
        |
otherwise: load immutable evidence (sec E) for exactly that sourceAttemptId
        |
rebuild the Step-1 context from the snapshot's persisted actionable hypotheses (sec H)
        |
run frozen HYPOTHESIS_UNCERTAINTY_V1 (never reimplemented -- sec R for version compatibility)
        |
rebuild the Step-2 candidate set from the snapshot's persisted candidate probes (sec I)
        |
run frozen HYPOTHESIS_DISCRIMINATION_V1
        |
apply the frozen V6 activation/fallback/ranking rules (Amendment 3, unchanged)
        |
compare the recomputed activation/fallback outcome against the snapshot's own persisted
  activated/fallback_reason (sec T) -- an integrity check, not the source of truth
        |
reproduce the original selected probe (already exactly recorded today by the existing,
  unmodified core.diagnostic_probe_provenance row, when one was written -- sec S)
```

**Replay must never** query `findLearnerExposedLogicalItemIds` (current, unbounded),
`findLearnerExposedLogicalItemIdsBefore` (created_at-bounded — see §W for its disposition), the
destination version's current `unseenPool`, any newly published curriculum/relationship content, or
any AI/graph source, as authoritative input to the historical candidate set. The persisted snapshot
*is* the authoritative candidate set for replay; live discovery of any kind is out of scope for a
replay read.

**Replay must never invoke `findMostRecentCompletedAttempt(...)`, or any equivalent "most recent" /
"latest completed attempt" discovery query, to determine the historical source attempt.** That
lookup is exactly the time-sensitive query §F's "Source attempt identity" describes — a later
learner attempt completing between the original decision and a replay changes what it returns.
Replay determines the source attempt **solely** by reading the snapshot's own persisted
`sourceAttemptId`, including reproducing `NO_SOURCE_ATTEMPT` unchanged when that value is `NULL` —
never by rediscovering a source attempt the original decision did not use, and never by treating a
later-available source attempt as though it had been available at decision time.

### P. Curriculum growth is not replay input

Confirmed (§E): `core.diagnostic_probe_relationship` rows are immutable once `PUBLISHED`, but new
rows can be published after any given `V6` decision. **Replay must reproduce what `V6` considered
then, not what `V6` would consider now** — the persisted candidate set (§G) is authoritative
regardless of what has been published since, even if a newly published relationship would, if
re-resolved today, surface an objectively better candidate. This is a deliberate, frozen replay
invariant: exact reproduction of a past decision, never a re-optimization disguised as a replay.

### Q. Step-1 evidence needs no duplication

`core.diagnostic_probe_provenance` and `core.assessment_response` are both immutable (§E) and are
already scoped to `source_attempt_id`. Once — and only once — that id is known (from the persisted
snapshot's own `sourceAttemptId`, §F, never from a fresh `findMostRecentCompletedAttempt(...)`
lookup), replay reads these tables directly through the existing repositories using exactly that
id. Nothing about a *known* source attempt's own governed evidence is subject to the §B/§F defects,
because a source attempt must already be `COMPLETED` (fixed, immutable) before it is eligible as a
`V6` source at all — the defect this amendment closes is entirely about *which* id to use, never
about the content once the id is fixed.

### R. Engine-version compatibility for replay

Replay against a historical snapshot runs the engines' **current** implementation of
`HYPOTHESIS_UNCERTAINTY_V1` and `HYPOTHESIS_DISCRIMINATION_V1` — not a preserved historical binary —
and relies on `EngineVersionFreezeTests`' own frozen-behavior-vector contract to guarantee that
"current `HYPOTHESIS_UNCERTAINTY_V1`" and "`HYPOTHESIS_UNCERTAINTY_V1` as it behaved at the original
decision time" are the same function, for as long as that identifier is never assigned to changed
behavior (this repository's own absolute rule: a behavior change mints a new identifier,
`..._V2`, and leaves the old one — and every historical record computed under it — untouched). This
is answer **B** of the two posed in review: replay depends on the frozen engine-version *contract*
holding (verified continuously by `EngineVersionFreezeTests`), not on preserving and re-executing a
historically pinned old implementation binary. If `HYPOTHESIS_UNCERTAINTY_V1` or
`HYPOTHESIS_DISCRIMINATION_V1` is ever superseded by a `_V2`, replay of a snapshot recorded under the
`_V1` identifiers must continue to invoke the `_V1` engines specifically (both remain in the
codebase, exactly as `DIAGNOSTIC_SCORING_V1`/`V2` and `EVIDENCE_CONFIDENCE_V1`/`V2` already coexist
today) — this is a direct consequence of the existing engine-versioning discipline, not a new rule
Amendment 4 invents.

### S. Decision replay vs. selected-probe verification (the `V5`-fallback finding)

Amendment 4's discovery work surfaced an important, non-obvious finding that narrows what actually
needs to change:

**Selected-probe verification is already solved, unconditionally, today.** Whenever a probe is
actually placed in a packet — whether `V6` itself activated and chose it, or `V6` fell back and
`V5`'s own `resolveHypothesisProbeSelection` chose it — the existing, unmodified
`core.diagnostic_probe_provenance` row already records the complete `DiagnosticHypothesis` identity
(via its five existing columns) and the chosen `item_version_id`, written atomically in the same
transaction, immutable from the moment it is written (`trg_probe_provenance_guard`). This record is
**not** subject to the §B/§F defects, because it is a direct write of what was actually decided and
persisted at decision time — never a later re-derivation from a timestamp. No change is needed here.
This closes the concern that a `V5`-fallback probe selection (which itself resolves against the same
exposure-dependent `ProbeRelationshipService.resolve` calls, and has carried the identical §B
exposure-timing property since M2-ADR-025, well before `V6` existed) might be unreplayable — it
already is not.

**Decision replay — reproducing *why* `V6` activated or fell back, and what Step 1/Step 2 actually
scored — is the real gap**, and is exactly what §F–§O close. The two concerns are therefore
frozen as distinct:

```
decision replay            -> needs the new persisted working-set snapshot (this amendment)
selected-probe verification -> already exact today, via existing diagnostic_probe_provenance
```

### T. Fallback reason: persisted as an audit value, not as sole authority

`activated` and `fallback_reason` (§L) are persisted, but replay must **always recompute** the
activation/fallback outcome from the persisted working set by re-running frozen Step 1/Step 2/the
frozen activation rules (§O) — never simply trust the persisted value as an oracle. The persisted
value exists so replay can **compare** recomputed-vs-recorded as an integrity check: a mismatch
signals either a corrupted snapshot or (across an engine-version boundary — §R) a genuine
behavior drift, either of which is a defect worth surfacing, not silently accepting. Today's
telemetry (`BusinessEventLogger`) records the same value only in logs, which are not a database of
record and are not queryable, immutable, or FK-consistent — persisting it here is the first
authoritative record of this fact, not a duplicate of one that already existed.

### U. The discrimination score remains unpersisted

Amendment 3's original preference — compute-on-read, never persist the score itself — is **not**
superseded. Nothing in the §F analysis requires the score: replay recomputes it deterministically
from the persisted working set (§G) via the same frozen `HYPOTHESIS_DISCRIMINATION_V1` call live
selection makes. Persisting inputs and recomputing outputs remains strictly preferable to persisting
outputs, per this repository's own stated principle, wherever recomputation is safe — and once the
working set itself is persisted, recomputing the score from it *is* safe (§F's defects are about the
working set's own construction, not about Step 2's own already-pure, already-frozen math).

### V. Migration requirement

**Does exact MVCC-safe `V6` replay require a schema migration? YES.**

This explicitly supersedes Amendment 3 §V's "no new column, no new table, no migration" verdict —
**for the historical-replay guarantee only.** To be unambiguous: **no migration was ever required
for live `V6` selection itself**, which reads current, real-time state exactly as `V1`–`V5` always
have and remains completely unaffected by this amendment. The migration this amendment requires
exists solely to make *replay* — an audit/reproducibility capability, not a live-selection
capability — actually exact, which Amendment 3 incorrectly believed it could achieve for free.

### W. Disposition of `findLearnerExposedLogicalItemIdsBefore`

**Remove it, in the implementation PR that follows this amendment's ratification.** It has no
production caller today (only its own test references it), and its sole original purpose — backing
exact `V6` replay — is fully superseded by §G's persisted snapshot. Retaining an unused,
non-authoritative method whose javadoc must permanently carry a "do not treat this as exact"
disclaimer (added during PR #277's review) is exactly the kind of misleading dead architecture that
invites a future caller to reach for it anyway, mistaking its plausible name for a guarantee it does
not provide. Its own test class's now-superseded cutoff test and its new concurrency-proof test
(added in PR #277) should be removed alongside it once the persisted-snapshot replay path has its
own tests covering the same ground (§28).

### X. Backward compatibility — the temporal boundary, stated honestly

`V6` attempts created **before** replay-input-snapshot persistence activates cannot be retroactively
made exactly replayable — the original transaction's MVCC visibility no longer exists anywhere to
recover, and Amendment 4 forbids fabricating a synthetic snapshot from `created_at` (that would just
reintroduce §B's own defect under a new name). This boundary is frozen explicitly, not left
implicit:

- **`DIAGNOSTIC_SELECTION_V6` attempts created before this amendment's implementation activates
  replay-input persistence:** best-effort audit only (whatever `diagnostic_probe_provenance` and
  ordinary logs already captured); **exact replay is not guaranteed and must never be represented as
  guaranteed.**
- **`DIAGNOSTIC_SELECTION_V6` attempts created after `DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1`
  activates:** exact replay is guaranteed, per §G–§O.

The mechanism for telling these apart at replay time is the snapshot header row's own *presence*
(§J), never a date comparison against when the feature "should" have been active.

### Y. Why this stays `DIAGNOSTIC_SELECTION_V6`, not a new policy version

**Decision: do not mint `DIAGNOSTIC_SELECTION_V6_1` or any successor selection-policy identifier.**
`V6`'s own live selection algorithm — activation conditions, enumeration, bound, ranking, fallback —
is completely unchanged by this amendment; only audit/provenance persistence *around* an unchanged
decision is added. This repository's own established minting discipline (seen throughout: a new
identifier is minted exactly when *decision-producing behavior* changes — e.g.
`DIAGNOSTIC_SCORING_V1`→`V2` when `FILL_BLANK` scoring was added, `DIAGNOSTIC_SELECTION_V4`→`V5`
when the hypothesis-driven probe selector was introduced) ties a policy-version bump to a behavior
change a learner's outcome could actually differ under. A learner's selected probe is bit-for-bit
identical whether or not this amendment's snapshot is persisted alongside it. Minting `V6_1` here
would misrepresent an audit-capability change as a selection-behavior change, and would force
`DiagnosticService`'s dispatch chain to carry two live policy identifiers for a distinction that
carries no decision-time meaning. The snapshot's own independent contract identifier
(`DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1`, §K) already carries the "is exact replay guaranteed for
this attempt" fact, fully decoupled from the selection-policy identifier — exactly mirroring how
`HYPOTHESIS_UNCERTAINTY_V1`/`HYPOTHESIS_DISCRIMINATION_V1` already carry their own engine-version
identifiers independent of whichever `DIAGNOSTIC_SELECTION_Vn` calls them.

### Z. Security/privacy

The persisted snapshot carries only technical decision provenance already present in this codebase's
existing candidate/hypothesis vocabulary: item-version ids, objective ids, relationship types, and
attempt ids. It must **never** carry raw learner free-text answers, LLM prompts or outputs, or any
sensitive profile data — none of which `V6`'s own inputs contain today, so this is a constraint on
the implementation PR's schema discipline, not a new capability this amendment introduces. Existing
tenant/learner isolation (every row FK-scoped to an attempt, which is FK-scoped to a learner) is
preserved unchanged.

### AA. AI boundary (reaffirmed)

`V6`'s persisted replay inputs capture only deterministic Java-governed candidate authority —
exactly the same `ProbeRelationshipService`/H4b resolution `V5` and live `V6` already use. An LLM,
MCP, an M2-ADR-032 advisory proposal, and the M2-ADR-033 misconception graph have, and retain, zero
authority over what is persisted or over how replay reconstructs a decision — the same boundary
Amendments 1–3 already froze, unchanged and unwidened here.

### BB. Normative golden scenarios (design-level; frozen expected behavior for the implementation PR)

| # | Scenario | Frozen expected behavior |
|---|---|---|
| A4-1 | Ordinary exact replay: decision snapshot persisted, later learner attempts occur, replay runs | Replay uses the persisted snapshot, not current exposure — identical candidate set, identical decision (activation/fallback and, where one was chosen, identical selected probe per `diagnostic_probe_provenance`) |
| A4-2 | Concurrent uncommitted earlier-created attempt (the §B scenario) | The destination attempt's own snapshot is built from its own live exposure read, which correctly excludes the concurrent attempt's items regardless of when the concurrent attempt later commits or what `created_at` it carries — replay of the destination attempt is unaffected by the concurrent attempt at any later point |
| A4-3 | A new probe candidate is published after the destination decision | Replay uses the persisted, original candidate set; the newly published item is never considered, even though a live `resolve()` call today would find it |
| A4-4 | The learner is later exposed to more items (further attempts) | Replay ignores current exposure entirely — it never queries exposure at all, live or cutoff-bounded; it reads only the persisted snapshot |
| A4-5 | The original decision fell back (e.g. `STEP1_INSUFFICIENT_EVIDENCE`, `ALL_SCORES_ZERO`) | Replay recomputes the same fallback reason from the persisted working set and reproduces the same final selected probe (via `V5`'s already-exact `diagnostic_probe_provenance` record, §S) — not a different, "currently correct" fallback |
| A4-6 | Idempotent retry with the same `Idempotency-Key` | Exactly one destination attempt, exactly one snapshot header row, no duplicate hypothesis/candidate rows, no recomputation — the existing idempotency short-circuit prevents `V6` from running a second time at all |
| A4-7 | Snapshot persistence fails (e.g. a constraint violation) | The entire attempt-creation transaction fails closed — no attempt, no packet, no provenance, no partial snapshot; nothing is left half-recorded |
| A4-8 | Replay is attempted against a pre-Amendment-4 `V6` attempt | No snapshot header row exists; replay must report "exact replay not available for this attempt" and must never fabricate one from `created_at` or any other timestamp |
| A4-9 | Later source attempt must not change replay: attempt A completes; destination attempt B is created and `V6` uses A as its source; attempt C (same learner, same assessment version) completes later; B is replayed | The snapshot's persisted `sourceAttemptId` names A; replay loads A and reproduces Step-1 evidence from A only; replay does **not** invoke `findMostRecentCompletedAttempt(...)` or any equivalent discovery; C is irrelevant to the replay regardless of when it completed; the original decision remains exactly reproducible |
| A4-10 | `NO_SOURCE_ATTEMPT` must not be overwritten by a later source: destination attempt B originally had no eligible source attempt (`sourceAttemptId = NULL` persisted); a source attempt later becomes available (e.g. an attempt completes after B was created); B is replayed | Replay reproduces `NO_SOURCE_ATTEMPT` unchanged, exactly as the original decision found it; the persisted `NULL` is authoritative and is never replaced by a source attempt that exists now but did not exist, or was not eligible, at B's own decision time |

### CC. Performance

`MAX_AUTHORIZED_HYPOTHESES_V6 = 4` (unchanged) bounds the snapshot to at most 4 hypotheses' worth of
candidate-probe rows per attempt; candidate probes per hypothesis are themselves bounded by
curriculum content and are typically small (single digits). Worst case is on the order of a few
dozen rows per `V6` attempt — negligible next to the `assessment_attempt_item`/`assessment_response`
rows every attempt already writes. No full learner-exposure-history copy is needed, because the
persisted boundary is the *working set*, not the exposure set it was filtered from (§G).

### DD. Revisit triggers added by this amendment

- If `V7` or any successor selection algorithm is introduced, whether it reuses
  `DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1`'s shape or needs its own contract version is a decision
  for that algorithm's own ADR, not assumed here.
- If RAMALS ever deliberately chooses a stronger transaction isolation level (e.g. `SERIALIZABLE`)
  for attempt creation, or introduces learner-level attempt serialization (an advisory lock or
  equivalent), re-examine whether §B's defect is closed at the source — this amendment's persisted
  snapshot would then become defense-in-depth rather than the sole guarantee, and that is itself a
  decision worth recording, not a silent simplification.
- If a new candidate-authority source (beyond `ProbeRelationshipService`/H4b resolution) is ever
  introduced, its interaction with the persisted working-set boundary needs its own review.
- If `core.diagnostic_probe_relationship`'s immutability-once-published guarantee is ever relaxed
  (e.g. a future "retract" or "supersede" lifecycle state), §E's Category-A classification of
  published relationship rows must be re-examined — this amendment's design assumes today's
  guarantee holds.
- If the persisted snapshot's own contract ever needs a breaking change, mint
  `DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V2` and leave `_V1` — and every snapshot already recorded
  under it — untouched, exactly the discipline `EngineVersionFreezeTests` already holds every other
  frozen identifier to.
- If RAMALS becomes multi-tenant with sharded storage, re-examine whether the FK-backed, single-schema
  design in §L still holds, or needs tenant-scoping this amendment does not anticipate.

### EE. ADR diff summary (this amendment)

- **New top-level section**, appended after Amendment 3 — Amendment 3's own text is unchanged (§A).
- Corrects the specific §V clauses enumerated in §A; introduces no change to Amendments 1–2 or to
  Amendment 3's own activation/fallback/enumeration/bound rules (§E–§P there).
- Freezes the concurrency defect as an architectural fact (§B), backed by an executable proof already
  merged in PR #277.
- Rejects every timestamp/sequence/UUID-ordering alternative, and internal MVCC-identifier coupling,
  as insufficient (§C).
- Freezes the governing principle (§D), the Category A/B input split (§E–§F), the persisted replay
  boundary (§G), exact hypothesis/candidate identity (§H–§I), snapshot scope (§J), a dedicated
  snapshot contract identifier (§K), a conceptual (non-final) logical schema (§L), atomicity (§M),
  idempotency (§N), the replay algorithm (§O), the curriculum-growth non-goal (§P), Step-1 evidence
  reuse (§Q), engine-version replay compatibility (§R), the decision-replay-vs-selected-probe-
  verification finding (§S), fallback-reason persistence as an audit value (§T), and the
  unpersisted-score decision retained (§U).
- Answers the migration question explicitly: **YES**, for exact replay only; **NO** migration was
  ever needed for live selection (§V).
- Freezes disposition of `findLearnerExposedLogicalItemIdsBefore`: **remove**, in the implementation
  PR (§W).
- Freezes the pre-/post-Amendment-4 replay guarantee boundary honestly, with no retroactive
  fabrication (§X).
- Freezes that `DIAGNOSTIC_SELECTION_V6`'s own policy identifier does **not** change (§Y).
- Reaffirms privacy/security and AI-boundary constraints on the new persisted data (§Z–§AA).
- Adds ten normative golden scenarios, `A4-1` through `A4-10` (§BB).
- Adds five revisit triggers specific to this amendment (§DD, this section's predecessor).
- **Review-round corrections (same PR, before merge — this amendment's own draft edited directly,
  not superseded, since it had not yet been ratified — the same discipline Amendment 3's own §CC
  already used for its pre-merge review-round fixes):**
  - **§E corrected — a normative contradiction is fixed.** The earlier draft of this amendment's own
    Category A listed "the source attempt's own id" as reconstructable and unpersisted, on the
    reasoning that `core.assessment_attempt` rows are never deleted. That reasoning proves only that
    a *known* attempt's row content is permanent — it does not prove that *which* attempt was used
    can be rediscovered later, and directly contradicted §F/§G/§I/§L's own (correct) requirement
    that `sourceAttemptId` be persisted. §E now states explicitly that it reconstructs content only,
    never identity, with a boundary statement pointing to §F.
  - **§F expanded** with a third, explicit Category B item — "which source attempt the decision
    actually used" — including the time-sensitivity example (`findMostRecentCompletedAttempt(...)`
    can return a different, later attempt at replay time than the one the original decision used)
    and the frozen rule that `sourceAttemptId` is authoritative decision-time provenance, persisted
    unconditionally (including as `NULL` for `NO_SOURCE_ATTEMPT`).
  - **§G's opening statement and closing paragraph corrected** to name source-attempt identity as
    part of the persisted boundary, not only the working set/candidate-probe set; its diagram
    annotated accordingly.
  - **§I clarified**: `destinationAttemptId`/`sourceAttemptId` are carried once, on the header row
    (§L), not necessarily repeated per candidate row — reconciling §I's prose with §L's own schema.
  - **§L's `source_attempt_id` column commentary expanded** to state explicitly that it is
    authoritative decision-time provenance, never re-derived, and never a cache of a value replay
    could otherwise compute.
  - **§O (replay algorithm) strengthened** with an explicit branch on the persisted `sourceAttemptId`
    (including the `NULL` case) and an explicit, standalone prohibition on replay ever invoking
    `findMostRecentCompletedAttempt(...)` or any equivalent "latest completed attempt" discovery.
  - **§D's governing-principle statement broadened**, in place, to name explicitly the two kinds of
    non-reconstructable decision-time state this amendment now covers — MVCC visibility (§B) and a
    time-sensitive "most recent" query result (§F) — rather than only the former.
  - **§Q clarified** to state the id-vs-content distinction explicitly, rather than only implying it.
  - **§BB expanded** with two new golden scenarios, `A4-9` (a later-completing attempt must not
    change a replay whose source was already fixed) and `A4-10` (`NO_SOURCE_ATTEMPT` must not be
    overwritten by a source attempt that becomes available only after the original decision).
  - None of these corrections reopen §B–§C, §E's own remaining bullets, §H, §J, §K, §M–§R, §T–§AA, or
    golden scenarios `A4-1`–`A4-8`: every other Amendment 4 decision (`created_at`'s insufficiency,
    every rejected alternative, persisted hypothesis/candidate identity, compute-on-read scores, the
    unconditional snapshot header, atomicity, the unchanged policy identifier,
    `findLearnerExposedLogicalItemIdsBefore`'s removal, best-effort pre-snapshot replay) stands
    exactly as first drafted.
- **Authorizes no code and no migration.** The implementation PR that follows ratification creates
  the schema in §L, wires persistence into `DiagnosticService.createAttempt`, removes
  `findLearnerExposedLogicalItemIdsBefore` and its superseded tests, and must not merge PR #277 until
  this amendment is ratified — PR #277 remains open, carrying the corrected (non-exact-replay-
  claiming) documentation from its own review round, until this amendment's implementation lands.
