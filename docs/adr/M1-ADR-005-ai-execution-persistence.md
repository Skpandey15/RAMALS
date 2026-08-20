# M1-ADR-005: Persist AI execution provenance

- **Status:** Accepted
- **Relates to:** M1-T13, M1-ADR-001, M1-ADR-003, M1-ADR-008

## M1-T13A amendment: pre-dispatch commissioning

The original terminal-only row is not sufficient to prevent a provider dispatch during a retry:
the first caller could reach the AI plane before its `ai_execution` row existed. M1-T13A therefore
adds an append-only `core.ai_execution_event` stream. A unique `STARTED` event for each
`requestId` + request digest is committed in an independent transaction before the AI call.

Only the caller that inserts that event may dispatch. A same-digest retry reuses the existing
terminal `core.ai_execution` outcome when present, or receives a deterministic in-progress result
while the original execution is unresolved. A different digest is an idempotency conflict. Terminal
events are appended alongside the existing immutable terminal row; neither table is updated or
deleted.

This preserves the non-authoritative AI boundary and privacy decision: the event stream stores only
bounded identifiers, lifecycle metadata, timestamps, error codes, and SHA-256 digests. It never
stores prompts, learner context, credentials, or model output. The tradeoff is that a crash after
provider dispatch and before terminal recording leaves a durable `STARTED` event and blocks an
automatic duplicate dispatch; recovery must be an explicit, separately governed retry policy.

## Context

The AI plane is non-authoritative, but every commissioned execution still needs durable evidence of
what was requested, which governed route answered, how it ended, and which correlation identifiers
were involved. Logs alone are insufficient for retries, incident investigation, and release-gate
measurements. Persisting prompts or model output would increase privacy and storage risk and would
duplicate proposal-specific persistence owned by other workflows.

## Decision

Spring owns an append-only `core.ai_execution` table with one row per `requestId`. The request ID is
the retry/idempotency key for an execution; a retry with the same request ID and different request
digest is rejected. The record stores bounded metadata and SHA-256 digests for the request and
proposal, never raw prompts, learner context, provider credentials, or model output.

Execution records contain agent/contract/version/route metadata, interaction and request IDs,
status, error code, token/cost/latency usage, and start/completion timestamps. Successful and failed
executions are both durable. Recording uses an independent transaction so it remains available
after a caller rollback; failure to record is an execution failure, not a successful untracked call.

The table is observational and non-authoritative. It cannot create evidence, mastery, approval, or
content state. Proposal and domain workflows remain responsible for their own deterministic
validation and authoritative writes.

## M1-T13 amendment: the AI plane holds no privilege here, and provenance expires

Two things were decided implicitly by "Spring owns an append-only `core.ai_execution` table" and are
now stated, because implicit decisions get "fixed".

**The AI plane receives no grant on these tables.** The MVP-1 master plan's M1-T13 line reads *"Grant
`ramals_ai_runtime` required DML only"*. That was written before this ADR, which decided Spring owns
the table; and the AI plane has no PostgreSQL driver at all, asserted by
`ramals-ai/tests/unit/test_no_database_access.py`. A grant would hand a credential to a process with
no way to use it, and it would be that process's first. The plan line is superseded.

Left implicit, the gap between the plan and the schema invites exactly the wrong correction — and
nothing would have failed, because `AiRuntimeBoundaryIntegrationTests` listed eight tables and not
these two. `V023` restates the revoke and `AiExecutionProvenanceIntegrationTests` asserts the role
holds nothing, alongside a converse test that Spring's role can still write.

**Provenance is retained for 400 days.** A full year of release evidence plus margin for an annual
review, which is what this data is for: reconstructing what an agent did, and at what cost, when a
release is questioned afterwards.

Retention collided with append-only enforcement, and the collision was real rather than theoretical:
`V021` and `V022` rejected every `DELETE` outright, so the policy could not have been executed at
all. It was found by writing the purge and watching it fail. The two are reconciled by separating
meanings of immutable — history must not be **rewritten** (no `UPDATE` ever, and no `DELETE` inside
the window, so a bad execution cannot be made to disappear the day after it happened), but it may
**expire**. The floor lives in the trigger, not in the caller, so passing a shorter window to
`core.purge_expired_ai_executions` deletes less rather than shortening the policy.

The purge is a function rather than a scheduled job because MVP-1 has no scheduler — Temporal is
deferred by the MVP-0 scope freeze. A function an operator can run and a test can exercise is honest;
a policy with no mechanism is a comment pretending to be a control.

**Redaction is structural.** There is nothing to remove because there is nowhere to put it: every
column is bounded metadata, and no `TEXT` column exists on either table. A redaction routine would be
the weaker control, running after the data was already written.

**Reconstruction is by correlation, not by a foreign key.** `ledger.decision_record` is append-only
and is written *before* the AI call, so a proposal column could never be filled in afterwards — it
would be permanently null and would read as "no AI was involved". Both tables carry `interaction_id`
from the same correlation contract, which is how a historical decision identifies the AI activity
that accompanied it.

## Alternatives rejected

- **Logs only:** not queryable or reliable enough for idempotency and release evidence.
- **Persist full prompts and outputs:** unnecessary for execution accounting and increases PII,
  secret, and retention exposure.
- **One execution row per network retry:** obscures caller idempotency and makes a single
  commissioned operation appear multiple times; provider retry details belong in bounded metadata.
- **Write inside the domain transaction:** couples a slow/non-authoritative call to an unrelated
  domain transaction and violates the existing AI boundary.

## Verification

- Migration constraints enforce non-empty IDs, valid status, bounded error metadata, and digest shape.
- A unique request ID and fingerprint conflict check prove retry safety.
- Repository tests cover success, failure, duplicate retry, digest conflict, and rollback isolation.
- Architecture tests verify the execution table remains under Spring/core ownership and that no raw
  prompt/output columns are introduced.
