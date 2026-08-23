# M2-T13 - Learner-facing evaluation and feedback

## Scope delivered

M2-T13 adds a learner-authorized Java read model and React presentation for the immutable
assessment-evaluation decisions introduced by M2-T12. It does not start M2-T14 orchestration,
modify authoritative evidence or mastery, or widen the frozen AI proposal contracts.

`GET /api/v1/me/assessment-evaluations/latest-feedback` returns the latest decision belonging to
the authenticated learner. The repository derives ownership from:

`JWT subject -> core.learner -> grounding_retrieval_record -> assessment_evaluation_decision`

The query is ordered, limited to one row, and has no caller-supplied learner identifier. A learner
without an owned decision receives the same `UNAVAILABLE` state whether no evaluation exists or a
different learner owns one. Additive migration `V032__assessment_feedback_read_index.sql` indexes
the context/latest-decision access path without changing data or breaking the previous image.

## Payload and authority boundary

Only an `ACCEPTED` M2-T12 decision is presented as `EVALUATED`. Its minimized payload contains:

- answer and rubric versions;
- the approved overall feedback;
- bounded rubric dimension, score, maximum and approved dimension feedback;
- a deterministic next-learning rationale derived from the lowest normalized rubric result; and
- the evaluation time.

Rejected and manual-review rows return only their learner-safe state and a null approved-feedback
object. Unknown or structurally invalid stored outcomes fail closed as `UNAVAILABLE`. The endpoint
never serializes proposal/request/run identifiers, evidence identifiers, confidence, policy or
parser diagnostics, correlation metadata, raw prompts, hidden reasoning, provider data or secrets.
Responses use `Cache-Control: no-store`.

The presentation service is read-only and has no dependency on evidence, mastery, progression or
agent execution writers. It synthesizes learner guidance deterministically from already-approved
rubric scores and cannot convert a rejected proposal into learner-visible content.

## React behavior

The dashboard now includes a focused evaluation-feedback panel with explicit states for:

- pending asynchronous reads;
- evaluated and approved feedback;
- rejected evaluation;
- manual review;
- unavailable feedback; and
- transient API failure.

Refresh is a repeatable, side-effect-free GET. A new refresh aborts the preceding browser request,
and unmount aborts in-flight work, preventing stale completion from replacing newer state. The panel
uses semantic headings, a captioned rubric table, row/column headers, a polite live region,
`aria-busy`, a status announcement and a labelled keyboard-operable refresh button. React renders
feedback as text; no raw HTML injection is used.

## Qualification mapping

- H01 pending feedback UI: an unresolved API read announces the pending state and disables refresh.
- H02 approved feedback display: accepted overall feedback, rubric result and next rationale render.
- H03 rejected/manual-review: candidate content is absent from both API payload and UI.
- H04 authorization: learner role and authenticated-subject scoping are enforced server-side.
- H05 no raw prompt/hidden reasoning: serialized-response tests assert every forbidden internal
  field is absent, and rejected candidate content is perturbed to prove it remains undisclosed.
- H06 refresh/retry safe: UI failure recovery repeats only the read and cancels stale requests.

## Production-grade review

The implementation follows the repository's React/Java production standards: transport, service
and repository responsibilities are separated; the read is bounded; immutable records carry the
projection; stored accepted content is revalidated before presentation; server authorization is
authoritative; loading, empty, rejection, retry and failure states are explicit; sensitive state is
not persisted in the browser; and negative, authorization, data-minimization and accessibility
behavior is automated.

## Verification evidence

Local qualification on 2026-08-23:

- focused Java assessment-feedback service, repository and API contract tests: `BUILD SUCCESSFUL`;
- uncached serialized Java `clean check`, including architecture, governance and integration tasks:
  `BUILD SUCCESSFUL` with all 11 tasks executed in 1m25s;
- real PostgreSQL 18.1 migration and assessment decision/read tests: `BUILD SUCCESSFUL`;
- migration compatibility: all 32 migrations rollback-safe;
- workflow trust policy: all eight workflows valid and SHA-pinned;
- React dependency audit: zero vulnerabilities at the configured high-severity gate;
- React lint: clean;
- React unit/component suite: 46 passed with 88.08% statement coverage;
- React production TypeScript/Vite build: successful.

Full Java, architecture, governance, integration and CI evidence is recorded on the M2-T13 pull
request after the complete branch qualification run.
