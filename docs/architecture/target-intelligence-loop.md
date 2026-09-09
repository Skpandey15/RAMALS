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
INFORMATION-GAIN SELECTION
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
| 6c | **Misconception graph edges + queryable graph surface** | `DESIGNED` | `NEXT` | **M2-ADR-033** (this batch) | Authored, non-authoritative `MISCONCEPTION_RELATED` and `MISCONCEPTION_PREREQUISITE_LINK` edges, plus a bounded read-only authored-knowledge query surface. Not implemented. No adaptive selection. Extends M2-ADR-026 §8. |
| 7 | **Uncertainty** | `IMPLEMENTED` (band) / hypothesis posterior `DESIGNED` | posterior `NEXT` | `DiagnosticConfidenceCalculatorV1` bands (G3 / H5); **M2-ADR-034** for the explicit hypothesis posterior | Diagnostic/causal confidence bands exist. An explicit deterministic posterior over a candidate hypothesis set does **not** exist yet; M2-ADR-034 fixes its design constraints (design only). |
| 8 | **Information-gain selection** | `DESIGNED` | `NEXT` | **M2-ADR-034** — `DIAGNOSTIC_SELECTION_V6` composed after frozen `V5`; `INFORMATION_GAIN_V1` frozen construct | Design only; not implemented. Current selection is fixed type-priority (`V5`), not an information-gain computation. An LLM never computes the score or chooses the probe. |
| 9 | **Next best diagnostic probe** | `IMPLEMENTED` (deterministic, fixed-priority) / advisory AI proposal `DESIGNED` | deterministic `NOW`; info-gain ranking + advisory contract `NEXT` | `DIAGNOSTIC_SELECTION_V1`–`V5`; `core.diagnostic_probe_provenance` (`V055`); `SelectionReason.HYPOTHESIS_DRIVEN_PROBE`; M2-ADR-032 (advisory AI probe proposal boundary — contract **not yet built**) | Deterministic, versioned, provenance-tracked, quota `MAX_HYPOTHESIS_PROBES_PER_PACKET = 1`. |
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
  confidence/reports (G2/G3/H6/H7), diagnostic hypotheses, deterministic probe selection (`V1`–`V5`),
  new-evidence capture, deterministic mastery recompute; plus the active documentation step:
  M2-ADR-033 and M2-ADR-034.
- **NEXT** — misconception graph edges + query surface (M2-ADR-033 impl), explicit hypothesis
  posterior + information-gain selection (M2-ADR-034 impl), the M2-ADR-032 advisory probe-proposal
  contract, a time-bound goal + trajectory model, a typed goal-relative gap taxonomy, the
  still-unwritten M2-ADR-022.
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
- M2-ADR-033 / M2-ADR-034 — active decisions for stages 6c, 7, and 8.
- M2-ADR-035 / M2-ADR-036 / M2-ADR-037 — deferred roadmap stubs for stages 13–16, the simulation
  modality, and stages 18–19.
