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

## Code-review remediation — 2026-08-23

**Remediation status: ACCEPTED.** Findings 1–3 are fixed and verified. Finding 4 is recorded as
separate security debt and no rate-limiter code changed. M2-T10 was not started.

### Finding disposition

| Finding | Fix | Automated evidence |
| --- | --- | --- |
| 1 — malformed proposal had no audit row | `ProposalGateDecisionPort` now has a dedicated `PreParseRejection` contract made only from runtime/envelope metadata. `DiagnosticAssessmentService` persists it before returning `PROPOSAL_INVALID`; it never fabricates a `ProposalGroundingRequest`. V030 adds bounded `parser_reason_code` while the public reason remains `PROPOSAL_INVALID`. The existing `(proposal_id, policy_version)` uniqueness makes replay deterministic. | Service tests prove rejected outcome, stable parser/public reasons, all correlation IDs, and stable replay identity. PostgreSQL integration proves two appends produce one immutable row and that evidence/mastery row counts do not change. |
| 2 — skill validation failed open | Skill-universe extraction now accepts only authoritative SKILL_GRAPH/MASTERY skill facts. An empty universe adds stable `SKILL_CONTEXT_MISSING`; diagnoses and recommendations therefore cannot pass membership validation when the context cannot establish a skill universe. | Gate tests cover known/unknown skills, remove/restore perturbation, and recommendations. Removing the guard made the focused perturbation test fail. |
| 3 — STRONG counted unrelated facts | `countDistinctApplicableLearnerEvidence` counts distinct IDs only when authoritative and sourced from LEARNER_EVIDENCE or MASTERY. Explicit skill metadata must match the diagnosed skill. | Tests prove policy facts do not count, two permitted facts do count, other-skill facts do not count when linkage is available, and duplicate IDs count once. Broadening the source filter made the policy-fact protection test fail. |
| 4 — rate limiter | Not changed in T09. Recorded as **TD-M2-SEC-01 — Rate-limit trust boundary and bounded state** in `docs/release/mvp2-technical-debt.md`. | Disposition verified by an empty diff for `RateLimitFilter` and `TokenBucketRateLimiter`. Status remains **open**. |

### Verification results

| Command | Result |
| --- | --- |
| `.\gradlew.bat :learning-platform:test --tests '*DiagnosticAssessmentServiceTests' --tests '*DiagnosticAssessmentProposalGateTests' --rerun-tasks` | PASS — 34 tests, 0 failures |
| `.\gradlew.bat :learning-platform:check --rerun-tasks` | PASS — unit 528 (115 skipped), governance 141 (6 skipped), architecture 33, integration 116 (109 environment-gated skips); 0 failures |
| isolated PostgreSQL: `.\gradlew.bat :learning-platform:integrationTest --tests '*GroundingPersistenceIntegrationTests' --rerun-tasks` | PASS — 1 test, 0 failures, 0 skipped; PostgreSQL 18 temporary database removed after the run |
| targeted Python diagnostic, transport, MVP-1 verdict, and secret-hygiene suites | PASS — 79 tests, 0 failures; credential variable removed only from the child test process so no-credential tests exercise their intended state |
| `scripts/ci/check-contract-compatibility.py` | PASS — backward compatible with frozen v1 |
| `scripts/ci/generate-contract-models.py --check` | PASS — committed models match |
| OpenAPI 3.1 validator | PASS |

The forced `check` run includes `ProposalGroundingGateTests`, MVP-1
`DiagnosticProposalGateTests`, diagnostic API/persistence suites, release governance, architecture,
and Java secret-hygiene/security tests. The MVP-1 Java gate and Python verdict validator are
unchanged. `GroundedContext` and the OpenAPI transport are unchanged.

### Perturbation proof

Each production guard was temporarily removed, its focused test was run with `--rerun-tasks`, and
the production guard was restored before the final green run:

| Removed guard | Expected failing proof |
| --- | --- |
| malformed pre-parse audit append | `aPayloadThatCannotBeReadAsTheContractIsRejectedRatherThanThrown` failed at the persisted-rejection assertion |
| `SKILL_CONTEXT_MISSING` fail-closed addition | `removingAllAuthoritativeSkillFactsFailsClosedAndRestoringThemResumesNormalBehavior` failed |
| LEARNER_EVIDENCE/MASTERY source restriction | `strongDoesNotCountUnrelatedAuthoritativePolicyFacts` failed because the required insufficiency reason disappeared |

### Authority and remaining limitations

The gate remains deterministic and side-effect free. The service writes only the immutable decision
audit; it writes no mastery, progression, or evidence-ledger state. PostgreSQL proof compares
authoritative row counts before and after a malformed decision.

Current scalar LEARNER_EVIDENCE and MASTERY transport rows do not carry a skill code, so those facts
remain applicable when no deterministic skill linkage is present. When an item explicitly carries
`*_SKILL_CODE` metadata, mismatch is rejected from STRONG counting. Adding skill linkage to the
transport would be an additive future grounding enhancement, not a prerequisite fabricated inside
this remediation.
