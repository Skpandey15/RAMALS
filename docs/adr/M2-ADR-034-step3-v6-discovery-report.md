# M2-ADR-034 Step 3 — `DIAGNOSTIC_SELECTION_V6` discovery & design-readiness report

**Status:** Discovery only. Not an ADR, not an amendment, authorizes nothing.

**Date:** 2026-09-11 (revised same day — see correction note below)
**Repository state inspected:** `main` @ `2c4bda7` (M2-ADR-034 Steps 1–2 merged: `HYPOTHESIS_UNCERTAINTY_V1`
in PR #273, `HYPOTHESIS_DISCRIMINATION_V1` in PR #275; `DIAGNOSTIC_SELECTION_V1`–`V5` untouched;
`DIAGNOSTIC_SELECTION_V6` does not exist).

No production code, migration, or test was changed to produce this report. It is pure discovery.

> **Correction (same day).** The first draft of this report over-concluded, in §11, that Step 1
> "necessarily sees zero evidence" at V6 selection time and that V6 must therefore always be a
> no-op. That was too strong: the *destination* attempt does have zero evidence at selection time,
> but the *immediately preceding completed source attempt* — already used today to derive the
> diagnostic hypothesis — may itself carry governed probe-response evidence, and
> `HypothesisUncertaintyContextAssembler`/`HypothesisUncertaintyRepository` accept any attempt id as
> `interactionId`. The ADR was ambiguous about *which* interaction supplies Step-1 evidence, not
> proven to make V6 permanently inert. §11 below is rewritten accordingly, with a corrected,
> sharper central finding (the two-level collapse and the mathematical necessity of bounded
> multi-hypothesis enumeration). **`docs/adr/M2-ADR-034-information-gain-probe-selection.md`
> Amendment 3 (2026-09-11)** now freezes this and every other decision §23 originally listed. The
> final verdict of this report is unchanged: implementation may not begin until Amendment 3 is
> reviewed and merged.

---

## 1. Repository state

- `HYPOTHESIS_UNCERTAINTY_V1` — `io.ramals.learningplatform.assessment.hypothesisuncertainty` —
  implemented, inert, 11 golden vectors frozen in `EngineVersionFreezeTests`, architecture-guardrail
  enforced (no selector consumer, no DB dependency in the calculator itself, no M2-ADR-033 input).
- `HYPOTHESIS_DISCRIMINATION_V1` — `io.ramals.learningplatform.assessment.hypothesisdiscrimination` —
  implemented, inert, 11 golden vectors frozen, architecture-guardrail enforced (no selector
  consumer, no M2-ADR-032 candidate-eligibility widening, no `INFORMATION_GAIN_V1` constant anywhere
  in main sources).
- Both are pure, deterministic, side-effect-free calculators. Neither has a real runtime caller.
  `HypothesisUncertaintyContextAssembler` (Step 1's own DB-touching companion) exists but is called
  by nothing. Step 2 has no assembler at all (a deliberate scoping decision made during Step 2's
  implementation — see §7).
- `DIAGNOSTIC_SELECTION_V1`–`V5` are unchanged since before this work began; `V6` does not exist as
  a class, string constant, or `SelectionReason` value anywhere in main sources.

---

## 2. `DIAGNOSTIC_SELECTION_V1`–`V5` map (code-verified, not name-inferred)

| Version | Class | Purpose (own javadoc) | Input | Candidate pool type | Selection rule | Fallback / empty pool | Runtime consumer |
|---|---|---|---|---|---|---|---|
| **V1** | `DiagnosticFormSelector` | Assembles one diagnostic form from the eligible item pool; pure function of its inputs. | `List<EligibleItem>` | `EligibleItem` | Sort unseen-first/least-recent/random/id, then three passes: skill coverage → difficulty coverage → fill to target size. | `EmptyItemPoolException` — hard failure, no fallback. | Yes — `DiagnosticService.selectLegacyForm`, the default branch when `selectionPolicy` matches none of V2–V5. |
| **V2** | `AdaptiveDiagnosticSelector` | Assembles one adaptive packet from an exposure-filtered pool and per-skill mastery signals. | `List<AdaptiveEligibleItem>` + `Map<String,SkillMasterySignal>` | `AdaptiveEligibleItem` | Round-robin over skills by `signal.priority()` ascending; `bestCandidate` picks the item whose band is closest to but never above `targetDifficulty()`. | Returns a possibly-partial packet (never throws itself); `DiagnosticService` throws `AssessmentBankExhaustedException` if the packet ends up empty. | Yes — `DiagnosticService.selectAdaptiveForm`, and as the terminal call inside V3/V4/V5's own methods. |
| **V3** | `PrerequisiteAwareDiagnosticSelector` | Whether a skill's evidence can be trusted given the prerequisite graph — a pure signal-map transform, not an independent selector. | Signal map + prerequisite graph + mastery status map | none of its own — reuses V2's pool | Demotes a skill to `FOUNDATIONAL`/`PREREQUISITE_NOT_SECURED` if any prerequisite isn't `MASTERED`; else passes through unchanged. | N/A (no pool of its own); returns input unchanged when its condition doesn't apply. | Yes — `selectPrerequisiteAwareForm`, and as a pre-step inside V4/V5's methods. |
| **V4** | `HypothesisConfirmationDiagnosticSelector` | H4a: cross-attempt regression confirmation — reprioritizes a skill whose latest mastery snapshot regressed vs. the one before it. | Signal map + caller-supplied `Set<String> regressedSkillCodes` (V4 does not detect regressions itself) | none of its own — reuses V2's pool | Sets `reason=HYPOTHESIS_CONFIRMATION, priority=0` for every regressed skill; unchanged otherwise. | Returns `baseSignals` unchanged (same instance) if the regressed set is empty. | Yes — `selectHypothesisConfirmingForm`, and as a pre-step inside V5's method. |
| **V5** | `HypothesisDrivenProbeDiagnosticSelector` | H4b runtime consumer — the only one of V3/V4/V5 that mutates the *pool*, not just the signal map. | Signal map + pool + a nullable `Selection` (hypothesis, sourceAttemptId, targetSkillCode, chosenItemVersionId) | `AdaptiveEligibleItem` | If `selection==null`, pass through unchanged. Else reprioritize the target skill (`reason=HYPOTHESIS_DRIVEN_PROBE, priority=0`) and strip every *other* item of that skill from the pool, leaving `chosenItemVersionId` as V2's only option for it. | `selection==null` → V2 runs on V4's untouched output; no special handling inside V5 itself. | Yes — `selectHypothesisDrivenProbeForm`. |

**Composition**: `DiagnosticService.selectForm` dispatches on ONE persisted `selection_policy_version`
string (`core.assessment_version`) to exactly ONE of the five `selectXxxForm` methods — this is a
one-time static routing decision, not a per-attempt runtime cascade (see §10).

---

## 3. Current end-to-end selection flow (exact code)

```
DiagnosticService.createAttempt(subject, domainCode, idempotencyKey)   [@Transactional]
        |
        v
repository.findPublishedDiagnostic / findByIdempotency / findActiveAttempt
        |
        v
repository.findSelectionPolicyVersion(versionId)  ->  "DIAGNOSTIC_SELECTION_V5"
        |
        v
repository.insertAttempt(...)                      <- the new attempt row exists NOW,
        |                                              with ZERO responses/items yet
        v
selectForm(attempt, learnerId, diagnostic, "DIAGNOSTIC_SELECTION_V5")
        |
        v
selectHypothesisDrivenProbeForm(attempt, learnerId, diagnostic)
        |
        +-- resolveAdaptiveInputs(learnerId, assessmentVersionId)
        |       -> full pool, exposure-filtered unseenPool, per-skill mastery signals
        |
        +-- PrerequisiteAwareDiagnosticSelector.adjustForPrerequisites(...)      [V3]
        |
        +-- detectRegressedSkills(...)                     <- DiagnosticService's own logic
        +-- HypothesisConfirmationDiagnosticSelector.adjustForRegressions(...)   [V4]
        |
        +-- resolveHypothesisProbeSelection(learnerId, diagnostic, inputs)   <-- THE SEAM (§5, §6)
        |       for each miss in sourceAttempt, in presentation_order:
        |         for each ProbeRelationshipType in RELATIONSHIP_TYPE_PRIORITY:
        |           probeRelationshipService.resolve(missedItem, type, learnerId)
        |           if CANDIDATES_AVAILABLE:
        |             chosenItemVersionId = resolution.candidates().get(0)   <-- collapse to 1
        |             return Selection(hypothesis, sourceAttemptId, skillCode, chosenItemVersionId)
        |       (first hit wins; everything else is never evaluated)
        |
        +-- HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe(...)   [V5 proper]
        |
        +-- adaptiveSelector.select(adjusted.pool(), adjusted.signals(), random)    [V2, unmodified]
        |       <- this is the ONLY method that actually picks items
        |
        +-- finishAdaptiveSelection(...)  -> repository.insertSelectedItems(...)   [persistence]
        |
        +-- if (probeSelection != null && packet contains chosenItemVersionId):
                probeProvenanceRepository.insert(attempt.id(), probeSelection)     [provenance]
```

- **Transaction boundary**: the entire flow above — repository reads, filtering, quota, hypothesis
  construction, V5's resolution call, provenance insert, item persistence — runs inside ONE
  `@Transactional` method, `createAttempt`. `ProbeRelationshipService.resolve` is itself
  `@Transactional(readOnly = true)` but joins the caller's transaction (default propagation).
- **No-repeat exclusion**: `repository.findLearnerExposedLogicalItemIds` filters both the general
  pool (`unseenPool`) and, inside `ProbeRelationshipResolver`, the per-hypothesis candidate list.
- **Quota**: `MAX_HYPOTHESIS_PROBES_PER_PACKET = 1` is enforced *structurally*, not counted —
  `resolveHypothesisProbeSelection` only ever returns one `Selection`, and V5's pool restriction
  leaves only one item of the target skill for V2 to possibly pick.
- **Response/submission side** (a separate transaction, `DiagnosticSubmissionService.submit`):
  scores the response, `DiagnosticConfidenceService` reads back `core.diagnostic_probe_provenance`
  to classify the outcome and (for H5) records a confidence observation. This is where
  `HypothesisEvidenceOutcome` classification actually happens against real data.

---

## 4. The exact V5 candidate-resolution seam

**Within one already-resolved hypothesis**, a full bounded list of eligible, unseen candidate items
genuinely exists as a first-class value: `ProbeRelationshipResolver.resolve(...)` returns
`ProbeResolution.candidates() : List<ProbeCandidateItem>` when `outcome == CANDIDATES_AVAILABLE`
(exactly one target objective resolved). `DiagnosticService` collapses this to one item by
`resolution.candidates().get(0)` — the first element of an already-deterministically-ordered list
(`display_order, item_code`), no scoring.

**Across hypotheses, no such list exists.** `ProbeRelationshipService.resolve(triggerItemVersionId,
relationshipType, learnerId)` takes exactly ONE trigger item and ONE relationship type — there is no
batch method. `DiagnosticService.resolveHypothesisProbeSelection` calls it inside a nested loop
(misses × `RELATIONSHIP_TYPE_PRIORITY`) and **returns immediately at the first `CANDIDATES_AVAILABLE`
hit** — a real `return` statement inside the loop body, not a `break`-and-continue. Every other miss
and every other relationship type is never evaluated once one hit is found. **Only one hypothesis is
ever "live" per attempt.**

This is the single most consequential fact in this report (expanded in §11).

---

## 5. Candidate authority analysis

**Mechanically enforceable today?** Partially.

- Within one hypothesis, YES: a `CandidateProbe` adapter could read `ProbeResolution.candidates()`
  directly (instead of `.get(0)`) and construct one `CandidateProbe` per `ProbeCandidateItem`,
  without touching `ProbeRelationshipResolver`, `ProbeRelationshipService`, or any repository query
  shape. `scoreable` would always resolve to `true` from this real path, since
  `ProbeRelationshipRepository.itemsForObjective`'s own javadoc guarantees "every verified,
  **scoreable** item" — confirming Step 2's own discovery-report conclusion that `scoreable=false`
  is direct-construction-only.
- Across hypotheses, NO — there is no existing mechanism (see §11) to produce more than one
  candidate hypothesis, and thus no existing mechanism to produce candidate probes for more than one
  hypothesis simultaneously, without a genuine control-flow change (not merely reading a value that
  already exists).
- **ADR-032 proposals cannot leak in mechanically today**: `DiagnosticProbeProposal` types live in
  `io.ramals.learningplatform.diagnosticassessment`, a package `resolveHypothesisProbeSelection` and
  `ProbeRelationshipService` never import or depend on. Nothing in the current selection code path
  touches that package. (Step 2's own architecture guardrail test,
  `packageCannotReachAdvisoryProbeProposal`, already proves the *discrimination calculator* can't
  reach it either.) **No accidental feed path exists today.** The risk is purely in a *future* V6
  implementation choosing to add one — which is exactly what Amendment 2 §V's revisit trigger and
  M2-ADR-032 §6 both require a separate, explicit ADR to authorize.

**Target architecture from the prompt is achievable** for the single-hypothesis case with no V1–V5
changes. It is **not yet achievable** for any multi-hypothesis case, because the thing to feed
`HYPOTHESIS_DISCRIMINATION_V1` (a comparison across ≥2 hypotheses) doesn't exist as a producible
value anywhere in the current runtime.

---

## 6. Hypothesis / evidence flow

- `DiagnosticHypothesis` is constructed in exactly one place: `ProbeRelationshipResolver.resolve`,
  the instant exactly one target objective is found for a (trigger item, relationship type) pair.
- Authorizing evidence: an incorrect response on an item in the learner's immediately preceding
  **completed** attempt of the same assessment version, combined with that item's single tagged
  objective and a deterministically-resolved single target objective.
- Ordering is fully deterministic: misses walked in `presentation_order`; relationship types walked
  in the frozen `RELATIONSHIP_TYPE_PRIORITY` list; every repository query behind target resolution is
  itself `ORDER BY`-stabilized.
- There is **no phase that first enumerates "the current hypothesis set" and only then looks for
  probes** — hypothesis discovery and candidate-probe discovery are fused into the same
  `ProbeRelationshipService.resolve` call, per (miss, type) pair, one at a time.
- **Amendment 2 does not freeze this.** It freezes what `HYPOTHESIS_DISCRIMINATION_V1` does *given* a
  `baseContext`/`baseResult` and a `List<CandidateProbe>` — it is deliberately silent on how a
  multi-member candidate hypothesis set would ever be produced. That silence is a real, load-bearing
  gap for V6 specifically (§11).

---

## 7. Step-1 integration feasibility

`HypothesisUncertaintyContextAssembler.assemble(UUID interactionId, List<DiagnosticHypothesis>
candidates)` can be called directly and reused as-is — its signature takes exactly what a future
caller would have: an interaction id and a hypothesis list. All the fields
`HypothesisUncertaintyContext` needs (learner is implicit via `interactionId`→attempt; domain;
candidate hypotheses; per-interaction evidence) are resolvable through the assembler's own existing
repository reads. **No second assembler is needed or should be created** — the existing one is
general enough.

**However** (see §11), at the natural point `resolveHypothesisProbeSelection` runs — attempt creation,
before any response exists in the new attempt — `interactionId = attempt.id()` would have **zero**
governed evidence rows (`core.diagnostic_probe_provenance` join `core.assessment_response`, both
scoped to `attempt_id`), because nothing has been answered yet in an attempt that doesn't even have
its items persisted yet. This is not an assembler defect — the assembler would work exactly as
designed — it's a question of *which* `interactionId` a V6 caller should even pass it, and the honest
answer under Amendment 1 §F's "per-interaction only" freeze is: **there is no interactionId that
would produce non-empty evidence for a hypothesis at the moment it is first being considered for
probing.** See §11 for the full analysis and §16–18 for the consequences.

---

## 8. Step-2 integration feasibility

`HypothesisDiscriminationCalculatorV1.calculate(HypothesisDiscriminationContext)` can be called
directly with `baseContext`/`baseResult` from Step 1 and a `List<CandidateProbe>` built from
`ProbeResolution.candidates()` (§5). No second implementation of TVD, no reinterpretation of Step 1
needed — the merged APIs are already exactly shaped for this. The blocking issue is not Step 2's own
contract; it's what feeds `baseResult.status()` in the first place (§11) — if Step 1 is always
`INSUFFICIENT_EVIDENCE`/`NOT_APPLICABLE` at the moment V6 would run, Step 2 is always
`NOT_APPLICABLE` too, and the entire discrimination step is vacuous regardless of how faithfully it's
wired.

---

## 9. Ranking analysis

Amendment 2 §K's frozen ranking (`score DESC → hypothesis canonical order ASC (Amendment 1 §H) →
probeItemVersionId ASC`) does not conflict with any *existing* V5 ordering, because V5 has no ranking
of its own to conflict with — its "ordering" today is a control-flow accident (first miss by
`presentation_order`, first type by `RELATIONSHIP_TYPE_PRIORITY`, first item by
`display_order/item_code`), not a scored comparison. §K's ranking *could* be consumed verbatim for
"which of several already-produced `CandidateDiscrimination`s wins" — but there is no conflict to
report because there is currently nothing else V6 would be ranking against (RELATIONSHIP_TYPE_PRIORITY,
difficulty, curriculum order are all upstream filters/orderings that produce the candidate SET, not
alternative ranking rules over an already-formed set). **This is not an ADR question** — no conflict
exists to escalate.

---

## 10. Fallback analysis

There is **no cross-version runtime cascade** (V5 fails → V4 → V3 → V2 as separate top-level
branches). `DiagnosticService.selectForm` picks exactly one `selectXxxForm` method once, based on the
attempt's persisted `selection_policy_version`. Within that one method, "fallback" is a pure-data
no-op: `Adjusted adjustForHypothesisProbe(..., selection=null)` returns its inputs unchanged, so
V4's (and, through it, V3's) already-computed output flows into V2's unmodified `select()` exactly as
if V5 had never run. `finishAdaptiveSelection` and provenance-insert still execute; the provenance
write is simply skipped (guarded by `probeSelection != null`).

**Where V6 belongs**: not as a new top-level branch in `selectForm`, and not as a new wrapper stacked
after V5 (Amendment 2 §2 explicitly forbids both). It belongs *inside* whatever replaces
`resolveHypothesisProbeSelection`'s decision of which `Selection` to return — i.e., V6 is a strategy
for producing the `Selection` value that `HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe`
(unmodified) already accepts. When V6 can't produce a ranked choice, the fallback is **not** "run V5
again" (V5's own `adjustForHypothesisProbe` never re-runs a decision) — it's "the `Selection`-producing
strategy itself falls back to today's first-eligible logic," which is functionally indistinguishable
from V5's current behavior. This preserves the ADR's own words exactly: "otherwise it degrades to no
adjustment and V5 behaves exactly as M2-ADR-025 already froze it."

---

## 11. Activation analysis — corrected, and the two central findings of this report

**The ADR's own activation rule** (§2): *"`V6` acts only when a bounded, well-formed hypothesis set
with a computed posterior exists for the attempt being created; otherwise it degrades to no
adjustment."* This is the only activation text anywhere in the merged ADR set. It does not define
"well-formed," does not specify a minimum candidate-hypothesis count, does not name a feature flag,
and does not say which interaction's evidence Step 1 should read.

### 11.1 Correction: this is a source-interaction ambiguity, not proof of permanent inertness

The first draft of this report over-concluded here. It is true that the *destination* attempt (the
one being created, whose packet — including the would-be probe — is selected in one shot before any
of it is presented) has zero governed evidence at the moment selection happens. **It is not true**
that this forces `interactionId = the destination attempt's own id`. Today's V5 already reads from a
*different*, already-completed interaction to derive the hypothesis in the first place:

```
sourceAttempt = repository.findMostRecentCompletedAttempt(learnerId, assessmentVersionId)
```

`HypothesisUncertaintyContextAssembler.assemble(UUID interactionId, ...)` and
`HypothesisUncertaintyRepository.findPerInteractionEvidence(UUID attemptId, ...)` both accept *any*
attempt id — nothing in Step 1's own contract pins `interactionId` to the destination attempt. If
`sourceAttempt` was itself created via V5 (i.e. it carries its own governed
`core.diagnostic_probe_provenance` row and a scored response), then `interactionId = sourceAttempt
.id()` would see **real, non-empty** evidence. **M2-ADR-034 as merged simply never said which of the
two interactions is authoritative for a V6 decision** — that is a governance gap the ADR left open,
not a proof that Step 1 must always return `INSUFFICIENT_EVIDENCE`/`NOT_APPLICABLE`.

**`docs/adr/M2-ADR-034-information-gain-probe-selection.md` Amendment 3 §C** now closes this gap:
`sourceInteractionId` is frozen as the immediately preceding completed source attempt (the same one
V5 already selects hypotheses from), never the destination attempt.

### 11.2 The two independent V5 collapses (the sharper central finding)

Closing the source-interaction gap alone is **not sufficient**, because of a second, independent
fact this report's first draft under-analyzed: `DiagnosticService.resolveHypothesisProbeSelection`
performs two separate collapses, which Amendment 2 §2's original text names together as one
undifferentiated "step (b)":

- **Collapse A (hypothesis collapse):** the nested loop over `misses × RELATIONSHIP_TYPE_PRIORITY`
  returns at the very first `CANDIDATES_AVAILABLE` hit — a real `return` statement inside the loop
  body. At most **one** `DiagnosticHypothesis` is ever admitted to `HypothesisUncertaintyContext
  .candidates()` today, regardless of which interaction supplies its evidence.
- **Collapse B (probe collapse):** within that one hypothesis, `resolution.candidates().get(0)`
  discards every candidate item after the first, before any discrimination calculation exists.

**Amendment 2 §H already proves, as frozen mathematics, that a sole participating hypothesis
normalizes to exactly `1.0000` in every reachable world — so every candidate probe targeting it
scores exactly `0.0000`, unconditionally, independent of which interaction's evidence Step 1 uses.**
Fixing §11.1's source-interaction question alone therefore still leaves V6 mathematically certain to
be a no-op, because Collapse A guarantees the candidate set handed to Step 1 never has more than one
member. **Both** the source-interaction decision (§11.1) **and** a decision to widen Collapse A to
admit more than one hypothesis are jointly necessary for V6 to ever have observable effect; neither
is independently sufficient.

**Sharper mathematical finding, offered as supporting analysis:** even granting ≥2 participating
hypotheses, a candidate probe whose *own target hypothesis currently has zero prior directional
evidence* still scores exactly `0.0000`. `DiagnosticConfidenceCalculatorV1`'s own frozen thresholds
map both `(1,0)` and `(0,1)` to `LOW` — so a first-ever observation moves any hypothesis into the
*same* band regardless of direction, leaving every candidate's normalized value identical between
`World-S` and `World-C` (every other hypothesis's own weight is untouched, and the target's weight is
identically `1` in both worlds). **Non-zero discrimination is reachable only for a candidate probe
that re-probes a hypothesis which already has at least one prior directional observation** — not for
a freshly authorized one. This is a direct, provable consequence of the already-frozen Step 1/Step 2
formulas, not a new rule; it does not change either engine's mathematics, only names an unstated
consequence relevant to Step 3's activation policy.

**`docs/adr/M2-ADR-034-information-gain-probe-selection.md` Amendment 3** now resolves both parts of
this finding: **§E** ratifies bounded multi-hypothesis enumeration (Mode 2 — walking the *same*
existing `ProbeRelationshipService.resolve` authority across every `(miss, type)` pair instead of
stopping at the first hit, bounded by the already-frozen `RELATIONSHIP_TYPE_PRIORITY.size() = 4` and
the source attempt's own — already operationally bounded — miss count), explicitly because Amendment
2 §H's own theorem makes the alternative (preserving Collapse A unchanged) mathematically certain to
be inert, not merely likely. **§J** freezes activation as six numbered conditions, including a
mathematically-derived requirement of **at least two participating hypotheses**, and names the
first-observation corollary above as supporting rationale, not a new independent rule.

This remains the report's central, load-bearing finding — see the (now largely resolved) rows in
§21's gap matrix and the verdict in §22, which is **unchanged**: Amendment 3 freezes the missing
decisions; it does not itself authorize implementation.

---

## 12. Zero-score semantics

Because of §11.2, "all candidates score `0.0000`" is not a rare edge case for V6 — even once
Amendment 3's §E multi-hypothesis enumeration and §C source-interaction decisions are both applied,
a first-time probe of any freshly authorized hypothesis still scores `0.0000` by the first-observation
corollary (§11.2); only a re-probe of an already-evidenced hypothesis can ever score above zero. This
was, correctly, flagged as the single most consequential open decision. **Amendment 3 §K** now
freezes it: `maxScore > 0.0000` uses Step-2 ranking; `maxScore == 0.0000` preserves V5's existing
selection exactly (no canonical-order tie-break is used to manufacture a behavior change with no
underlying diagnostic signal), and Amendment 3 §K explains why this does not conflict with Amendment
2 §K's own frozen ranking contract (Step 2's ranking is correct for a result that carries genuine
separating power; Step 3 separately decides *when* to act on it).

---

## 13. `NOT_APPLICABLE` semantics (Step 1)

Not specified for V6. The only textual anchor is §2's generic "degrades to no adjustment" — which,
per §11, is not a rare branch but the default path. No document states whether "no adjustment" means
"V5's original first-eligible tiebreak runs" (most consistent with the ADR's own words) versus some
other named fallback. Flagged for Amendment 3, though the ADR's own phrasing already points toward
the V5-original-behavior answer more strongly than most of the other open questions here.

---

## 14. `INSUFFICIENT_EVIDENCE` semantics (Step 1)

Per §11, this is the *typical* status, not an edge case, for a freshly-raised hypothesis. Step 2
itself already correctly refuses to synthesize a distribution here (`NOT_APPLICABLE`, §G). The
question for Amendment 3 is identical to §13's: what does V6 do when Step 2 says `NOT_APPLICABLE` —
not specified beyond "degrades to no adjustment."

---

## 15. Provenance impact

`core.diagnostic_probe_provenance` has no engine-version, hypothesis-id, or score column — the engine
version is recorded once per attempt on `core.assessment_attempt.selection_policy_version`, not
per-provenance-row. Minimal impact if V6 ships: a new `selection_policy_version` string constant
(`"DIAGNOSTIC_SELECTION_V6"`), following the exact precedent V1–V5 already set — no schema change
required for that alone. Recording `uncertaintyEngineVersion`/`discriminationEngineVersion`/
`discriminationScore` per decision (as the prompt's item 24 speculates) would require a genuine
schema change that Amendment 2 §P explicitly declines to authorize ("a future step... may revisit
persistence for audit — this amendment does not authorize it"). Whether V6 needs its own
`SelectionReason` value is unclear — the existing `HYPOTHESIS_DRIVEN_PROBE` reason is about the
*skill* being reprioritized, not about which tiebreak chose the specific item, so it may not need a
new value at all. **Flagged as an open, non-blocking question** for Amendment 3 or the eventual
design PR — not as consequential as §11/§12.

---

## 16. Persistence impact

Strong preference in every Amendment-2-adjacent document reviewed is **no migration**. Given §15,
none is structurally required to ship a minimal V6 that only changes which `Selection` value is
computed — the existing `core.diagnostic_probe_provenance` and `core.assessment_attempt` schemas can
represent "V6 selected this item for this hypothesis" exactly as they represent V5's choice today,
using the SAME columns. A schema change would only become necessary if audit requirements demand
recording the actual discrimination score or the full candidate set considered — not authorized by
any merged text today.

---

## 17. Transaction / concurrency impact

`resolveHypothesisProbeSelection`'s replacement would run inside the exact same `createAttempt`
`@Transactional` boundary that already wraps candidate resolution, hypothesis construction, and
persistence — no new transaction boundary is needed, and no candidate-set-changes-between-scoring-
and-persistence race is possible, since everything happens synchronously in one thread inside one
transaction, exactly as V5 does today. Existing concurrency protections
(`repository.findActiveAttempt`'s one-active-attempt invariant, idempotency-key handling with
`DuplicateKeyException` catch-and-retry) are unaffected by anything V6 would add, since Step 1/Step 2
are pure functions with no shared mutable state. **No new concurrency risk identified.**

---

## 18. Performance impact

Under §11's finding, `H` (candidate hypotheses) is effectively always 1 with the current discovery
walk, and `P` (candidate probes for that one hypothesis) is bounded by however many verified
scoreable items are tagged to one target objective — small, curriculum-bounded, typically single
digits. Step 2's own cost is at most `2P` calls into Step 1, each `O(H log H)` — trivial at `H=1`.
DB calls before the calculator ever runs are already bounded (`resolve` does a handful of indexed
reads per (miss, type) pair; `itemsForObjective` and `findObjectiveDomainCodes` are already batched,
not N+1). **If** Amendment 3 resolves §11 by choosing to enumerate multiple hypotheses (e.g., all
misses × all relationship types instead of stopping at the first hit), the DB-call count before
scoring could grow to `O(misses × 4)` in the worst case — worth re-estimating once that direction is
chosen, but not a blocking concern at today's typical miss counts (small, bounded by one attempt's
own item count).

---

## 19. Observability impact

No Micrometer metrics exist anywhere in the assessment/diagnostic package to imitate; the existing
convention is `BusinessEventLogger.info/warn/error(Logger, "dotted.operation.name", message,
Map<String,?> fields)` with `entityType`/`entityId`/`learnerId`/`outcome` as standard fields, plus
MDC-sourced `interactionId`/`traceId`/`spanId` correlation (never put directly in the fields map).
A future V6 should follow this exact convention (e.g. `BusinessEventLogger.info(LOGGER,
"assessment.probe.selection.v6", ..., Map.of("selectionPolicy", "DIAGNOSTIC_SELECTION_V6",
"candidateCount", ..., "outcome", ...))`) rather than introducing a new Micrometer-based pattern this
codebase doesn't otherwise use.

---

## 20. Architecture diagram (actual classes, current state — not yet built)

```
DiagnosticService.createAttempt                                [@Transactional, unchanged]
        |
        v
DiagnosticService.selectHypothesisDrivenProbeForm               [unchanged]
        |
        +-- PrerequisiteAwareDiagnosticSelector.adjustForPrerequisites   [V3, unchanged]
        +-- HypothesisConfirmationDiagnosticSelector.adjustForRegressions [V4, unchanged]
        |
        v
[[ NOT YET BUILT — the actual V6 seam ]]
resolveHypothesisProbeSelection  -->  a V6-aware replacement that must first resolve
        |                              Amendment-3 Decisions #1 (activation/evidence scope)
        |                              and #2 (multi-hypothesis enumeration) below
        v
ProbeRelationshipResolver / ProbeRelationshipService             [reused verbatim, unchanged]
        |  ProbeResolution.candidates() : List<ProbeCandidateItem>   <- read in full, not .get(0)
        v
[[ NEW, small ]] CandidateProbe adapter                          <- owned by whichever class
        |                                                            replaces resolveHypothesisProbeSelection;
        |                                                            NOT a controller, NOT AI/MCP
        v
HypothesisUncertaintyContextAssembler.assemble(interactionId, hypotheses)   [reused verbatim]
        v
HypothesisUncertaintyCalculatorV1.calculate(...)                 [reused verbatim, Step 1]
        v
HypothesisDiscriminationContext(baseContext, baseResult, candidateProbes)
        v
HypothesisDiscriminationCalculatorV1.calculate(...)              [reused verbatim, Step 2]
        v
[[ NEW ]] V6 deterministic ranking (§K, reused verbatim) -> choose highest-scoring CandidateDiscrimination
        |                                    (fallback: today's resolution.candidates().get(0)
        |                                     when Step 2 status != SCORABLE or all scores tie at 0.0000
        |                                     and no tie-break policy has been frozen — Amendment 3)
        v
HypothesisDrivenProbeDiagnosticSelector.Selection(...)           [unchanged record shape]
        v
HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe [V5 proper — completely untouched]
        v
AdaptiveDiagnosticSelector.select(...)                           [V2 — completely untouched]
        v
finishAdaptiveSelection -> repository.insertSelectedItems -> ProbeProvenanceRepository.insert
```

Fallback path (both today and under any V6 design that respects §2): if the V6-aware resolution step
cannot produce a ranked `Selection` (no hypothesis, Step 1/2 not `APPLICABLE`/`SCORABLE`, or — per
§11 — essentially every real invocation today), it returns `null` exactly as
`resolveHypothesisProbeSelection` does today, and every downstream step behaves byte-for-byte as it
does on `main` right now.

---

## 21. Gap matrix

| Concern | Already frozen? | Current implementation | Gap | Amendment needed? |
|---|---|---|---|---|
| Activation | **Now frozen — Amendment 3 §J** (6 numbered conditions incl. ≥2 participating hypotheses) | N/A — V6 doesn't exist | None remaining | Resolved by Amendment 3 (pending review/merge) |
| Candidate eligibility (source) | Yes (Amendment 2 §E: V5/H4b only, no ADR-032, no LLM/graph); reaffirmed Amendment 3 §I | `ProbeRelationshipResolver`/`Service` already produce a bounded per-hypothesis list | None | No |
| Candidate construction (`CandidateProbe`) | **Now frozen — Amendment 3 §H** (full `ProbeResolution.candidates()`, not `.get(0)`) | `ProbeResolution.candidates()` exists; adapter still to be written at implementation time | Small mechanical adapter; `ProbeCandidateItem` still has no item-type field for `scoreable` derivation | No (mechanical, implementation-time) |
| Hypotheses (multi-member set) | **Now frozen — Amendment 3 §E/§F/§G** (Mode 2, bounded by misses × `RELATIONSHIP_TYPE_PRIORITY`, exact-identity de-dup, three distinct orders named) | One hypothesis discovered per attempt today, fused with candidate-probe discovery | None remaining at the design level | Resolved by Amendment 3 (pending review/merge) |
| Evidence scope/window | **Now frozen — Amendment 3 §C** (source interaction, not destination) | Evidence scoped to `attempt_id`; assembler already supports any id | None remaining | Resolved by Amendment 3 (pending review/merge) |
| Uncertainty (Step 1) integration | Yes (contract); source now frozen (§C) | Assembler reusable as-is | None | Resolved |
| Discrimination (Step 2) integration | Yes (contract); input now non-degenerate once §C+§E apply | Calculator reusable as-is | None | Resolved |
| Ranking | Yes (Amendment 2 §K, reaffirmed Amendment 3 §Q) | No existing conflicting rule | None | No |
| Zero score | **Now frozen — Amendment 3 §K** (fallback to exact V5 selection; reconciled with Amendment 2 §K) | N/A | None remaining | Resolved by Amendment 3 (pending review/merge) |
| `NOT_APPLICABLE` (Step 1) fallback | **Now frozen — Amendment 3 §L** | N/A | None | Resolved |
| `INSUFFICIENT_EVIDENCE` fallback | **Now frozen — Amendment 3 §M** | N/A | None | Resolved |
| Empty candidate set | Yes, by inheritance from V5's existing `ProbeResolutionOutcome` handling; reaffirmed Amendment 3 §O | `continue`/`null` cascade already exists | None | No |
| Fallback chain (cross-version) | Yes (no cascade exists; static routing); reaffirmed Amendment 3 §T | `selectForm` picks one branch once | None | No |
| Tie-break (score ties) | Yes (Amendment 2 §K; Amendment 3 §K clarifies the zero-tie case separately) | N/A | None | Resolved |
| One-candidate-probe case | **Now frozen — Amendment 3 §P** (use V5 selection directly; do not invoke Step 1/2) | N/A | None | Resolved |
| Validation vs. expected-outcome distinction | **Now frozen — Amendment 3 §N** (expected statuses → V5 fallback; corruption → fail closed) | N/A | None | Resolved |
| Provenance | **Now frozen — Amendment 3 §U** (minimum auditable fields; no migration; replay preferred over persistence) | Schema already supports "which item, which hypothesis" | None blocking | Resolved |
| Persistence | Explicitly deferred (Amendment 2 §P); reaffirmed Amendment 3 §U | None | None required for a minimal V6 | No |
| Transactionality | **Now frozen — Amendment 3 §Z** | Single `@Transactional` covers everything | None | Resolved |
| Observability | N/A (no existing metrics to violate) | `BusinessEventLogger` convention | None blocking; just follow convention | No |
| `V1`–`V5` compatibility | Yes (frozen, hashed); reaffirmed Amendment 3 §S/§T | Confirmed unaffected by any proposed seam (§20) | None | No |
| ADR-032 boundary | Yes (Amendment 2 §E/§V; M2-ADR-032 §5/§6); reaffirmed Amendment 3 §W | No accidental path exists today | None | No (already governed) |
| ADR-033 boundary | Yes (M2-ADR-033 §6, names "or a future V6" explicitly); reaffirmed Amendment 3 §X | No graph dependency anywhere in the diagnostic path | None | No |
| Performance bound | **Now frozen — Amendment 3 §AA** (`H ≤ misses × 4`, both pre-existing quantities) | N/A | None | Resolved |
| Replay/reproducibility | **Now frozen — Amendment 3 §V** | N/A | Exposure-set monotonic-growth caveat noted, pre-existing to `V1`–`V5` | No (pre-existing property, not new) |

---

## 22. Final YES/NO readiness verdict

> **NO — Amendment 3 is required before implementation. This remains true even now that Amendment 3
> has been drafted: implementation may begin only after Amendment 3 is reviewed and merged, not the
> moment it is written.**

The decisive reason, as corrected in §11, was never simply "Step 1 sees no evidence" — it was that
**two independent decisions were both required and both absent**: which interaction supplies Step-1
evidence (§11.1), and whether V5's existing single-hypothesis collapse may be widened (§11.2, made
unavoidable by Amendment 2 §H's own theorem). `docs/adr/M2-ADR-034-information-gain-probe-selection.md`
Amendment 3 (drafted alongside this correction) freezes both, plus every other decision §23
originally listed. Until that amendment is reviewed and merged, the verdict for actually writing
`DIAGNOSTIC_SELECTION_V6` code remains **NO** — a drafted amendment is not a ratified one, and this
report's own governing instruction was explicit that implementation begins only after review and
merge, not after drafting.

---

## 23. Amendment-3 decision list

### Decision 1 — Evidence scope / activation window for V6
**Why unresolved:** Amendment 1 §F freezes "per-interaction only, no cross-attempt" evidence, but the
only point selection happens (attempt creation) always has zero evidence for the new interaction.
**Available options:**
  - (a) Use the *source* attempt's own interactionId, if and only if the source attempt itself
    already carries governed evidence for the hypothesis in question (rare, since source attempts
    typically probe a *different*, earlier hypothesis).
  - (b) Revisit Amendment 1 §F to allow a bounded cross-attempt evidence window scoped to one
    recurring hypothesis tuple (a real amendment to Step 1's own frozen boundary, not a V6-only
    decision).
  - (c) Redefine what "evidence" means for a not-yet-probed hypothesis at selection time (e.g.,
    treat the *triggering miss itself* as a synthetic first observation) — this would be new
    algorithmic policy, exactly what M2-ADR-023 §2 requires a deliberate, reviewed decision for.
**Existing behavior:** V5 never needed this question because it never compares hypotheses; it just
takes the first one.
**Recommended direction:** (a) as a first, minimal step — accept that V6 is non-trivial only in the
rare case a hypothesis recurs with existing evidence, and explicitly document the common case as a
documented, provable no-op rather than treating it as a bug. (b)/(c) are bigger, separately-reviewed
changes if RAMALS wants V6 to matter in the common case.
**Risk if left unspecified:** V6 ships as permanently inert code with passing tests and no runtime
effect — misleading maturity signal in `target-intelligence-loop.md` and wasted review effort.

**Resolved by Amendment 3 §C**, choosing option (a)'s underlying attempt (the source interaction),
but framed unconditionally rather than "only if it happens to carry evidence" — every V6 decision
reads the source interaction's evidence, whether or not it happens to be non-empty; §L/§M freeze
what happens when it is empty (V5-equivalent fallback), so the "rare case" framing above is
superseded: this is now the *only* rule, not a conditional one.

### Decision 2 — Multi-hypothesis enumeration policy
**Why unresolved:** Hypothesis discovery and candidate-probe discovery are fused in
`resolveHypothesisProbeSelection`, which stops at the first (miss, type) hit. No document specifies
whether V6 should see all misses, all relationship types for one miss, or some other bounded subset.
**Available options:**
  - (a) Keep "first miss" but evaluate *all four* relationship types for it, collecting every one
    that resolves `CANDIDATES_AVAILABLE` as a distinct candidate hypothesis (bounded by 4).
  - (b) Evaluate *all* misses under only the highest-priority type each resolves under (bounded by
    miss count).
  - (c) Evaluate the full cross-product (misses × types) up to some frozen cap.
  - (d) Leave discovery exactly as today (one hypothesis) and accept V6 only ever compares "the one
    hypothesis" against nothing — makes Step 2 mathematically vacuous (§H's sole-participant proof)
    and is equivalent to not implementing V6 at all.
**Existing behavior:** (d) — today's actual behavior.
**Recommended direction:** (a) is the smallest change with the clearest scope (bounded by the
already-fixed `RELATIONSHIP_TYPE_PRIORITY` list, not by learner-dependent miss counts), and stays
closest to "the same eligible-candidate set step (a) produced" language in §2 — but this itself is a
new algorithmic policy question the current ADR text does not settle, and must be decided
deliberately, not inferred.
**Risk if left unspecified:** Same as Decision 1 — without resolving this, Decision 1(a) alone still
leaves H=1 in the overwhelming majority of cases.

**Resolved by Amendment 3 §E**, choosing a variant of option (c) bounded not by an invented cap but
by the two already-existing quantities identified during drafting: `RELATIONSHIP_TYPE_PRIORITY
.size() = 4` and the source attempt's own (already operationally bounded, e.g. by
`AdaptiveDiagnosticFormProperties`'s default target sizes) miss count — walking the full
`misses × RELATIONSHIP_TYPE_PRIORITY` domain exhaustively, de-duplicated by exact `DiagnosticHypothesis`
identity (§F), explicitly justified (not merely preferred) by Amendment 2 §H's own theorem making
option (d) mathematically certain to be inert, not just the status quo.

### Decision 3 — Zero-score / tie fallback (§12/§16 of the numbered prompt)
**Why unresolved:** No document says what V6 does when every candidate scores `0.0000` (the expected
common case given Decisions 1–2, and even after resolving them, a real possibility per §D's
corollaries).
**Available options:** A (select canonical first candidate — i.e., preserve today's `.get(0)`
behavior as the tie-break), B (fall back to V5's original first-eligible logic entirely), C (fall
back further to V2's plain pool), D (declare discrimination unavailable and skip the probe this
round).
**Existing behavior:** Closest to A/B today (there is no discrimination step, so "first eligible" —
V5's own current behavior — already IS today's answer).
**Recommended direction:** B, worded precisely as "when every candidate ties (including the trivial
all-`0.0000` case), fall back to exactly today's `resolution.candidates().get(0)` selection" — this
keeps V6 a strict refinement of V5's existing determinism rather than inventing new tie-break policy,
and matches §2's own "degrades to no adjustment" language most literally.
**Risk if left unspecified:** Implementers will each pick differently; reproducibility (M2-ADR-023's
own core discipline) breaks across environments/versions if this isn't frozen identically to how
every other tie-break in this codebase is frozen.

**Resolved by Amendment 3 §K**, adopting recommendation B exactly as worded, plus an explicit
reconciliation with Amendment 2 §K explaining why Step 3 choosing not to *act* on a valid Step-2
result never mutates or contradicts Step 2's own frozen ranking.

### Decision 4 — `NOT_APPLICABLE`/`INSUFFICIENT_EVIDENCE` fallback wording
**Why unresolved:** "Degrades to no adjustment" is stated once, generically, never spelled out as a
concrete algorithm step.
**Available options:** treat as identical to Decision 3's fallback (single unified rule), or define
separately.
**Existing behavior:** N/A.
**Recommended direction:** Unify with Decision 3 — one fallback rule ("if Step 2 status is not
`SCORABLE`, or `SCORABLE` with an empty/all-tied result, use today's `resolution.candidates().get(0)`
selection") rather than two separately-specified branches.
**Risk if left unspecified:** Low on its own, but compounds Decision 3's reproducibility risk if left
inconsistent with it.

**Resolved by Amendment 3 §L/§M**, unified with Decision 3's rule exactly as recommended: both
statuses preserve V5's existing selection; neither ever fabricates a distribution.

### Decision 5 — Does V6 mint its own persisted version identifier?
**Why unresolved:** `core.assessment_attempt.selection_policy_version` currently distinguishes V1–V5;
no document says whether V6 gets its own value or is recorded as a silent internal variant of V5.
**Available options:** (a) new `"DIAGNOSTIC_SELECTION_V6"` string, minted the same way V1–V5 were;
(b) no new value — V6 is invisible in `selection_policy_version`, distinguished only by a new
`SelectionReason` or nothing at all.
**Existing behavior:** every prior selection version (V1–V5) got its own string when it started
actually running — precedent strongly favors (a).
**Recommended direction:** (a), for consistency with the platform's own reproducibility/versioning
discipline (M2-ADR-023 §2, `EngineVersionFreezeTests`'s entire premise).
**Risk if left unspecified:** Attempts selected under V6 become indistinguishable from V5 in the
database — breaks the "every consequential decision is stamped with a version identifier" invariant
`EngineVersionFreezeTests`'s own javadoc states as the platform's MVP-1 entry criterion.

**Resolved by Amendment 3 §S**, choosing option (a) exactly as recommended.

### Decision 6 — Probe enumeration (Collapse B) — surfaced during Amendment 3 drafting
**Why unresolved:** the original 5-decision list treated "which candidate item is chosen" as settled
by Step 2's contract alone; drafting Amendment 3 surfaced that V5's own `resolution.candidates()
.get(0)` collapse (Collapse B, §11.2) needed its own explicit freeze, separate from Collapse A
(Decision 2).
**Resolved by Amendment 3 §H**: every candidate in `ProbeResolution.candidates()` becomes a
`CandidateProbe`, not just the first — no new eligibility rule, only widening which of an
already-bounded, already-filtered list reaches scoring.

### Decision 7 — Validation-failure vs. expected-outcome distinction — surfaced during Amendment 3 drafting
**Why unresolved:** the original list treated all non-`SCORABLE` outcomes as one "fallback" case;
drafting surfaced that a genuine validation/invariant failure (`BASE_RESULT_MISMATCH`,
`DUPLICATE_EVIDENCE_OBSERVATION`, any `CROSS_DOMAIN_*`/`MALFORMED_*` code) is categorically different
from an expected control outcome (`NOT_APPLICABLE`/`INSUFFICIENT_EVIDENCE`) and must not be handled
the same way.
**Resolved by Amendment 3 §N**: expected outcomes fall back to V5's selection; validation/corruption
failures fail the attempt-creation transaction closed, consistent with how Amendment 1/2's own
fail-closed calculators are already treated everywhere else in this codebase (no caller silently
substitutes a fallback for a `*ValidationException`).

---

*End of report. No code, migration, or ADR was changed to produce this.
`DIAGNOSTIC_SELECTION_V6` remains unimplemented and unauthorized for implementation. Amendment 3
freezes the runtime semantics listed above but requires its own review and merge before
implementation may begin.*
