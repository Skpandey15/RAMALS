# RAMALS target intelligence loop — repository-native north-star map

**Status:** ARCHITECTURE MAP (repository-native). Direction, not a claim of current capability.
**Scope:** the long-term RAMALS intelligence loop, stage by stage, each marked
`IMPLEMENTED` / `DESIGNED` / `DEFERRED` and `NOW` / `NEXT` / `LATER`, with its owning ADR, module,
or migration where one exists.

This file is the git-versioned reconciliation of the strategic direction with what is actually on
`main`. Strategic `.docx` packs held outside this repository (e.g. "Revised Target Architecture
v2.0", "Twelve Intelligence Enhancements") are design *input*; this file is the in-repository
authority for which parts of that direction exist, are designed, or are deferred.

## How to read this map

| Maturity | Meaning |
| --- | --- |
| `IMPLEMENTED` | Code, migration, and tests are on `main`. |
| `DESIGNED` | An ADR is Accepted or Proposed; not fully built, or built only as an inert foundation. |
| `DEFERRED` | A roadmap ADR stub reserves the boundary; nothing is authorized for construction. |

| Horizon | Meaning |
| --- | --- |
| `NOW` | In place, or the immediately active documentation/decision step. |
| `NEXT` | The next milestone once `NOW` work settles. |
| `LATER` | MVP-3 and beyond; sequenced behind its dependencies. |

Non-negotiables that hold at every stage: **agents recommend, deterministic services decide**;
evidence is immutable and auditable; mastery is deterministic and reproducible; diagnostic
confidence is separate from mastery and never feeds it; AI output is a proposal or evidence, never
business truth; every authoritative algorithm is named and versioned with a frozen behaviour
vector; `interactionId` / `traceId` / `spanId` and policy/engine version travel with every record;
no premature ML/RL.

## The loop

```
GOAL + DEADLINE
      ->
CLAIMS + IMMUTABLE EVIDENCE
      ->
MASTERY + CONFIDENCE
      ->
GAP INTELLIGENCE
      ->
DIAGNOSTIC HYPOTHESES
      ->
PREREQUISITE GRAPH + MISCONCEPTION GRAPH
      ->
UNCERTAINTY
      ->
PROBE DISCRIMINATION SCORING
      ->
NEXT BEST DIAGNOSTIC PROBE
      ->
NEW EVIDENCE
      ->
ROOT CAUSE / MISCONCEPTION
      ->
NEXT BEST ACTION
      ->
INTERVENTION
      ->
REASSESSMENT
      ->
OUTCOME
      ->
INTERVENTION EFFECTIVENESS
      ->
UPDATED EVIDENCE / MASTERY
      ->
PRIVACY-GOVERNED AGGREGATION
      ->
ORGANIZATIONAL CAPABILITY DIGITAL TWIN
```

## Stage-by-stage status

| # | Stage | Maturity | Horizon | Owner (ADR / module / migration) | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | **Goal + deadline** | `IMPLEMENTED` (minimal) / rest `DEFERRED` | `NEXT` | `core.learner_goal` (`V004`) | A goal/target row exists. A first-class time-bound goal model — target mastery, target date, priority, effort budget, and an `ON_TRACK` / `AT_RISK` / `OFF_TRACK` / `INSUFFICIENT_EVIDENCE` trajectory — is **not** implemented and has no ADR yet. |
| 2 | **Claims + immutable evidence** | evidence `IMPLEMENTED`; claims `DEFERRED` | evidence `NOW`; claims `LATER` | `ledger.evidence` (`V007`); `EvidenceConfidenceCalculatorV2` (`EVIDENCE_CONFIDENCE_V2`, `V009`) | Evidence is append-only, provenance-linked, confidence-weighted. **Claims** (CV / self-reported / certification as a system of record, distinct from evidence) are not built. |
| 3 | **Mastery + confidence** | `IMPLEMENTED` | `NOW` | `ledger.mastery_snapshot` (`V008`); `WeightedMasteryCalculator` (`WEIGHTED_MASTERY_V1`); `MasteryStatusPolicyV2` (`MASTERY_STATUS_POLICY_V2`); `V046` coverage v2 | `NUMERIC` / `BigDecimal` throughout; single computation locus; frozen calculators. |
| 4 | **Gap intelligence** | `IMPLEMENTED` (H1) / typed goal-relative gaps `DEFERRED` | H1 `NOW`; typed gaps `NEXT` | `GapDiagnosisService`, `GapClassification` (`io.ramals.learningplatform.diagnosis`); `PrerequisiteAwareDiagnosticSelector` (`DIAGNOSTIC_SELECTION_V3`, `V051`); M2-ADR-023 | H1 explains *why* a skill reads weak (inherited from an unsecured prerequisite, or independent), read-only, no mastery write. A typed, goal-relative gap taxonomy (study / learning / prerequisite / application / preparation / retention / evidence / confidence) is a strategic direction, not the current model. |
| 5 | **Diagnostic hypotheses** | `IMPLEMENTED` | `NOW` | `DiagnosticHypothesis`, `HypothesisEvidenceOutcome`, `core.diagnostic_probe_relationship` (`V054`); `HypothesisDrivenProbeDiagnosticSelector` (`DIAGNOSTIC_SELECTION_V5`, `V055`); M2-ADR-024, M2-ADR-025 | A hypothesis is never a diagnosis; evidence is three-valued `SUPPORTING` / `CONTRADICTORY` / `INCONCLUSIVE`; nothing here writes mastery. |
| 6a | **Prerequisite graph** | `IMPLEMENTED` | `NOW` | `core.skill_prerequisite` (`V003`); `CurriculumGraph` / `CurriculumGraphValidator`; [M0-T06](../database/m0-t06-curriculum-graph.md) | Acyclic, version-scoped, immutable once published. |
| 6b | **Misconception entity, evidence, confidence, reports** | `IMPLEMENTED` | `NOW` | `core.misconception`, `core.diagnostic_node`, `core.assessment_item_option_misconception` (`V057`, M2-ADR-026); `core.misconception_evidence_observation` (`V058`, `MISCONCEPTION_EVIDENCE_V1`, M2-ADR-027); `core.misconception_confidence_observation` (`V059`, `DIAGNOSTIC_CONFIDENCE_V1`, M2-ADR-028); H6 report (M2-ADR-029); H7 longitudinal projection (`LONGITUDINAL_EVIDENCE_V1`, M2-ADR-030) | `Misconception` is a first-class entity orthogonal to the `LearningObjective → Concept → Sub-concept` tree, with a DB-enforced exclusive-arc target and `DRAFT` / `PUBLISHED` immutability. |
| 6c | **Misconception graph edges + queryable graph surface** | edges `IMPLEMENTED` (persistence + domain model + authored validation) / bounded read-only query surface `IMPLEMENTED` (step 2) / adaptive use `DESIGNED` | edges + query surface `NOW`; adaptive use `NEXT` | **M2-ADR-033**: `core.misconception_relationship` + `core.misconception_prerequisite_link` (`V061`, step 1); `MisconceptionRelationshipService` / `Validator` / `Repository` (step 1) and `MisconceptionGraphQueryService` / `MisconceptionGraphQueryRepository` + `MisconceptionGraphView` projection (step 2), all in `io.ramals.learningplatform.assessment.misconceptiongraph` | Step 1 stores and deterministically validates authored, non-authoritative `MISCONCEPTION_RELATED` (misconception↔misconception, sub-types `CO_OCCURS_WITH` / `SPECIALISES` / `CONTRASTS_WITH`, symmetric ones canonically ordered, `SPECIALISES` acyclic) and `MISCONCEPTION_PREREQUISITE_LINK` (misconception→prerequisite `core.skill`) edges, `DRAFT`/`PUBLISHED` immutable, published-endpoint-only. Step 2 (M2-ADR-033 §5) composes, for one `LEARNING_OBJECTIVE` / `CONCEPT` / `SUB_CONCEPT`, a deterministic bounded `@Transactional(readOnly = true)` projection — that node's curriculum prerequisites + its `PUBLISHED` misconceptions + the `PUBLISHED` `MISCONCEPTION_RELATED` / `MISCONCEPTION_PREREQUISITE_LINK` edges among and from that set — in a fixed 5 (or 3) queries, no N+1, published-only enforced in SQL, no external-neighbour expansion. **Still no** learner-scoped overlay, adaptive selection, information gain, posterior, `DIAGNOSTIC_SELECTION_V6`, AI graph authoring, HTTP/MCP transport, or graph traversal API — no selector reads the graph and the query surface reads no selector, evidence, mastery, or G2/G3. Extends M2-ADR-026 §8. |
| 7 | **Uncertainty** | `IMPLEMENTED` (band) / `HYPOTHESIS_UNCERTAINTY_V1` `IMPLEMENTED` / `INERT` (M2-ADR-034 step 1) / adaptive use `DESIGNED` | distribution `NOW`; adaptive use `NEXT` | `DiagnosticConfidenceCalculatorV1` bands (G3 / H5); **M2-ADR-034 Amendment 1**: `HypothesisUncertaintyCalculatorV1` + `HypothesisUncertaintyContextAssembler` + `HypothesisUncertaintyRepository` (`io.ramals.learningplatform.assessment.hypothesisuncertainty`) | Diagnostic/causal confidence bands exist. `HYPOTHESIS_UNCERTAINTY_V1` (M2-ADR-034 Amendment 1, step 1) is implemented: a **deterministic normalized relative** hypothesis-uncertainty distribution (explicitly **not** a Bayesian posterior: no prior, no likelihood, no update) over the already-authorized candidate hypothesis set, from per-interaction governed probe evidence only, reusing `DiagnosticConfidenceCalculatorV1` verbatim, ordinal-linear band weights `LOW=1/MODERATE=2/HIGH=3`, `INSUFFICIENT_EVIDENCE` kept off the numeric scale behind an `APPLICABLE`/`INSUFFICIENT_EVIDENCE`/`NOT_APPLICABLE` status, `BigDecimal` scale 4 `HALF_EVEN` + largest-remainder residual + total canonical order on hypothesis identity, fail-closed input validation + observation-id evidence de-duplication + one-learner/one-interaction/one-domain isolation, all eleven golden vectors passing and frozen in `EngineVersionFreezeTests`. **Inert**: no selector calls it, `DIAGNOSTIC_SELECTION_V1`–`V5` are unchanged, no M2-ADR-033 graph or H7 input, no migration, no contract, no REST/MCP surface. `HYPOTHESIS_DISCRIMINATION_V1` (step 2, implemented and inert per M2-ADR-034 Amendment 2 — see stage 8) is implemented; `DIAGNOSTIC_SELECTION_V6` (step 3) remains design-only. |
| 8 | **Probe discrimination scoring** (formerly "information-gain selection") | `HYPOTHESIS_DISCRIMINATION_V1` `IMPLEMENTED` / `INERT` (M2-ADR-034 step 2) / `DIAGNOSTIC_SELECTION_V6` `DESIGNED`, runtime semantics `RATIFIED` (step 3, Amendment 3; implementation not authorized) | step 2 `NOW`; step 3 implementation `NEXT` | **M2-ADR-034 Amendment 2**: `HypothesisDiscriminationCalculatorV1` (`io.ramals.learningplatform.assessment.hypothesisdiscrimination`); **M2-ADR-034 Amendment 3** — freezes `V6`'s runtime semantics; **M2-ADR-034 §2** — `DIAGNOSTIC_SELECTION_V6` supersedes only `V5`'s final candidate tiebreak | Amendment 2 (2026-09-11) found true *expected information gain* undefensible — RAMALS has deterministic outcome **classification** (`HypothesisEvidenceOutcome`) but no outcome **probability**, and none may be invented (M2-ADR-023 §2) — so it froze `HYPOTHESIS_DISCRIMINATION_V1` instead, now implemented: per candidate probe (drawn only from `DIAGNOSTIC_SELECTION_V5`'s own already-governed candidate-item resolution, one hypothesis at a time), the total variation distance between the two `HYPOTHESIS_UNCERTAINTY_V1` distributions hypothetically resulting from the probe's two deterministically reachable outcomes (a non-scoreable probe's single reachable world scores `0.0000` by construction). Consumes `HYPOTHESIS_UNCERTAINTY_V1` and its own context verbatim; recomputes no band/weight/normalization. `BigDecimal` scale 4 `HALF_EVEN`; deterministic score → hypothesis-canonical-order → probe-id ranking; fail-closed validation; no M2-ADR-033 graph weighting; every candidate probe drawn only from `DIAGNOSTIC_SELECTION_V5`'s own resolution — an accepted M2-ADR-032 proposal stays advisory/audit-only and is never eligible here (enforced by architecture guardrail tests); **known V1 limitation:** no probe-specific psychometric quality is modeled, so two scoreable probes on the same hypothesis can tie; all eleven golden vectors passing and frozen in `EngineVersionFreezeTests`. **Inert**: no selector calls it, `DIAGNOSTIC_SELECTION_V1`–`V5` are unchanged, `V6` is not implemented, no migration, no contract, no REST/MCP surface. **Amendment 3 (2026-09-11)** freezes what a future `V6` implementation must do before it may replace `V5`'s tiebreak: read Step-1 evidence from the immediately preceding completed *source* attempt (never the destination attempt being created); widen `V5`'s existing first-match-wins hypothesis walk into a bounded multi-hypothesis enumeration across the same deterministic relationship-resolution authority (ratified, not assumed, because Amendment 2's own sole-participant theorem proves the alternative is permanently inert); score every eligible candidate probe rather than only the first; activate only with ≥2 participating hypotheses and a genuinely separating score; and fall back to `V5`'s exact existing choice whenever discrimination provides no separation. Quota-of-one unchanged; an LLM never computes a score or chooses the probe; `V6` itself remains unimplemented. |
| 9 | **Next best diagnostic probe** | `IMPLEMENTED` (deterministic, fixed-priority) / advisory AI proposal `IMPLEMENTED` (contract + gate + bounded reasoner + offline semantic-safety suite; **recorded only, consumed by nothing**) | deterministic `NOW`; discrimination-score ranking `NEXT` | `DIAGNOSTIC_SELECTION_V1`–`V5`; `core.diagnostic_probe_provenance` (`V055`); `SelectionReason.HYPOTHESIS_DRIVEN_PROBE`; M2-ADR-032 (advisory AI probe proposal boundary): `contracts/mvp2/diagnostic-probe-proposal.v1.schema.json` + `DiagnosticProbeProposalGate` (step 2), the bounded `DiagnosticProbeReasoner` wired to that gate (step 3), `diagnostic-probe-eval-v1` semantic-safety suite (step 4) | Deterministic selection unchanged (`DIAGNOSTIC_SELECTION_V1`–`V5`), quota `MAX_HYPOTHESIS_PROBES_PER_PACKET = 1`. The advisory AI proposal is deterministically gated (`ACCEPTED`/`REJECTED`/`MALFORMED`/`ABSENT`), audit-only, and feeds no selection engine. Discrimination-score-driven ranking (`V6` / M2-ADR-034 step 3) remains design-only — its runtime semantics are now ratified (Amendment 3) but no `V6` code is authorized; `HYPOTHESIS_DISCRIMINATION_V1` (step 2) itself is implemented and inert, unwired from selection — see stage 8. |
| 10 | **New evidence** | `IMPLEMENTED` | `NOW` | `core.assessment_response` (`V006`); event-time capture; `ledger.evidence` | Immutable, event-time anchored. |
| 11 | **Root cause / misconception** | `IMPLEMENTED` (prerequisite + misconception) / fuller cause taxonomy `DEFERRED` | `NOW`; fuller taxonomy `NEXT` | H1 `rootCauses` / `PREREQUISITE_GAP`; G2 / G3 misconception evidence and confidence; H6 report | Deterministic; AI never authors a root-cause classification (M2-ADR-023). A fuller explicit taxonomy (missing prerequisite / wrong model / application failure / execution error / retention decay / insufficient evidence) is partially covered. |
| 12 | **Next best action** | `IMPLEMENTED` (recommendation-decision only) / policy-versioned NBA engine `DEFERRED` | `LATER` | `core.learning_recommendation` (`V010`); `io.ramals.learningplatform.recommendation`; `ledger.decision_record` | A recommendation *decision* record exists. A policy-versioned NBA engine with effort, rationale, expiry, reassessment criteria, and a recommendation budget is **not** built and has no ADR yet. |
| 13 | **Intervention** | `DEFERRED` | `LATER` | **M2-ADR-035** (stub) | No `intervention` table. Governed dataset first; no RL. |
| 14 | **Reassessment** | `IMPLEMENTED` (attempts + H7 projection) / verify-a-diagnosis reassessment `DEFERRED` | `LATER` | Assessment attempts; H7 (`LONGITUDINAL_EVIDENCE_V1`, M2-ADR-030); constraint recorded in M2-ADR-023 §3 | A reassessment undertaken *to verify a diagnosis*, distinguishable in the audit trail from ordinary bank exhaustion and from spaced-retention testing, is explicitly unscoped (M2-ADR-023 §3). |
| 15 | **Outcome** | `DEFERRED` | `LATER` | **M2-ADR-035** (stub) | `POSITIVE` / `NEGATIVE` / `INSUFFICIENT_EVIDENCE`; derived from accepted evidence. |
| 16 | **Intervention effectiveness** | `DEFERRED` | `LATER` | **M2-ADR-035** (stub) — `INTERVENTION_EFFECTIVENESS_V1` | Deterministic aggregation over the governed dataset. **No reinforcement learning.** Never feeds mastery. |
| 17 | **Updated evidence / mastery** | `IMPLEMENTED` | `NOW` | Same as stages 2–3; mastery recompute is deterministic and idempotent | The loop closes here today: new evidence → deterministic mastery recompute. |
| 18 | **Privacy-governed aggregation** | `DEFERRED` | `LATER` | **M2-ADR-037** (stub) | Requires a multi-tenancy ADR as a mandatory predecessor. k-anonymity-style population and minimum-evidence thresholds. |
| 19 | **Organizational capability digital twin** | `DEFERRED` | `LATER` (MVP-4/5+) | **M2-ADR-037** (stub) | Organizational capability is **not** `AVG(individual mastery)`. Deterministic, versioned aggregation accounting for coverage, evidence sufficiency, confidence, recency, proficiency distribution, critical skills, minimum thresholds, hierarchy, RBAC, and tenant isolation. |

### Stages 7–9 in detail (M2-ADR-034)

```
immediately preceding completed source attempt      (Amendment 3 §C -- never the destination attempt)
        ->
misses (presentation_order) x RELATIONSHIP_TYPE_PRIORITY   (Amendment 3 §E -- bounded, exhaustive walk,
        ->                                                  not the first-match-wins V5 walk today)
bounded authorized hypothesis set, de-duplicated     (Amendment 3 §F/§G)
        ->
HYPOTHESIS_UNCERTAINTY_V1                            (stage 7 — implemented, inert; Amendment 1)
        ->
bounded deterministic candidate probes, full list    (Amendment 3 §H; V5/H4b resolution only --
        ->                                             no M2-ADR-032 proposal enters here)
HYPOTHESIS_DISCRIMINATION_V1                         (stage 8 — implemented, inert; Amendment 2)
        ->
if >=2 participate and a score separates them        (Amendment 3 §J activation rule)
        ->
deterministic score / ranking                        (score DESC -> hypothesis canonical order -> probe id)
        ->
DIAGNOSTIC_SELECTION_V6   [future, unimplemented]     (stage 9 — Step 3; runtime semantics ratified
                                                        by Amendment 3, code not authorized)

fallback (no source attempt / no hypothesis / <2 participants / all scores 0.0000 / any non-SCORABLE
status): preserve V5's existing selection exactly (Amendment 3 §K/§L/§M/§O/§P)
```

`HYPOTHESIS_DISCRIMINATION_V1` is a non-expectation deterministic discrimination score (total
variation distance), never *expected information gain* — RAMALS has no outcome-probability model to
support that computation, and Amendment 2 explicitly rejected it after analysis (historical
rationale only; see M2-ADR-034 Amendment 2 §B/§C). An M2-ADR-032 accepted advisory proposal remains
audit-only and never enters the candidate-probe pool above without its own separate, explicit ADR.
Widening `V5`'s hypothesis discovery from "first match wins" to the bounded enumeration above is
Amendment 3's own ratified decision, not an automatic assumption — Amendment 2's own frozen
sole-participant theorem (§H) makes the alternative (leaving discovery unchanged) provably
incapable of ever producing a non-zero discrimination score.

## Production-simulation evidence (crosses stages 10 and 2)

`DEFERRED` — **M2-ADR-036** (stub). A production-simulation / scenario evidence modality that
captures reasoning (`observe → hypothesize → investigate → cite evidence → decide → remediate`) and
writes the **same `ledger.evidence`**, flowing through the same deterministic mastery, confidence,
and diagnostic architecture — never an independent scoring system. Hard dependency: **M2-ADR-022**
(free-text / rubric evaluation authority, still unwritten) must resolve first. `NOW` item types are
`SINGLE_CHOICE` and `FILL_BLANK` (`SHORT_ANSWER` / `USE_CASE` authorable but unreachable, `V047`).

## The long-term data and knowledge assets (direction, not current capability)

The defensible long-term assets are the governed, versioned, auditable knowledge and evidence
structures — not the LLM. Each is at its own maturity (see the table above):

- Concept / Skill Graph + Prerequisite Graph + Misconception Graph
- Immutable evidence history
- Versioned diagnostic policies
- The deterministic mastery model and the retention model
- Intervention history and intervention outcomes
- Production-simulation evidence

The longitudinal chain these assets record — and the reason it is valuable — is:

```
Situation / Question
      ->
Learner Response / Action
      ->
Evidence
      ->
Diagnostic Hypothesis
      ->
Misconception / Root Cause
      ->
Intervention
      ->
Reassessment
      ->
Outcome
```

Every link is captured with provenance, policy/engine version, and `interactionId` / `traceId` /
`spanId` correlation, so any conclusion the platform reaches stays reproducible and auditable.

## NOW / NEXT / LATER summary

- **NOW** — evidence, mastery + confidence, prerequisite graph, misconception entity/evidence/
  confidence/reports (G2/G3/H6/H7), the misconception relationship graph and its bounded read-only
  query surface (M2-ADR-033 steps 1–2), diagnostic hypotheses, deterministic probe selection
  (`V1`–`V5`), new-evidence capture, deterministic mastery recompute; plus the inert
  `HYPOTHESIS_UNCERTAINTY_V1` (M2-ADR-034 step 1) and `HYPOTHESIS_DISCRIMINATION_V1` (step 2)
  foundations, both implemented and unwired.
- **NEXT** — a learner-scoped misconception-graph overlay and adaptive misconception-driven probing
  (each its own ADR per M2-ADR-033 §5/§6); implementing `DIAGNOSTIC_SELECTION_V6` (M2-ADR-034 Step 3
  — its runtime semantics are now ratified by Amendment 3, its own separately reviewed implementation
  PR is the immediate next milestone that would wire `HYPOTHESIS_DISCRIMINATION_V1` into selection);
  the M2-ADR-032 advisory probe-proposal contract; a time-bound goal + trajectory model; a typed
  goal-relative gap taxonomy; the still-unwritten M2-ADR-022.
- **LATER** — intervention / reassessment / outcome / effectiveness (M2-ADR-035), production-
  simulation evidence (M2-ADR-036, after M2-ADR-022), a policy-versioned NBA engine, multi-tenancy,
  privacy-governed organizational aggregation and the capability digital twin (M2-ADR-037).

## Related documents

- [`docs/adr/M2-ADR-register.md`](../adr/M2-ADR-register.md) — the authoritative MVP-2 decision
  register.
- [`docs/product/RAMALS_PRODUCT_VISION_AND_SEGMENT_ARCHITECTURE.md`](../product/RAMALS_PRODUCT_VISION_AND_SEGMENT_ARCHITECTURE.md)
  — product direction and segment boundary.
- M2-ADR-023 through M2-ADR-032 — the prerequisite-aware diagnostic-reasoning program (H1–H7), MCP,
  and the advisory AI probe-proposal boundary.
- M2-ADR-033 — stage 6c; steps 1–2 (authored edges + bounded read-only query surface) implemented,
  a learner-scoped overlay and adaptive use deferred to their own ADRs.
- M2-ADR-034 — stages 7 and 8. Amendment 1 (2026-09-10) ratified `HYPOTHESIS_UNCERTAINTY_V1` (stage
  7); step 1 (2026-09-11) implements it, inert and unwired. Amendment 2 (2026-09-11) ratified
  `HYPOTHESIS_DISCRIMINATION_V1` (stage 8); step 2 (2026-09-11) implements it, inert and unwired.
  Amendment 3 (2026-09-11) freezes the runtime semantics `DIAGNOSTIC_SELECTION_V6` (Step 3, stage 8)
  must satisfy — source-interaction evidence, bounded multi-hypothesis enumeration, activation, and
  fallback — but authorizes no `V6` code; `V6` itself remains unimplemented. See also
  `docs/adr/M2-ADR-034-step3-v6-discovery-report.md` for the discovery analysis behind Amendment 3.
- M2-ADR-035 / M2-ADR-036 / M2-ADR-037 — deferred roadmap stubs for stages 13–16, the simulation
  modality, and stages 18–19.
