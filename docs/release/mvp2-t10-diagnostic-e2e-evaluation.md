# MVP-2 T10 — Diagnostic E2E and Evaluation Evidence

Status: implemented. This task is a release-blocking verification layer; it does not introduce an
authoritative diagnosis store or modify mastery, progression, or evidence.

## Release thresholds

The versioned golden dataset covers weak, strong, inconsistent, and cold-start learners. The
provider-independent scorer requires:

| Dimension | Threshold |
| --- | ---: |
| Contract/schema validity | 100% |
| Claims citing context evidence | 100% |
| Semantic stability across repeated runs | 100% |
| Expected classifications and recommendations | 90% |

Semantic comparison includes only `(skillCode, classification)` and recommended skill codes.
Provider wording, reason prose, model identity, and generated correlation identifiers are excluded.
An absent observation is rejected rather than reported as a passing score.

## Qualification matrix

| Scenario | Automated proof |
| --- | --- |
| E01 weak-skill happy path | Agent contract tests and weak golden case |
| E02 strong/stable learner | Repeated strong golden case with changed reason wording |
| E03 one-variable perturbation | Only the changed classification/recommendation may move |
| E04 malformed provider output | Agent validation returns no diagnosis |
| E05 unsupported evidence | Validator/gate tests and evaluator threshold failure |
| E06 low confidence | Spring proposal-gate policy tests |
| E07 timeout/retry/failure | Gateway retry tests; diagnostic timeout records failed execution and no decision |
| E08 duplicate proposal/request | Stable decision identity and pre-dispatch execution commission |
| E09 deterministic business rejection | Spring gate rejects while leaving authoritative state unchanged |
| E10 trace reconstruction | PostgreSQL joins context, execution, and decision by request/run/context IDs |

## Operational invariants

- Spring resolves the authenticated learner and builds the grounded context.
- A request is commissioned before dispatch, preventing duplicate logical execution.
- Successful calls persist provider/model/route provenance and proposal digest.
- Transport failures persist a normalized error code and create no proposal decision.
- Returned malformed proposals are successful executions but rejected business decisions.
- `interactionId`, `traceId`, `requestId`, `agentRunId`, and `contextId` remain queryable as one
  chain in PostgreSQL.
- Diagnostic proposals remain non-authoritative and cannot write evidence or mastery state.

The deterministic fake-provider suite is the CI regression gate. Selective live-provider runs may
supplement release-candidate evaluation, but cannot replace deterministic malformed-output,
timeout, stability, and perturbation coverage.
