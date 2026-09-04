# M2-ADR-023: Prerequisite-aware diagnosis is evidence, not a gate; diagnostic confidence is a separate, versioned, deterministic construct

- **Status:** Proposed
- **Decides:** the constraints binding the prerequisite-aware diagnostic-reasoning roadmap (H1–H7)
  before any of H2, H5, or H7 is built — how a weak prerequisite may influence selection and
  reporting, what "diagnostic confidence" is and is not, and how the deferred retention/
  reassessment policy relates to root-cause verification.
- **Relates to:** `core.skill_prerequisite` (V003), `DIAGNOSTIC_SELECTION_V2` / `AdaptiveDiagnosticSelector`
  (PR-B, #244/#245), `MasteryStatusPolicyV2`/`EvidenceConfidenceCalculatorV2` (PR #240), M1-ADR-010 and
  M2-ADR-010 (AI evaluation remains proposal-only; the deterministic core decides).
- **Does not relate to, and must not be confused with, M2-ADR-022.** That number is separately
  reserved for resolving the M1-ADR-010-vs-MVP-2 conflict over `RECORD_EVALUATION_EVIDENCE` writing
  `ledger.evidence` — a different, still-open decision, gated ahead of free-text (SHORT_ANSWER/
  USE_CASE) evaluation. This ADR is numbered `023` specifically so it cannot collide with `022` once
  that one is written.
- **Originates here**, on the same basis as M2-ADR-016 through M2-ADR-021: a repository-native
  decision, not part of the accepted MVP-2 package.

## Context

PR-B shipped a selector that escalates difficulty per skill from its own evidence, but treats every
skill as an independent track. It has no notion that `KAFKA_TOPIC` depends on `KAFKA_BROKER`, even
though `core.skill_prerequisite` has carried that edge since V003 and is already read by
`CurriculumGraph`/`CurriculumGraphValidator` and by `ProgressionPolicy` for learning-path sequencing.

The next slice of work (H1–H7, agreed in project discussion) closes that gap: read the prerequisite
DAG alongside mastery evidence, so a diagnosis can say *why* a skill looks weak — inherited from an
unsecured prerequisite, or independent — rather than reporting five flat, unexplained numbers.

That is valuable, and it is also exactly the kind of change this project has learned to write down
before building, not after: it is easy to build correctly on the first attempt and easy to erode
one convenient shortcut at a time on the second. Three shortcuts in particular are worth naming now,
because each has an obvious, tempting, wrong version:

1. Treating "prerequisite not secured" as a reason to **stop testing** the dependent skill, rather
   than a reason to trust its own evidence less.
2. Treating "diagnostic confidence" (how sure the system is that a *specific weak skill causes*
   another skill's weakness) as the same number as, or a trivial derivative of, **mastery
   confidence** (how sure the system is that a *single skill's own score* is trustworthy) — or
   worse, as something an AI model classifies.
3. Treating H7's "verify the diagnosis by reassessing after remediation" as free licence to reuse
   the recency/repeat machinery loosely, rather than as the already-deferred retention/
   spaced-reassessment policy it actually is.

## Decision

### 1. The prerequisite graph is diagnostic evidence, never a selection gate

A skill whose prerequisite(s) have not reached `MASTERED` status may have its target difficulty
capped (e.g. held at FOUNDATIONAL regardless of what its own evidence would otherwise justify) and
its selection priority adjusted so the selector spends more of a packet confirming the prerequisite.

**It must never be excluded from selection because a prerequisite is unsecured.** Real learners
arrive with fragmented, non-linear knowledge; a hard gate would silently convert this platform from
an evidence-gathering instrument into a rigid, curriculum-order enforcement engine, and would do so
by omission — a skill that is never tested produces no evidence, and an absence of evidence is not
distinguishable downstream from a skill nobody needed to test.

Any cap or reprioritization applied for this reason must be recorded with its own explicit,
auditable selection reason (the same discipline `SelectionReason` already holds every V2 reason to)
— never a silent adjustment folded into an existing reason's meaning.

### 2. Diagnostic confidence is a distinct, versioned, deterministic construct

"How confident is the mastery engine in skill X's own score" (`evidenceConfidence` on
`MasterySnapshot`, already computed by `EvidenceConfidenceCalculatorV2`) and "how confident is the
system that skill X's weakness explains skill Y's weakness" are different questions with different
answers, and reporting the second as if it were a reading of the first overstates certainty the
system does not have.

Where a diagnostic/causal confidence number is introduced (H5), it must:

- be computed by a **named, versioned calculator** with a frozen behaviour vector, the same
  discipline `EvidenceConfidenceCalculatorV2` and `WeightedMasteryCalculator` are already held to —
  not an inline heuristic that can drift silently across commits;
- be **deterministic and reproducible** from already-authoritative inputs (mastery status/score/
  confidence, prerequisite graph distance, evidence volume, corroborating vs. contradictory
  evidence) — never a number an AI model assigns or adjusts;
- **never feed back into mastery computation.** It explains a diagnosis; it does not revise
  `WEIGHTED_MASTERY_V1`, `EVIDENCE_CONFIDENCE_V2`, or `MASTERY_STATUS_POLICY_V2`, all of which
  remain frozen and authoritative exactly as prior decisions left them.

This is the same invariant M1-ADR-010 and M2-ADR-010 already hold for evaluation: the deterministic
core decides; nothing upstream of it is authoritative by default, including a new kind of
confidence this ADR is the one introducing.

### 3. H7 (diagnosis verification via reassessment) inherits the deferred retention constraint

Confirming a root-cause hypothesis by reassessing a skill after remediation is, mechanically, the
spaced-reassessment/retention capability that was explicitly deferred during PR-A/PR-B's no-repeat
work: legitimate future retention testing was not designed away, but it was also explicitly kept out
of ordinary bank-exhaustion handling.

H7 does not get to reuse that deferred territory implicitly by virtue of having a better reason for
it. When H7 is actually scoped, it requires its own explicit decision — this ADR only records that
the constraint applies to it, not that the constraint is satisfied by anything in H1–H6. In
particular: reassessing a skill to verify a diagnosis must remain distinguishable, in the audit
trail, from ordinary adaptive selection reaching bank exhaustion, and must not become a route by
which either quietly authorizes the other.

### 4. Scope: this ADR binds design, it does not authorize construction

Accepting this ADR does not approve building H2, H5, or H7. It fixes the constraints those PRs must
be reviewed against when they are proposed. H1 (`GapDiagnosisService`) needs no exception from
anything here — it is read-only, touches no mastery computation, and introduces no new confidence
construct; it is unaffected by §2 and only informs the *reporting* side of §1, not selection.

## Alternatives rejected

- **Hard-gate a dependent skill until its prerequisite is mastered.** Simpler to implement and
  actively wrong: it produces silence instead of evidence for exactly the learners most worth
  diagnosing — the ones with real but non-linear knowledge.
- **Reuse `evidenceConfidence` directly as diagnostic/causal confidence**, since it is already
  computed and already on `MasterySnapshot`. Rejected because it answers a different question;
  presenting it as causal confidence would overstate certainty about attribution the number was
  never computed to support.
- **Let an AI model rank or classify root-cause hypotheses**, since natural-language explanation is
  exactly the kind of thing a model is good at. Rejected on the same grounds M1-ADR-010/M2-ADR-010
  already settled for evaluation: an AI-authored classification that determines what a learner is
  told their gap is would be a new authoritative AI role this project has consistently refused to
  grant anywhere else.
- **Treat H7 as already covered by PR-B's existing no-repeat/exhaustion handling.** Rejected because
  PR-B's own design explicitly walled off retention/spaced reassessment from ordinary exhaustion for
  a reason; H7 reusing that machinery without its own decision would quietly reopen a question that
  was deliberately left closed.

## Consequences

- H2 (`DIAGNOSTIC_SELECTION_V3`) must implement prerequisite state as a difficulty cap and priority
  modifier only, with its own selection reason(s); code review should treat any exclusion-based
  implementation as a defect against this ADR, not a valid alternative.
- H5 must mint a versioned, frozen-hash diagnostic-confidence calculator before it ships, following
  the `EngineVersionFreezeTests` pattern already established for every other scored/ranked engine in
  this codebase.
- H7 remains unscoped by this ADR. Its own design must state explicitly how it stays distinguishable
  from ordinary bank exhaustion, and should be reviewed as inheriting this constraint rather than
  re-litigating it.
- H1 may proceed without further governance review; nothing in this ADR blocks it.

## Revisit triggers

- If H2's real-world behaviour shows prerequisite-capping is insufficient and a genuine gate is
  wanted for a specific, named pedagogical reason, that reverses §1 and needs its own ADR, not a
  quiet code change.
- If diagnostic confidence is ever found to need model input to be useful, that is an AI-authority
  boundary change and must go through the same scrutiny M1-ADR-010/M2-ADR-010 received, not a
  silent addition to H5.
- When H7 is scoped, its own decision should supersede or extend §3 rather than treat it as already
  fully specified here.

## Note on the ADR register

`docs/adr/M2-ADR-register.md`'s table currently stops at M2-ADR-019; M2-ADR-020 and M2-ADR-021
already exist as files but were never added to it, and this predates this ADR. This entry adds
M2-ADR-023 to the register without otherwise correcting that pre-existing gap.
