# M1-ADR-010: AI assessment evaluation is FORMATIVE_ONLY in MVP-1

- **Status:** Accepted
- **Date:** 2026-08-16
- **Relates to:** MVP-1 Canonical Package v1.3 Doc 03 §5, M1-T10, M1-T16
- **Required before:** M1-T10

## Context

The MVP-1 architecture is absolute everywhere else: agent output is a proposal, and Spring is the
only component that may apply an authoritative learner-state change.

One endpoint was not. Earlier drafts described `POST /internal/v1/assessment/evaluate` as
"non-authoritative *unless deterministic policy explicitly allows*", and the `VERIFIED` trust state
as usable "*only where policy allows*". Neither document said which evaluation types qualified, what
validation would promote them, or who decided.

An unbounded exception in the one place where a model could touch scored evidence is worse than no
exception, because it reads as permission while specifying nothing. It would also break the property
MVP-0 exists to provide: that a mastery decision can be reconstructed deterministically from stored
evidence.

## Decision

In MVP-1, `POST /internal/v1/assessment/evaluate` is **FORMATIVE_ONLY**.

It **may** return formative feedback, classifications, rubric suggestions and reviewer assistance.

It **must not**:

- create, or cause the creation of, a row in `ledger.evidence`;
- determine or persist an authoritative assessment score;
- modify assessment attempt completion based on model judgment;
- affect `mastery_score`, `mastery_status`, `evidence_confidence`, recommendation or progression.

The `VERIFIED_CONTENT` trust state applies to *content* that has passed approved validation or
review. It does not confer evaluation authority: content may enter the deterministic scoring path,
but an AI evaluation never becomes the score.

Any future authoritative AI-assisted scoring requires a new ADR in a later MVP, with an explicit
item-type allowlist and deterministic validation controls.

## Consequences

- The proposal-only invariant holds without exception across every MVP-1 endpoint.
- Mastery remains reproducible from evidence alone, so the MVP-0 deterministic control stays a valid
  comparison baseline.
- Formative AI feedback is still available to learners, which is most of the pedagogical value.
- M1-T16's security suite includes FORMATIVE_ONLY bypass attempts; M1-T10 carries a repository-level
  negative test that evaluation cannot create evidence.

## Verification

- M1-T10 required tests: evaluation cannot create `ledger.evidence`; attempt score is immutable
  under AI evaluation; mastery and progression are unchanged.
- M1-T16 required tests: FORMATIVE_ONLY bypass corpus.
- The `ramals_ai_runtime` database role holds no privilege on `ledger`, so the strongest form of
  this rule is already enforced at the privilege layer by `V015` and proven by
  `AiRuntimeBoundaryIntegrationTests`.
