# M2-T09 - DiagnosticAssessmentProposalGate and the grounded transport

- **Status:** Implemented
- **Branch:** `feat/m2-t09-diagnostic-assessment-gate` (base `e11421f`)
- **Architecture:** M2-ADR-001, M2-ADR-005, M2-ADR-006, M2-ADR-007, M2-ADR-012, M2-ADR-015
- **Invariant:** Agents recommend; deterministic Spring services decide.

## Flow

```
authoritative learner state  (learner resolved from the OIDC subject, never caller-supplied)
  -> GroundingRetrievalService.retrieve(subject, curriculumVersionId, REQUIRED_SOURCES)
GroundedContext v1           (bounded, versioned, recorded in ledger.grounding_retrieval_record)
  -> POST /internal/v1/diagnostic-assessment/propose   [new, additive]
       DiagnosticAssessmentRequest { contractVersion, interactionId, requestId,
                                     constraints, groundedContext }
  -> Python: fail-closed GroundedContext.model_validate + require_grounding
  -> DiagnosticAssessmentAgent (M2-T08)
DiagnosticAssessmentProposal -> AIProposalEnvelope   (provenance v2, NON_AUTHORITATIVE)
  -> Java DiagnosticAssessmentProposal.parse(...)     (contextId bound by the runtime)
  -> DiagnosticAssessmentProposalGate
  -> ledger.proposal_gate_decision                    ACCEPT / REJECT + reason codes
```

## Request-contract decision

**A new operation, not a new field on `AIRequestEnvelope`.**

The grounded context is mandatory for this operation and meaningless for the five MVP-1 operations.
Adding an optional `groundedContext` to the shared envelope would have left every existing operation
carrying a field whose requiredness no schema states — precisely the ambiguous optional-field
semantic the task warns against. A caller could not tell from the contract whether omitting it was
legal, and neither could a reviewer.

The additive path also keeps the frozen v1 baseline intact. `check-contract-compatibility.py` treats
new endpoints and new schemas as compatible and reports the contract still backward compatible;
`AIRequestEnvelope` is byte-identical, and a test asserts it never gained the field.

## Exact contract changes

| Kind | Name | Note |
| --- | --- | --- |
| path | `POST /internal/v1/diagnostic-assessment/propose` | new |
| schema | `DiagnosticAssessmentRequest` | new; `additionalProperties: false` |
| schema | `GroundedContextEnvelope` | new; `contractVersion` enum admits only `1.0` |
| schema | `GroundedContextItemEnvelope` | new; scalar values only, bounded |
| migration | `V029__diagnostic_assessment_decision_correlation.sql` | adds nullable `interaction_id`, `trace_id` |

Nothing was removed, no property became required, no enum value was dropped, no limit tightened.
Python models were regenerated from the contract: 85 insertions, 1 deletion (an import line).

## Transport design

The context crosses as a typed object and is **re-validated on arrival** rather than trusted because
it came over a typed boundary. The generated model proves the shape; the hand-written
`GroundedContext` proves freshness, size and sensitive-field rules, and those are the ones that fail
closed. `require_grounding` runs at the boundary before dispatch, so a context missing a required
source costs no provider call.

The AI plane cannot widen what it was given. There is no learner identifier in the request beyond the
opaque `learnerRef` inside the context, no capability string, and no database reachable from the
plane. A test asserts the request has exactly five fields and that `learner` is not one of them.

## Gate rules

`DiagnosticAssessmentProposalGate` composes `ProposalGroundingGate` rather than reimplementing it.

| Requirement | Owned by | Reason codes |
| --- | --- | --- |
| A contract/schema validity | parse + grounding gate | `PROPOSAL_INVALID`, `PROPOSAL_VERSION_UNSUPPORTED` |
| B evidence membership | grounding gate (T07) | `EVIDENCE_REFERENCE_UNKNOWN`, `EVIDENCE_REFERENCE_NON_AUTHORITATIVE`, `CLAIM_UNSUPPORTED` |
| C skill membership | **T09** | `SKILL_NOT_IN_CONTEXT` |
| D classification enum | parse | `PROPOSAL_CLASSIFICATION_UNKNOWN` → `PROPOSAL_INVALID` |
| E semantic validation | **T09** | `CLASSIFICATION_CONFLICT`, `EVIDENCE_INSUFFICIENT_FOR_STRONG`, `INSUFFICIENT_EVIDENCE_OVERCONFIDENT` |
| F confidence policy | grounding gate (T07) | `CONFIDENCE_BELOW_POLICY` |
| G recommendation validation | **T09** | `RECOMMENDATION_INVALID` |
| H freshness / snapshot | grounding gate (T07) | `CONTEXT_ID_MISMATCH`, `GROUNDING_INVALID` |
| I idempotency | `UNIQUE (proposal_id, policy_version)` | — |

Three semantic rules are new and deliberately deterministic:

* **STRONG needs two authoritative references.** One observation is consistent with a lucky guess.
  WEAK is deliberately not held to the same bar — proposing more practice on thinner evidence is the
  safe direction to be wrong in.
* **A repeated skill is refused, agreeing or not.** Normalising a duplicate would mean the gate
  choosing which reading of a learner is the real one, which is the authority a gate must not take.
* **INSUFFICIENT_EVIDENCE above 0.90 confidence is refused.** "There is not enough evidence to say"
  and "I am almost certain" are not both true.

Skill and recommendation membership bind only when the context names skills. An empty set means the
context cannot answer the question, and rejecting every classification on that basis would fail
closed for a reason unrelated to the proposal; evidence membership still binds and is the stronger
rule.

## Authoritative effect

**None beyond the decision.** No mastery write, no progression change, no evidence-ledger mutation.
An accepted proposal means the reading is grounded, consistent and within policy — not that the
platform adopted it as fact. No advisory-persistence table was invented; what consumes an accepted
diagnosis is M2-T10 and M2-T14.

## Decision-record linkage

`ledger.proposal_gate_decision` now records, per decision: `proposal_id`, `request_id`,
`agent_run_id`, `context_id` (FK to the retrieval record), `proposal_type`, `accepted`,
`reason_codes`, `referenced_evidence_ids`, `policy_version`, `decided_at`, and — new in V029 —
`interaction_id` and `trace_id`. `ai_execution` joins on `request_id`.

Both new columns are nullable. V028 rows predate them and are still valid decisions; a NOT NULL
column would have meant inventing correlation identifiers for history that never carried them.

**Transport failure is not business rejection.** A call that never produced a proposal raises out of
the client and writes no decision row. A proposal that arrived and failed the rules is a successful
system outcome: a row, a reason code, and a returned decision. A payload that cannot be read as the
contract is also a rejection, not a discarded exception — something was returned and the record
should say what happened to it.

## Tests

| Suite | Count |
| --- | --- |
| `DiagnosticAssessmentProposalGateTests` | 18 |
| `DiagnosticAssessmentServiceTests` | 9 |
| `test_diagnostic_assessment_transport.py` | 11 |
| Java total | **521**, 0 failures |
| Python total | **603**, 95.41% coverage |

Scenario coverage: **E05** unsupported claim and summary-only citation · **E06** below threshold and
exactly at threshold · **E08** replay after acceptance and after rejection · **E09** STRONG without
sufficient evidence, INSUFFICIENT_EVIDENCE asserted with near-certainty · fabricated evidence id ·
unknown skill code · conflicting duplicate classifications · stale context · context-id mismatch ·
unsupported contract version · all four classifications accepted on valid input · invalid
recommended skill · provider timeout · malformed and empty payloads · unknown classification ·
correlation continuity.

## Perturbation proof

Each control was removed and the suite re-run:

| Perturbation | Failing test |
| --- | --- |
| skill-membership check removed | `anUnknownSkillCodeIsRejectedWhenTheContextNamesSkills` |
| STRONG evidence minimum removed | `e09_strongWithoutSufficientEvidenceIsALegitimateBusinessRejection`, `e08_replayingARejectedProposalStaysRejectedAndRecordsTheSameReasons` |
| duplicate-classification check removed | `twoClassificationsForOneSkillAreRejectedRatherThanNormalised` |

There is also an in-test perturbation that flips the decision and flips it back:
`perturbingTheEvidenceIdentifierFlipsTheDecisionAndRestoringItFlipsItBack` — accepted on `e-1`,
rejected on `e-999`, accepted again on `e-1`.

## Regression proof

Unchanged, verified by `git diff` producing no output for each: MVP-1 `DiagnosticProposalGate`,
`AdaptationProposalGate`, the MVP-1 Python diagnostic agent and its verdict-language validator, and
the adaptation agent. `AIRequestEnvelope` did not gain a field, all five MVP-1 operations remain, and
the OpenAPI contract is still backward compatible with the frozen v1 baseline.

`test_the_shipped_app_serves_every_agent_route` failed when the route was added and was updated
deliberately: that test exists so a new agent route cannot appear unnoticed, and it worked.

## Security

* The learner is resolved inside retrieval from the authenticated subject. No caller-supplied learner
  identifier is accepted anywhere on this path, so constructing another learner's context is
  unreachable rather than merely checked.
* The request has no field with which to ask for more data, and the plane holds no database handle.
* Retrieved content reaches the prompt inside a labelled data block; the gate's rules are applied in
  Spring and cannot be altered by prompt text.
* No provider secret appears in the context, the proposal or the decision record — none of those
  structures has a field that could carry one.
* Unknown contract versions fail closed on both sides: the transported context enum admits only
  `1.0`, and the gate refuses an unrecognised proposal version before any other rule.

## Known limitations

* **No durable dispatch on this path yet.** `DiagnosticAssessmentService.assess` is synchronous. The
  outbox and dispatcher from T02/T03 exist and the natural next step is an `AgentWorkProcessor` for
  this agent type, but wiring one is orchestration work and belongs to M2-T14.
* **Idempotency is proven at the identity and constraint level, not end to end in a database test.**
  The service offers a stable proposal identity on replay and the table has
  `UNIQUE (proposal_id, policy_version)`; a deployed replay test belongs with M2-T10.
* **`REQUIRED_SOURCES` is stated in three places** — the Java service, the grounding policy and the
  Python agent. They agree today and the gate rejects a context that fails the policy regardless, but
  the duplication is real and a single source would be better.
* **No advisory persistence for an accepted diagnosis.** Deliberate, per the task: the decision is
  recorded, and consumption is deferred.
