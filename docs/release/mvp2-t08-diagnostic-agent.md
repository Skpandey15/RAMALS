# M2-T08 - Diagnostic Agent (proposal-only)

- **Status:** Implemented
- **Architecture:** M2-ADR-001, M2-ADR-006, M2-ADR-007, M2-ADR-008, M2-ADR-012, M2-ADR-014, M2-ADR-015
- **Invariant:** Agents recommend; deterministic Spring services decide.

## Prerequisite status on current `main`

The execution board spreadsheet still shows every task `Not Started / BLOCKED`; that source is not
edited here, because the master plan does not assign it to T08. The actual prerequisite state, read
from `main`, is:

| Task | Landed as | State |
| --- | --- | --- |
| M2-T02 transactional outbox | #117 | merged |
| M2-T03 durable dispatcher | #118 | merged |
| M2-T04 AI provenance v2 | #119 | merged |
| M2-T05 GroundedContext contract | #120 | merged |
| M2-T06/T07 retrieval + grounding validation | #121 | merged |

T08's hard gate (T02, T03, T04 accepted before the Diagnostic Agent) is therefore satisfied.

## Naming

MVP-1 already owns the word "diagnostic". Its `DiagnosticAgent` proposes the *next probe*
(`skillCode`, `objectiveCode`, `difficulty`), and its Java `DiagnosticProposalGate` exists partly to
**refuse** any inferred verdict — `inferredStatus` is described there as "Present only so the gate can
refuse it; it is never adopted."

MVP-2's `diagnostic-proposal.v1` asks for the opposite: a *classification* of learner state, made
accountable by mandatory evidence references and a deterministic gate. These are two different
semantics, so they get two different names and share no code:

| | MVP-1 | MVP-2 (this task) |
| --- | --- | --- |
| Python model | `DiagnosticAgent` | `DiagnosticAssessmentAgent` |
| Proposal | probe (`objectiveCode`) | `DiagnosticAssessmentProposal` |
| Prompt template | `DIAGNOSTIC_ROOT_CAUSE` | `DIAGNOSTIC_ASSESSMENT` |
| Validator | `diagnostic/validation.py` | `diagnostic_assessment/validation.py` |
| Java gate | `DiagnosticProposalGate` (unchanged) | `DiagnosticAssessmentProposalGate` (reserved, T09) |

The MVP-1 verdict-language rules are untouched and still apply to MVP-1 proposals. The MVP-2
validator is a separate module; it does not relax them, it answers a different question.

## Execution mapping

```
GroundedContext (v1, Spring-built, fail-closed on consume)
        |
        |  require_grounding({MASTERY, LEARNER_EVIDENCE})   <- stale / missing sources fail closed
        |  permitted evidence ID set derived from AUTHORITATIVE_FACT items only
        v
AgentState (existing LangGraph state; minimized_learning_context = bounded context projection)
        |
        |  load_context -> policy_precheck -> plan
        v
model_or_tool  (existing gateway: route resolution, budget ceilings, provenance v2 stamped)
        |
        v
raw structured output
        |
        |  validate_output -> bounded_repair -> validate_output ...   (existing bounded loop)
        v
local validation (MVP-2 validator)
        |  schema shape, classification enum, confidence range,
        |  every evidenceId present in the permitted set, no fabrication
        v
DiagnosticAssessmentProposal  --serializes exactly--> contracts/mvp2/diagnostic-proposal.v1.schema.json
        |
        v
AIProposalEnvelope (trustLevel = NON_AUTHORITATIVE, provenance v2 carried through)
```

Authority is unchanged at every step. The agent computes no mastery, writes no domain table, and its
`recommendedNextSkills` are strings in a payload — nothing consumes them as state.

## Scope boundary

T08 delivers the agent, its contract, its prompt and its validation. It does **not** add an internal
HTTP route and does **not** touch `contracts/ai-internal.openapi.yaml`.

That is deliberate. `contracts/generated.py` is generated from the OpenAPI contract and guarded
against a frozen baseline, and `AIRequestEnvelope` forbids unknown fields — so a transport for a
GroundedContext-carrying request is a contract change, not agent work. Defining it here would freeze
a request shape before T09 exists to consume it. The agent's entry point is
`DiagnosticAssessmentAgent.propose(...)`, and T09 wires the transport it needs.

## Routing

The existing `diagnostic-default` route serves the new template; no new route and no new budget were
invented. Doc 04 governs per-route ceilings and has no entry for a second diagnostic route, and
`assessment-default` already sets the precedent of one route serving two templates. `ROUTE_TABLE_V1`
is not bumped: the shipped table gains a capability, and no existing template pointer changes, so no
previously recorded execution identity is affected.

## Failure behaviour

All of these end with no proposal adopted and no authoritative state touched, because the agent never
had the ability to touch it:

| Condition | Result |
| --- | --- |
| Provider timeout / deadline expiry | `AiUnavailableException` path; run ends, nothing emitted |
| Malformed JSON | validation errors recorded, bounded repair, then a proposal marked invalid |
| Fabricated evidence ID | rejected locally; also rejected by `ProposalGroundingGate` in Spring |
| Missing required grounding | `GROUNDING_REQUIRED_SOURCE_MISSING` before any model call |
| Exhausted bounded repair | finalize with `schemaValid=false`; the envelope carries reason codes |

## Qualification mapping

| Scenario | Covered by |
| --- | --- |
| E01 weak-skill diagnosis happy path | `test_e01_weak_skill_diagnosis_happy_path` |
| E03 single-variable perturbation | `test_e03_single_variable_perturbation_moves_only_the_changed_skill` |
| E04 malformed JSON | `test_e04_malformed_json_is_rejected_and_emits_no_diagnosis` |
| E07 provider timeout | `test_e07_provider_timeout_leaves_no_proposal` |

E05, E06, E08 and E09 are gate-side and belong to T09.

## Rollback

Additive only. Removing the module, its prompt artifact and the template entry returns the image to
the previous behaviour; no migration, no schema change, no change to any MVP-1 path.
