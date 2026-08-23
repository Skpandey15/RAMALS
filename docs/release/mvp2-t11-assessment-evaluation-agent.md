# MVP-2 T11 — Assessment Evaluation Agent

Status: implemented. This slice stops at a non-authoritative proposal. The Spring-owned
EvaluationProposalGate, decision persistence, manual-review policy, and authoritative evidence or
score effects belong to M2-T12.

## Deterministic-first boundary

The MVP-2 operation is separate from the MVP-1 formative-assistance endpoint. Its request contract
admits only `FREE_TEXT`, `DESIGN`, and `REASONING`. MCQ, true/false, numeric, exact-match, and
executable response types cannot be represented on this endpoint and remain on deterministic
scorers.

Spring supplies a bounded evaluation context containing:

- the exact answer and rubric versions;
- a stable answer evidence ID;
- approved rubric dimensions, criteria, maxima, and evidence IDs; and
- a versioned `GroundedContext` containing the authoritative assessment facts.

Before model dispatch, the AI plane verifies that the answer and every rubric dimension resolve to
authoritative `ASSESSMENT` facts in that context. A stale, incomplete, unauthorized, or unbound
context is rejected without a provider call.

## Proposal contract and validation

The agent returns the frozen `AssessmentEvaluationProposal` shape with runtime-owned proposal,
request, run, answer-version, and rubric-version identities. Local validation requires:

- every configured rubric dimension exactly once and no invented dimensions;
- configured maximum scores copied exactly and proposed scores within bounds;
- each dimension to cite both the answer and its approved rubric fact;
- feedback evidence to cite the answer and all evidence IDs to exist in the supplied context;
- bounded feedback and confidence; and
- no claim that a score is final, official, committed, mastery, progression, pass, or fail.

The response envelope is always `NON_AUTHORITATIVE`. Invalid model output is returned internally as
an invalid envelope and normalized by the HTTP boundary to an unprocessable proposal. Provider
timeouts and failures yield no proposal. The agent imports no database or domain-write capability.

## Qualification evidence

| Scenario | T11 proof |
| --- | --- |
| F01 deterministic types bypass AI | Closed response-type enum and pre-dispatch transport test |
| F02 free-text rubric evaluation | Structured happy path validated against the frozen JSON Schema |
| F03 invented dimension | Local semantic validation refuses the proposal |
| F04 out-of-range score | Local bounds validation refuses the proposal |
| F05 unsupported feedback/evidence | Context-subset and required-citation validation |
| F07 provider failure | Normalized gateway failure produces no proposal |
| F09 answer version linkage | Runtime stamps the exact supplied answer and rubric versions |

F06 conflict policy, F08 authoritative replay effects, and F10 evaluation-to-mastery effects remain
T12/T14 concerns because implementing them here would give the agent or transport premature domain
authority.
