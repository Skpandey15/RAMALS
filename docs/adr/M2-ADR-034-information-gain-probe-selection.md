# M2-ADR-034: Information-gain diagnostic probe selection (`DIAGNOSTIC_SELECTION_V6` / `INFORMATION_GAIN_V1`) — design only

- **Status:** Proposed
- **Date:** 2026-09-08
- **Decides:** the design constraints binding a future deterministic information-gain diagnostic
  probe-selection policy — a `DIAGNOSTIC_SELECTION_V6` composed strictly after frozen `V5`, and a
  named, versioned, frozen `INFORMATION_GAIN_V1` construct — including how diagnostic-hypothesis
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
- **This ADR authorizes no code, no migration, no contract change, and no `SelectionReason` value.**
  `V6` is not implemented in this PR. Frozen calculators and `EngineVersionFreezeTests` are
  untouched.

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

### 2. `DIAGNOSTIC_SELECTION_V6` composes after frozen `V5` — never a rewrite

- Composition becomes `V3 → V4 → V5 → V6 → frozen V2`, mirroring exactly how `V3`/`V4`/`V5` already
  wrap `V2` (M2-ADR-025 §1). `V6` is a wrapper adjustment over `V2`'s two existing inputs (the
  per-skill signal map and the candidate pool), never a replacement selection algorithm, and never a
  change to `V1`–`V5` code, to the composition order among `V1`–`V5`, to
  `MAX_HYPOTHESIS_PROBES_PER_PACKET`, or to the shape of `core.diagnostic_probe_relationship` /
  `core.diagnostic_probe_provenance`.
- `V6` acts only when a bounded, well-formed hypothesis set with a computed posterior exists for the
  attempt being created; otherwise it degrades to no adjustment — the same "degrades to no
  adjustment, never breaks attempt creation" guarantee M2-ADR-025 §2 gives `V5`.
- **Precedence.** `V6` runs after `V5`, so on a shared skill `V6`'s reason/priority win — by the same
  "composition order is the frozen rule, tested explicitly" mechanism M2-ADR-025 §5 uses for `V4`
  versus `V5`. The implementation design PR must state and test this precedence explicitly and
  justify its direction (the information-gain-optimal probe is the more specific evidence-seeking
  action; if the design concludes the reverse ordering is correct it must say so and compose
  accordingly) — the ordering is a frozen decision, never an accident of which wrapper runs last.
- **Quota.** `V6` selects at most one probe per packet, enforced structurally the way M2-ADR-025
  §3/§6 enforces `V5`'s — by restricting `V2`'s input pool, not by counting probes served.

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
- **Output:** a deterministic real-valued expected-information-gain score per candidate probe, and a
  total order over candidates with ties broken by an explicit, documented, deterministic key — never
  by SQL row order (the discipline M2-ADR-024 §5 and M2-ADR-025 §4 already enforce). The score is
  evidence-acquisition value only. It is never a diagnosis, never a learner-facing number, never
  mastery, and never root-cause truth.
- The design PR chooses and freezes the concrete method — entropy reduction over the hypothesis
  posterior, expected KL divergence, expected posterior-variance reduction, or a bounded
  deterministic scoring rubric. The brief's constraint is adopted verbatim: *do not over-engineer
  this into an ML system prematurely.*

### 4. Hypothesis uncertainty / posterior representation

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
