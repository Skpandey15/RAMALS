# M2-ADR-032: Governed advisory AI diagnostic-probe proposal boundary

- **Status:** Proposed
- **Date:** 2026-09-07
- **Decides:** whether, and under what strict boundary, an AI agent that has read governed H6/H7
  evidence through MCP (MCP-1 through MCP-3.2) may propose a candidate next diagnostic
  evidence-acquisition action — distinct from diagnosing, classifying, ranking hypotheses, or
  selecting a probe to run. Fixes the constraint any future contract, gate, or Python reasoning work
  on this capability must be reviewed against, exactly as M2-ADR-023 §4 already does for H2/H5/H7.
- **Relates to:** M1-ADR-010 (AI assessment evaluation is formative-only), M2-ADR-010 (deterministic
  assessment scoring is preferred; AI evaluation is proposal-only — `docs/MVP02/RAMALS_MVP2_ADR_Package_v1.0/ADR-010_Deterministic-First_Assessment_Scoring.docx`,
  recorded in [`M2-ADR-register.md`](M2-ADR-register.md)), M2-ADR-023 (diagnostic reasoning is
  evidence, not a gate), M2-ADR-024/025 (H4b hypothesis-driven probe foundation and runtime
  selection), M2-ADR-026 (granular diagnostic ontology foundation), M2-ADR-029 (H6, the granular
  diagnostic report), M2-ADR-030 (H7, longitudinal evidence projection), M2-ADR-031 (delegated
  learner context for AI-initiated platform access).
- **Does not relate to, and does not reopen,** M2-ADR-006/007 (grounded-context/gate design for the
  MVP-2 proposal architecture generally) or MCP-1/MCP-2/MCP-3/MCP-3.1/MCP-3.2 themselves — this ADR
  governs what an agent that already has governed MCP read access is semantically permitted to
  *propose* with it, not the read/transport/authentication mechanics those PRs already shipped and
  which are unchanged here.
- **Originates here**, on the same repository-native basis as M2-ADR-023 through M2-ADR-031.

## Context

### Repository-first findings on current main (`956f76a`, PR #264 merged)

A repository-first inspection for implementing "the `DiagnosticAssessmentAgent` recommends the next
diagnostic probe, grounded in H6/H7 MCP evidence" found two independent problems before any code was
written, both confirmed directly against current main rather than assumed:

1. **The existing proposal contract cannot represent it.**
   [`DiagnosticAssessmentProposal`](../../ramals-ai/src/ramals_ai/diagnostic_assessment/contracts.py)
   is a skill-by-skill mastery-evidence reading — `diagnoses: [{skillCode, classification:
   STRONG|WEAK|INCONSISTENT|INSUFFICIENT_EVIDENCE, reason, evidenceIds}]`, `recommendedNextSkills:
   list[str]`, `confidence: float` — with no field for a target misconception, a target ontology
   node, or a probe intent. Its own module docstring is explicit that this is deliberate: *"The name
   is deliberately not `DiagnosticProposal`. MVP-1 already owns that word for a different thing — a
   proposal about what to probe next — and its gate exists partly to refuse the verdicts this
   contract requires."* The wire contract,
   [`contracts/mvp2/diagnostic-proposal.v1.schema.json`](../../contracts/mvp2/diagnostic-proposal.v1.schema.json),
   declares `"additionalProperties": false` at both the proposal and diagnosis level and
   `"contractVersion": {"const": "1.0"}`; Python's own Pydantic model enforces `extra="forbid"`. Even
   if a field were force-fit in, Java's own parser,
   [`DiagnosticAssessmentProposal.java`](../../learning-platform/src/main/java/io/ramals/learningplatform/diagnosticassessment/DiagnosticAssessmentProposal.java),
   reads exactly four keys (`diagnoses`, `recommendedNextSkills`, `confidence`, `contractVersion`)
   and silently ignores anything else — meaning a new field would carry **zero gate-side
   validation**, the opposite of what §11 below requires. §21 records the consequence; this ADR does
   not design that contract.

2. **Adjacent, already-accepted governance appears to bear directly on the underlying behavior, not
   only the contract shape.** [M2-ADR-025](M2-ADR-025-hypothesis-driven-probe-runtime-selection.md)
   §10, governing the one mechanism this codebase already has for "what to probe next"
   (`DIAGNOSTIC_SELECTION_V5`), states verbatim: *"V5 ... does not add LangGraph, does not introduce
   same-attempt dynamic questioning, and **does not ask an LLM to choose a probe**. Every one of
   trigger eligibility, relationship lookup, ambiguity handling, candidate selection, priority,
   tie-breaks, quota, and provenance is deterministic and reproducible from already-authoritative
   inputs."* [M2-ADR-023](M2-ADR-023-diagnostic-reasoning-is-evidence-not-a-gate.md), under
   "Alternatives rejected," records: *"Let an AI model rank or classify root-cause hypotheses ...
   Rejected on the same grounds M1-ADR-010/M2-ADR-010 already settled for evaluation: an AI-authored
   classification that determines what a learner is told their gap is would be a new authoritative
   AI role this project has consistently refused to grant anywhere else."*

Both statements are confirmed, verbatim, on current main — not accepted from any external prompt.
Whether an advisory-only, Java-gated proposal about what evidence to collect next is the same
authoritative role those two ADRs already rejected, or a narrower, distinguishable one, is an
architecture question this project's own discipline requires answering explicitly, in writing,
before any contract or implementation work begins — the same discipline M2-ADR-023 §4 states of
itself ("this ADR binds design, it does not authorize construction") and M1-ADR-010 states of
itself ("any future authoritative AI-assisted scoring requires a new ADR ... with an explicit
item-type allowlist and deterministic validation controls").

### The question this ADR answers

> Can RAMALS permit an AI to recommend **what evidence to collect next**, without permitting the AI
> to decide **what the learner's diagnosis is**?

### Repository precedent this ADR builds on, not around

M1-ADR-010 already drew a structurally identical line for a different endpoint, and its shape is the
template this ADR follows: `POST /internal/v1/assessment/evaluate` **may** return "formative
feedback, classifications, rubric suggestions and reviewer assistance" — genuinely evaluative,
natural-language AI output — but **must not** create evidence, determine or persist a score, or
affect mastery/progression. The distinguishing fact is not that the AI's words are harmless; it is
that nothing downstream treats them as authoritative without an independent, deterministic,
privilege-enforced gate in between (`ramals_ai_runtime` holds no privilege on `ledger` at all —
M1-ADR-010's own verification section). This ADR asks whether the same shape — AI produces
natural-language, evidence-referencing output; a deterministic gate independently decides whether it
has any effect; the AI role carries no privilege to enforce anything itself — can be drawn for
diagnostic-probe recommendation the way it was already drawn for assessment evaluation, without
reopening M2-ADR-023's or M2-ADR-025's own rejections.

## Decision

### 1. The semantic distinction, stated precisely, and why it survives M2-ADR-023's rejection

Two categorically different claims must never be confused, and this ADR names them so that no future
code, prompt, or contract can accidentally conflate them:

- **Diagnosis / classification** — an assertion about what is true *of the learner*: which
  misconception they hold, what their root cause is, how confident the platform is that a specific
  authored misconception explains their pattern of wrongness, what their mastery is. This is, and
  remains after this ADR, **exclusively Java's**, computed by named, versioned, deterministic
  calculators (`DiagnosticConfidenceCalculatorV1`, `LONGITUDINAL_EVIDENCE_V1`,
  `WeightedMasteryCalculator`, `EvidenceConfidenceCalculatorV2`) from persisted evidence alone, per
  M1-ADR-010/M2-ADR-010/M2-ADR-023, with **zero exception carved out by this ADR**.
- **Evidence-acquisition recommendation** — a claim about which *currently unresolved ambiguity in
  the evidence already on record* would benefit from one additional, bounded, discriminating
  observation. It asserts nothing about the learner. It never says a misconception is true, likely,
  resolved, or ruled out; it says only that collecting evidence of a specific kind would narrow an
  ambiguity the governed H6/H7 projections already show exists. Structurally, it is closer to a lab
  technician recommending which test to run next than to a diagnosis: "run test P" is not a claim
  about what the patient has.

M2-ADR-023's rejected alternative was **"an AI model rank or classify root-cause hypotheses"** in a
way that **"determines what a learner is told their gap is."** Both halves of that description are
absent from the role this ADR considers: the recommendation never ranks or classifies which
misconception is more likely true (it names *at most one* candidate for *additional evidence*, never
a comparative judgment among hypotheses), and it is never learner-facing — it is an internal proposal
returned through the existing non-authoritative AI-proposal architecture (M2-ADR-001/M2-ADR-007) to
Java alone, which independently decides whether it has any effect at all, exactly as every other
non-authoritative AI proposal in this codebase already does. A recommendation with zero operational
effect unless an independent deterministic gate accepts it is not "the AI determining what the
learner is told" in the sense M2-ADR-023 refused; it is the same "agents recommend, deterministic
services decide" invariant this whole MVP-2 register is built on (`M2-ADR-register.md`'s own
governing invariant, verbatim), applied at finer grain than it has been applied before.

This distinction is genuinely narrow, and this ADR does not pretend otherwise. §2 through §21 exist
specifically because a distinction stated once in prose is not a boundary; it is only a boundary once
every consuming system enforces it independently of the model's own good behavior.

### 2. What the AI may do

An agent operating under this boundary (initially `DiagnosticAssessmentAgent`; any future agent
claiming this role must be reviewed against this same ADR, not assumed to inherit it) **may**:

- read authorized governed diagnostic evidence through the existing MCP-3/3.1/3.2 path
  (`diagnostics.current-domain-report`, `diagnostics.longitudinal-summary`, and, only when required
  to resolve a specific ambiguity, `diagnostics.misconception-longitudinal-detail` and
  `diagnostics.attempt-report` — the same four capabilities MCP-3.1's own
  `AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES` already delegates, and
  `DIAGNOSTIC_AGENT_CAPABILITIES`/MCP-3.2 already grant this agent locally; this ADR authorizes no
  sixth);
- reason over the persisted H6/H7 projections those reads return;
- propose **at most one** bounded next diagnostic-probe candidate per interaction (§17);
- reference an existing, already-authored misconception exposed to this interaction (§11);
- reference existing governed evidence/provenance identifiers actually supplied to this interaction
  (§10);
- state, in bounded natural language, why additional evidence would be diagnostically useful (§8/§9
  constrain the vocabulary this may use);
- propose evidence *acquisition*, never a diagnosis, a classification, or a probability;
- return the proposal through the existing non-authoritative AI-proposal architecture to Java, and
  nowhere else.

### 3. What the AI must never do

The agent **must not**:

- declare that a misconception is true, likely, confirmed, or ruled out;
- create, name, or infer a misconception that does not already exist as a `PUBLISHED` authored
  entity (M2-ADR-026 §4) — an incorrect response is never, by itself, evidence of a misconception the
  agent invents;
- classify an arbitrary wrong answer as evidence of a misconception outside the governed
  wrong-option/semantic mapping M2-ADR-026/027 already define;
- declare or imply a root cause;
- rank, order, or compare candidate root causes or misconceptions against one another;
- calculate, adjust, or restate G2 evidence classification, G3 diagnostic confidence, H5 causal
  confidence, H7 longitudinal state, or mastery — all remain exclusively Java-computed, read-only
  inputs to the agent, never outputs it may produce or revise;
- decide prerequisite weakness, progression eligibility, or scoring;
- write to `ledger.evidence` or any authoritative table — this agent holds, and this ADR grants, no
  new database privilege of any kind (unchanged from M1-ADR-005's "the AI plane holds no database
  credential at all");
- mutate learner state or learner journey state in any way;
- activate, execute, or dispatch the probe it recommends — only Java's existing deterministic
  selection machinery (H4b/`DIAGNOSTIC_SELECTION_V1`–`V5`, or a future extension of it) may do that,
  and this ADR does not modify that machinery;
- choose, supply, or influence which learner the recommendation concerns — learner identity remains
  derived solely from the verified M2-ADR-031 delegated context, unchanged, with no exception;
- broaden its own delegated domain or MCP capability scope beyond what M2-ADR-031/MCP-3.1 already
  authorize for this interaction;
- convert an evidence-strength band (G3, H7) into a probability, a percentage, or any other
  probabilistic-sounding claim (§8);
- state or imply that later-only-contradictory evidence (H7 `LATER_CONTRADICTION_ONLY`) means a
  misconception is resolved, corrected, or that mastery is restored (§9);
- generate a new assessment item, question, or probe content of any kind (§16);
- request, cause, or imply same-attempt mutation of the assessment the learner is currently taking
  (§6);
- loop, re-plan, or issue a second MCP read cycle based on its own prior output within one
  interaction (§17) — this ADR authorizes one bounded reasoning pass, not autonomous iteration.

### 4. What Java must independently validate — "Java validates" is not itself the rule

A syntactically well-formed AI proposal must still be rejectable. This ADR requires that any future
Java gate for this capability validate **at minimum** all of the following, independently of
anything the AI asserted about itself, before the recommendation may have any operational effect —
naming these now is what makes §1's distinction enforceable rather than aspirational:

1. proposal schema and contract version;
2. learner binding — the interaction's own authoritative learner, never a value read from the
   proposal;
3. interaction binding — the proposal concerns the interaction that produced it, not a stale or
   unrelated one;
4. domain binding — the proposal's domain matches the interaction's authorized domain;
5. the target misconception exists;
6. the target misconception is within this interaction's authorized diagnostic scope;
7. the target ontology entity (objective, concept, or sub-concept per M2-ADR-026's exclusive arc)
   exists and is valid;
8. every evidence/provenance reference the proposal cites was actually supplied to this interaction
   (§10, `E_proposed ⊆ E_allowed`);
9. that evidence belongs to the authorized learner;
10. that evidence concerns the referenced misconception/domain;
11. the recommended probe intent resolves to an existing, eligible, authored assessment/probe
    object, **or** a future deterministic Java-controlled mechanism independently resolves the
    advisory intent to one — the AI's own naming of a probe is never itself sufficient authorization
    to run anything;
12. the proposal contains no identifier outside the authorized/allowed sets;
13. the proposal does not request an authority the AI does not possess (e.g., a capability outside
    its delegated allowlist, a learner outside its delegated scope);
14. proposal/policy/schema version compatibility with the currently deployed gate;
15. Java independently decides whether, and how, the recommendation is used — acceptance is never
    automatic even when every check above passes.

Every one of these is a **fail-closed** check (§13): a check that cannot be completed (unavailable
data, ambiguous binding, version mismatch) is a rejection, never a best-effort acceptance, and never
grounds for falling back to a broader or default authority.

### 5. Relationship to M2-ADR-023 — this ADR does not overturn it

M2-ADR-023 protects one question: **"what is the learner's authoritative gap/root cause, and how
confident is the platform that it explains their weakness?"** That question's answer remains,
unconditionally, Java's own — computed by named, versioned, deterministic calculators, never fed by,
adjusted by, or overridable by anything this ADR authorizes. This ADR governs a narrower, different
question: **"given the evidence already on record, what additional evidence would be useful to
collect?"** The second question's answer can be wrong, unhelpful, or simply ignored without the
first question's answer ever being affected — because §4's Java gate sits strictly between the AI's
proposal and any operational effect, and because §3 forbids the AI from ever producing a diagnosis,
classification, ranking, or confidence value in the first place. If a future implementation ever
allows an accepted recommendation to influence which misconception a report *displays* as more
likely, or to feed any diagnostic-confidence calculation, that would cross back into the role
M2-ADR-023 rejected and would require reopening that ADR, not a quiet extension of this one.

### 6. Relationship to M2-ADR-025 — this ADR does not reopen same-attempt dynamic questioning

M2-ADR-025 §10 is explicit that `DIAGNOSTIC_SELECTION_V5` "does not ask an LLM to choose a probe" and
does not introduce same-attempt dynamic questioning. This ADR preserves both facts unmodified:

- `DIAGNOSTIC_SELECTION_V1`–`V5`, their composition order, trigger eligibility, quota
  (`MAX_HYPOTHESIS_PROBES_PER_PACKET = 1`), and provenance model are **untouched**. This ADR
  authorizes no change to any of them, and no future implementation of this ADR may route an accepted
  recommendation through V5's own selection call in a way that lets the AI supply what V5 currently
  computes deterministically.
- The recommendation this ADR governs is consumed, if ever accepted, **only as advisory input toward
  a subsequent diagnostic interaction** — never as a live mutation of the assessment attempt the
  learner is currently taking. No implementation of this ADR may alter question order, question
  content, or item selection within an attempt already in progress. If a future product decision
  wants the AI's recommendation to inform V5 or a successor selector, that is a new, separate
  decision requiring its own ADR review against M2-ADR-025's own frozen composition order — not an
  implicit consequence of this one.
- Timing and lifecycle of *when* an accepted recommendation is actually surfaced to a future
  interaction are deliberately left to the implementation PR this ADR authorizes (§21's sequence),
  not fixed here. What is fixed here is the boundary: not this attempt, not this active assessment.

### 7. Relationship to M2-ADR-031 — authorization is not semantic authority

M2-ADR-031 governs *whether* `ramals-ai` may call into Java's MCP surface at all, for which learner,
domain, and capability set, on behalf of an interaction Java itself already authorized. It answers
"who may read what." This ADR governs a completely separate question: what the AI is semantically
*permitted to say* once it has legitimately read that data. A valid delegated context under
M2-ADR-031 proves the read was authorized; it proves nothing about whether any given output the AI
produces afterward is safe to act on — that is exactly what §2/§3/§4 exist to answer, and no amount
of correct M2-ADR-031 plumbing substitutes for them.

### 8. G3 semantics preserved, and explicitly protected from AI restatement

G3 (persisted diagnostic confidence) means: *the strength of accumulated governed evidence supporting
a specific authored misconception, under the governed, versioned confidence policy currently in
force.* It does not mean probability, diagnosis certainty, root cause, mastery, or "the learner
probably has this misconception." Any future prompt or reasoning implementation under this ADR MUST
reproduce G3 values verbatim (`HIGH`/`MODERATE`/`LOW`/`INSUFFICIENT_EVIDENCE` or whatever the current
`DiagnosticConfidenceCalculatorV1` band vocabulary is) and MUST NOT translate a band into a
percentage, a probability, or comparative language ("more likely," "less likely") between
misconceptions. An advisory proposal may say a band is `HIGH` and that additional evidence would
still be useful to collect (§1's evidence-acquisition framing); it may never say a band being `HIGH`
means the misconception is confirmed.

### 9. H7 semantics preserved, and explicitly protected from AI reinterpretation

H7 (longitudinal evidence) answers: *what does governed evidence recorded after a deterministic
baseline evidentiary anchor say?* Its five states —
`NO_LATER_EVIDENCE`/`LATER_INCONCLUSIVE_ONLY`/`LATER_SUPPORT_ONLY`/`LATER_CONTRADICTION_ONLY`/`LATER_MIXED_EVIDENCE`
— and its independent `LongitudinalDataStatus` (`NO_BASELINE`/`HAS_BASELINE`) must be reproduced
exactly, per M2-ADR-030's own forbidden-terminology list
(`VERIFIED`/`CONFIRMED`/`RESOLVED`/`CURED`/`RECURRENCE`/`REGRESSION`/`REVERSAL`/`ROOT_CAUSE`), which
this ADR adopts unchanged and extends to every future AI-facing surface of H7, not only Java's own
report endpoints. In particular:

- `NO_BASELINE` is never treated as, or reported as, `NO_LATER_EVIDENCE` — they are independent
  facts (M2-ADR-030).
- `LATER_CONTRADICTION_ONLY` does not mean the misconception is resolved, that the learner is
  corrected, that mastery is restored, or that the learner has recovered. The only conclusion an
  advisory proposal may draw from it is that additional evidence would help discriminate the
  remaining uncertainty — exactly the wording pattern in this ADR's own title: an
  evidence-acquisition recommendation, never a resolution claim.

### 10. Ontology semantics preserved

`LearningObjective → Concept → Sub-concept` (M2-ADR-026 §1) is a content-driven, optional
refinement, never a mandatory decomposition and never itself counted toward `objectiveCoverage` or
mastery. `Misconception` is a separate, first-class authored entity targeting **exactly one** node —
an objective, a concept, or a sub-concept — via the DB-enforced exclusive arc M2-ADR-026 §4 defines,
with its own `DRAFT`/`PUBLISHED` lifecycle. An incorrect response is not automatically evidence of a
misconception; it becomes evidence only through the governed wrong-option/semantic mapping
M2-ADR-026/027 already define. Any future implementation under this ADR MUST reference misconceptions
and their target nodes exactly as M2-ADR-026 defines them, and MUST NOT let an agent infer a new
misconception from arbitrary incorrect learner text.

### 11. Evidence citation rule

Let `E_allowed` be the exact set of governed evidence/provenance references actually supplied to the
AI for this interaction (the MCP results this specific call received — never a wider set the AI could
plausibly guess at). Any future proposal contract MUST require that every evidence reference the
proposal cites, `E_proposed`, satisfies `E_proposed ⊆ E_allowed`, and Java's gate (§4 item 8) MUST
enforce this independently of the prompt. A reference outside `E_allowed` is rejected outright; this
ADR does not rely on prompt instructions alone to prevent invented citations, consistent with how
`diagnostic_assessment/validation.py`'s existing `EVIDENCE_NOT_IN_CONTEXT` check already treats a
fabricated identifier for the current, differently-shaped contract.

### 12. Identifier rule

Let `M_allowed` be the governed, authored misconception identifiers exposed to this interaction (via
the governed H6/H7 MCP reads actually performed). If a proposal references misconception `M`, `M`
must be a member of `M_allowed`, **and** Java must independently validate `M` against authoritative
state (existence, publication status, domain/scope binding — §4 items 5–7) regardless of what the AI
claims about it. The LLM is never the authority for whether an identifier is valid; only Java's own
lookup is.

### 13. Probe eligibility is distinct from an AI recommendation

The AI may name a *candidate* — an intent to collect more evidence, optionally naming a specific
probe object it believes would serve that intent. It may never determine that the candidate is
*eligible to execute*. Only deterministic Java policy — the existing H4b/V1–V5 selection machinery,
or a future extension reviewed on its own terms — may decide eligibility, considering publication
state, domain, ontology target, assessment/probe lifecycle, learner authorization,
duplication/repetition policy, current interaction policy, and any other deterministic diagnostic
policy already governed elsewhere. This ADR does not authorize duplicating any of those policies in
Python; a future implementation must call into Java's existing (or purpose-built, deterministic)
eligibility mechanism, never reimplement one.

### 14. Fail-closed rule

If Java cannot validate any of §4's items — target, evidence, scope, probe eligibility, or policy —
the recommendation is rejected. There is no "best effort" partial acceptance, and no fallback
authority for the AI to fill the gap left by a failed validation. A validation Java cannot complete
is treated identically to one that failed outright.

### 15. No-evidence (`diagnosticDataStatus = NO_EVIDENCE`) rule

When H6 reports `NO_EVIDENCE`, the AI must not fabricate a misconception-targeted recommendation —
there is nothing to target. A future implementation may recommend a broad, deterministic,
policy-defined initial diagnostic action **only if Java's existing deterministic system already
defines cold-start diagnostic behavior for this circumstance**, and only by deferring to that
existing policy rather than inventing one in the prompt. As of this ADR, this repository was not
found to define such a deterministic cold-start policy for the diagnostic-assessment path
specifically; if the implementation PR (§21) confirms that gap still exists, the correct behavior is
the same non-MCP degrade this whole MCP series already establishes elsewhere (proceed without a
probe recommendation), never an AI-invented substitute for a policy that does not exist.

### 16. No AI-generated assessment items

This ADR governs recommendation of a diagnostic probe *target/intent* only. It does not authorize the
AI to generate a new assessment item, question, or probe content of any kind. If a future
architecture wants AI-authored diagnostic questions, that is a separate authority/security/evaluation
decision requiring its own ADR — this one does not fold it in, silently or otherwise.

### 17. One proposal, not autonomous planning

The governed capability is deliberately bounded to: one interaction → bounded governed evidence → at
most one next-probe recommendation → Java accept/reject. This ADR does **not** authorize an
autonomous diagnostic loop (propose → observe → propose again, repeated), does not authorize the
agent to issue a second round of MCP reads based on its own first-round output within one
interaction, and does not authorize multi-step planning of any kind. A future implementation that
wants iterative behavior needs its own ADR review, not an inferred extension of this one.

### 18. MCP authority unchanged; no MCP-4 authorized

MCP remains read-only for this and every stage this ADR governs. This ADR authorizes no MCP write
tool, no MCP proposal tool, no generic execute capability, and no new MCP capability of any kind —
the existing five MCP-2 capabilities are unchanged and remain the entire MCP surface. The
advisory recommendation this ADR governs returns exclusively through the existing, non-MCP,
governed AI-proposal architecture (the same `AIProposalEnvelope`/gate path every other proposal-only
agent already uses), never through a new MCP mutation path. Advisory diagnostic-probe reasoning does
not itself require, and this ADR does not authorize, MCP-4. Any future MCP write/proposal capability
remains a wholly separate architectural decision, gated on its own explicit review.

### 19. Auditability

A future accepted or rejected proposal must be auditable well enough to answer: which interaction
produced it; which prompt template and version; which provider/model/route; which governed evidence
references were supplied to the AI (`E_allowed`); which references the AI actually cited
(`E_proposed`); which misconception/target it referenced; which proposal schema/contract version;
which Java gate/policy version evaluated it; whether it was accepted or rejected, and under which
rejection-reason category. This ADR does not require storing raw prompts or model chain-of-thought
beyond what existing governance already permits (M2-ADR-005's "no raw prompts or outputs" provenance
discipline, and M2-ADR-017's identical prohibition for Contract B, both unchanged and both apply).

### 20. Determinism of the gate, not of the proposal

The AI's proposal is, and remains, non-deterministic — the same model call could recommend
differently on a re-run. The authority boundary must not inherit that non-determinism: given one
proposal and Java's own authoritative state, the accept/reject gate (§4) must be governed by an
explicit, versioned policy and must be deterministic and reproducible, the same discipline every
other deterministic gate in this codebase (`DiagnosticAssessmentProposalGate`,
`ProposalGroundingGate`, `DiagnosticConfidenceCalculatorV1`) is already held to.

### 21. Security posture unchanged

Workload identity, the M2-ADR-031 delegated learner context, and the exact MCP capability scope this
ADR's §2 lists remain exactly as MCP-3/3.1/3.2 already established. This ADR grants the AI no new
authority to choose a learner, broaden a domain, or broaden an MCP capability set — every constraint
M2-ADR-031 already enforces (learner identity derived solely from the verified delegated token,
capability scope explicit and local, never remote-discovered) applies unchanged to whatever
implementation follows this ADR.

### 22. Proposal contract consequence

The existing `DiagnosticAssessmentProposal` (v1.0) contract cannot represent this capability safely
(see Context, finding 1) — this is not a design preference this ADR introduces, it is a fact about
the currently shipped, closed, versioned schema. Implementing this ADR's role therefore requires a
**separate, versioned proposal contract change**, reviewed and built in its own PR (§23), not folded
into this one. This ADR does not design that JSON schema, but the future contract MUST, at minimum:

- declare an explicit proposal *type* distinguishing it from the existing skill-classification
  proposal (never overload one field to mean two different verdict shapes);
- carry only existing, governed target identifiers (misconception id, ontology node reference) — no
  freeform target description;
- carry evidence/provenance references, validated per §11;
- carry a bounded rationale string, with an explicit maximum length, matching this codebase's
  existing bounded-string convention (`Diagnosis.reason`'s own 1000-character bound);
- carry **no field named or shaped like a bare `confidence`** that could be confused with G3
  diagnostic confidence, H5 causal confidence, or mastery confidence — if an AI-specific
  quality/support concept is genuinely required, it must use a distinct name (e.g.
  `proposalQuality`/`reasoningSupport`) and must be documented, in the contract itself, as none of
  those governed concepts; the strong preference is to avoid such a field entirely unless a concrete
  future need requires one;
- carry its own contract version, independent of `diagnostic-proposal.v1.schema.json`'s;
- reject unknown fields, exactly as the existing contract already does;
- be validated by Java (§4) before any operational use — never trusted on arrival.

## Alternatives considered

- **A. Reject all AI diagnostic-probe recommendations; keep deterministic probe selection only.**
  The safest option, and the default this ADR is measured against. Not chosen as the final decision
  (see below), but this ADR's own §1 distinction is deliberately drawn narrowly enough that, if a
  future reviewer concludes it does not hold, falling back to Option A costs nothing already built —
  no contract, gate, or Python reasoning work is authorized until a separate PR (§23) implements it.
- **B. Allow the AI to authoritatively select the next probe.** Rejected outright. This would make
  the AI, not Java, the thing that decides learner flow — precisely the inversion M2-ADR-023,
  M2-ADR-025 §10, M1-ADR-010, and M2-ADR-010 all already refuse, and this ADR does not attempt to
  relitigate that refusal.
- **C. Allow the AI to recommend one bounded probe candidate, with deterministic Java validation and
  independent acceptance/rejection.** **Chosen.** This is the role §1–§22 define and bound. It
  preserves M2-ADR-023's and M2-ADR-025's own rejections intact (§5/§6) by keeping the AI's output
  structurally incapable of being a diagnosis, a ranking, or a probe activation, and by requiring an
  independent, fail-closed, deterministic gate (§4/§14) before anything it proposes can matter at
  all.
- **D. Allow the AI to generate arbitrary new diagnostic questions.** Out of scope and not
  authorized by this ADR (§16). A separate authority/security/evaluation decision, if ever wanted.
- **E. Add an MCP write/proposal capability for probe selection.** Rejected as unnecessary for this
  stage (§18). The advisory role this ADR authorizes returns through the existing non-MCP AI-proposal
  path; no MCP capability change is required to carry it.

## Final decision

**ACCEPTED WITH STRICT AUTHORITY BOUNDARY.**

An agent operating under this ADR may propose evidence acquisition — at most one bounded next
diagnostic-probe candidate per interaction, referencing only governed evidence and existing authored
misconceptions actually exposed to that interaction, in bounded natural language that names an
unresolved evidentiary ambiguity and never a diagnosis. Java alone independently decides whether that
proposal has any operational effect, under the fail-closed, deterministic, versioned gate §4 and §14
require. Nothing in this ADR authorizes the AI to diagnose, classify, rank, calculate any governed
confidence construct, activate a probe, mutate an active assessment, or acquire any authority beyond
what M2-ADR-031 already delegates. §22 records that this role has zero operational reality until a
separate, versioned proposal contract and Java validation gate are built and reviewed in their own
right (§23) — accepting this ADR authorizes that future design work to proceed against a fixed
boundary; it does not itself change any running system.

### Answering the success criterion directly

- Can an LLM diagnose the learner? **No.**
- Can an LLM declare a misconception true? **No.**
- Can an LLM declare root cause? **No.**
- Can an LLM calculate diagnostic confidence (G3/H5/H7/mastery)? **No.**
- Can an LLM choose the learner? **No.**
- Can an LLM activate a probe? **No.**
- Can an LLM autonomously alter an active assessment? **No.**
- Can an LLM recommend one bounded next evidence-acquisition probe candidate? **Yes — this ADR
  accepts that role, strictly bounded by §2–§22.**
- Who decides whether that recommendation is valid and used? **Java, exclusively, under §4/§14.**
- What happens when validation fails? **Reject; no operational effect; no fallback authority.**

## Recommended implementation sequence

Recorded as a recommendation, not a normative numbering commitment — actual PR numbers depend on
what else lands on `main` in the meantime:

1. **This PR** — M2-ADR-032 only. No runtime change.
2. **A versioned diagnostic-probe proposal contract PR** — the new contract §22 requires, plus the
   Java validation/gate semantics §4 requires. No LLM reasoning change; the gate must be buildable
   and testable against synthetic proposals before any model ever produces a real one.
3. **A Python reasoning implementation PR** — the bounded H6/H7 evidence projection and probe-
   recommendation generation this ADR's §2/§3 constrain, wired against the contract PR (2) shipped.
4. **An evaluation/semantic-safety harness PR** — fixtures proving evidence-grounded recommendations,
   rejection of unsupported claims, rejection of invented misconception/evidence identifiers,
   rejection of semantic overreach (probability language, "resolved" claims), correct H7/G3
   interpretation, and correct no-evidence handling, the same discipline this repository's existing
   qualification/evaluation suites already hold every other agent to.

## Consequences

- `DiagnosticAssessmentAgent` (or any future agent claiming this role) gains no new authority from
  this ADR alone — it authorizes future design and construction, exactly as M2-ADR-023 §4 and
  M2-ADR-024's own PR-251/PR-#? footnote already establish is this project's convention for
  foundation-vs-authorization ADRs.
- Any future contract PR that reintroduces a bare `confidence` field on this new proposal type, or
  that lets an accepted recommendation feed G3/H5/H7/mastery computation, is a defect against this
  ADR, not a permissible extension.
- Any future implementation that lets the AI's recommendation mutate an in-progress assessment
  attempt, or that removes Java's independent accept/reject step, is a defect against §5/§6, not a
  simplification.
- `DIAGNOSTIC_SELECTION_V1`–`V5` remain exactly as M2-ADR-024/025 left them; this ADR changes none of
  their code, composition order, or quota.
- The existing `DiagnosticAssessmentProposal` (v1.0) contract, its JSON schema, and its Java parser
  are unchanged by this ADR (§22) — a separate PR carries that change, reviewed on its own terms.

## Revisit triggers

- If a future reviewer concludes §1's distinction does not survive scrutiny once real prompts and
  real Java gate code exist (not merely in this ADR's prose), the correct response is reverting to
  Alternative A, not silently weakening §3/§4 to make the distinction "work" in practice.
- If a product decision ever wants an accepted recommendation to influence a diagnostic report's
  *display* (which misconception is shown more prominently, say) rather than only to seed a future
  evidence-collection interaction, that crosses back toward M2-ADR-023's own protected question and
  needs that ADR reopened, not a quiet reading of this one.
- If a genuine need for iterative, multi-step AI diagnostic reasoning emerges, §17's one-proposal
  bound must be revisited deliberately, with its own ADR, not extended implicitly.
- If cold-start (`NO_EVIDENCE`) diagnostic behavior is later explicitly defined by a deterministic
  Java policy, §15 should be revisited to reference it directly rather than defer to "no policy
  exists yet."

## Note on the ADR register

Adds `M2-ADR-032` to [`docs/adr/M2-ADR-register.md`](M2-ADR-register.md)'s "Decisions originating in
this repository" table, immediately after `M2-ADR-031`, in the same row format already used for
`M2-ADR-016` through `M2-ADR-031`. `docs/adr/README.md`'s own index does not enumerate individual
M2-ADRs (it only points to the M2 register), and `AdrRegisterTests` matches only `M1-ADR-\d{3}`
filenames, so neither requires a change for this addition.
