# M2-T12 - EvaluationProposalGate

## Scope delivered

M2-T12 adds the Spring-owned acceptance boundary for non-authoritative assessment-evaluation
proposals. It does not add learner-facing presentation (M2-T13) or controlled multi-agent workflow
composition (M2-T14).

The implementation validates:

- exact request, proposal, agent-run, grounded-context, answer-version and rubric-version identity;
- the configured rubric dimension set, unique dimension identifiers and approved maxima;
- every proposed score against the configured `[0, maxScore]` range;
- authoritative answer/rubric facts and evidence references for each dimension and feedback;
- proposal-only language policy and the versioned evaluation request policy;
- the deterministic minimum-confidence policy; and
- an explicit deterministic-core comparison supplied independently of model prose.

## Decision semantics

The gate produces exactly one of three outcomes:

- `ACCEPTED`: all hard rules pass, confidence meets policy and no deterministic conflict exists;
- `REJECTED`: contract, identity, grounding, rubric, range, evidence or safety policy fails; or
- `MANUAL_REVIEW`: the proposal is otherwise valid but confidence is below policy or an independent
  deterministic check disagrees.

Only `ACCEPTED` reports `allowsAuthoritativeEffect=true`. The M2-T12 service has no dependency on
the assessment response repository, evidence service/repository or mastery service/repository. An
ArchUnit rule makes that absence executable, so invalid and manual-review results cannot write
learner state through this component.

## Durable trace and replay

Migration `V031__assessment_evaluation_decision.sql` creates the append-only
`ledger.assessment_evaluation_decision` table. Each row links:

`answer evidence/version -> grounded context -> gate decision -> successful ASSESSMENT ai_execution`

The row retains normalized dimension results, approved feedback candidate, stable reason codes,
confidence, deterministic comparison and correlation identifiers. It excludes answer text, raw
prompts, provider secrets and hidden reasoning.

`request_id` is unique. A normalized SHA-256 decision digest makes identical delivery an idempotent
no-op and turns reuse of the same request for different content into an explicit replay conflict.
The database trigger rejects update/delete mutations.

## Qualification mapping

- F03: invented/duplicate rubric dimensions are rejected.
- F04: out-of-range scores and changed maxima are rejected.
- F05: absent or non-authoritative evidence is rejected.
- F06: deterministic disagreement is retained and routes to manual review.
- F08: pure-gate determinism plus database replay collapse/conflict detection.
- F09: exact answer and rubric versions are grounded and persisted.
- F10 boundary: the decision component cannot directly write evidence or mastery; authoritative
  orchestration remains a later task.

Automated verification includes Java unit/service tests, migration-contract tests, ArchUnit
authority-boundary tests, migration rollback-compatibility checks, and an environment-gated real
PostgreSQL test for foreign-key traceability, append-only enforcement and replay behavior.
