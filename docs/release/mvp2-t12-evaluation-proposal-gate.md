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
confidence, deterministic comparison and correlation identifiers. `trace_id` is nullable and only
contains an actual distributed trace identifier; the service never fabricates it from an
`interaction_id`. The row excludes answer text, raw prompts, provider secrets and hidden reasoning.

`request_id` is unique. A normalized SHA-256 decision digest makes identical delivery an idempotent
no-op and turns reuse of the same request for different business content into an explicit replay
conflict. Attempt-specific `trace_id` and correlation-only `interaction_id` are deliberately outside
the semantic digest. Reuse of `(proposal_id, policy_version)` for a different request is translated
to `AssessmentEvaluationReplayConflictException`, rather than leaking a database constraint
exception. The database trigger rejects update/delete mutations.

## Independent review remediation

The independent M2-T12 review was remediated before merge:

- **P1-1 replay digest:** trace and interaction identifiers were removed from the semantic digest.
  The real-PostgreSQL replay test changes each independently, proves one retained row, and then
  changes feedback to prove business-content drift still raises a replay conflict.
- **P1-2 rejected confidence audit:** the gate normalizes confidence outside `[0,1]` to `NULL` in a
  rejected decision. Service-to-PostgreSQL tests cover `-1`, `1.5`, and a 1,001-digit integer and
  prove durable `REJECTED` rows with `EVALUATION_CONFIDENCE_INVALID` rather than rollback.
- **P1-3 frozen v1 compatibility:** v1 evidence arrays remain optional on the wire, exactly as in
  the frozen schema. Python and Java wire models accept their absence; the deterministic gate
  applies T12's stronger non-empty grounding requirement. Shared positive and duplicate-evidence
  negative fixtures are exercised by JSON Schema, the Python wire model, and the Java parser.
- **P2-4 proposal/policy reuse:** an insert conflict on the second unique identity is detected and
  reported through the domain replay exception with safe diagnostics.
- **P2-5 fail-closed direct use:** null answer/rubric/source-version state is covered by direct gate
  tests and produces `REJECTED` without an exception. Invalid proposal dimensions are normalized
  safely for audit construction.
- **P2-6 numeric resource bounds:** Java rejects non-number input and bounds decimal lexical length,
  precision, scale, integer digits, and exponent before `BigDecimal` construction, using stable
  `EVALUATION_*_INVALID` parser codes.
- **Provenance:** missing tracing context persists as `NULL`. Because V031 exists only on this
  unmerged task branch and has never shipped, its create-table definition was corrected in place;
  no mutation of an applied migration or unnecessary follow-up migration was introduced.

Verification evidence for the remediation consists of focused T12 Java tests, Python assessment
evaluation tests, shared schema/parser fixtures, uncached full Java checks, generated-contract drift
and backward-compatibility checks, migration compatibility, architecture guardrails, secret hygiene,
and the PostgreSQL-backed CI suite. The final reviewed commit and CI run are reported on PR #127.

Local qualification on 2026-08-23 produced the following evidence:

- focused T12, migration-contract, MVP-2 contract, and architecture tests: `BUILD SUCCESSFUL`;
- uncached serialized `clean check`: `BUILD SUCCESSFUL` with all 11 tasks executed in 2m16s;
- Python lint and format: 109 files clean; strict mypy: 109 source files clean;
- complete Python suite: 636 passed with 94.95% coverage;
- cross-language contract suite: 17 passed;
- generated-model drift: committed models match generated output;
- contract compatibility: backward compatible with the v1 baseline;
- migration compatibility: all 31 migrations rollback-safe;
- workflow trust policy: all eight workflows valid and SHA-pinned;
- Gitleaks 8.30.1 full-history scan: 232 commits and 4.58 MB scanned, no leaks found; and
- disposable PostgreSQL 18.1 execution of `GroundingPersistenceIntegrationTests`:
  `BUILD SUCCESSFUL`, followed by verified removal of the test container.

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
