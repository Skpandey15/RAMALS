# M1-ADR-007: Limited-Durable Human Approval Workflow

- **Status:** Accepted
- **Date:** 2026-08-18
- **Relates to:** MVP-1 Canonical Package v1.3 Doc 01 §5/§10, Doc 03 §4, Doc 07 §2–§3, M1-ADR-001, M1-ADR-002, M1-ADR-003, M1-ADR-006, M1-ADR-008, M1-ADR-010
- **Required before:** M1-T12

## Context

M1-T10 can produce assessment content that is useful only after a person has reviewed it. The
review cannot happen inside the AI request: M1-ADR-001 defines `LIMITED_DURABLE` as the class for
approval-only work and requires the HTTP response to return once `APPROVAL_REQUIRED` is durable.
Waiting for a reviewer while holding an HTTP request would turn a human queue into a distributed
timeout.

The repository already has two related but deliberately different concepts:

- M1-ADR-006 and V017 make generated assessment content `UNVERIFIED` on creation and allow only
  `VERIFIED_CONTENT` content into scored use.
- The contract contains `APPROVAL_REQUIRED`, but V017 does not use that value as an assessment
  item's `trust_state`. The existing trust-state constraint must not be weakened or overloaded.

The workflow therefore needs durable state, a reviewer boundary, retry-safe commands, concurrency
control, and a final check against current deterministic platform state. It must never turn an AI
proposal or a human approval into an AI-authored learner decision.

This ADR is a decision document only. It does not implement M1-T12 and does not change
`ramals-ai/src/ramals_ai/graph/limits.py` or the Python graph package.

## Decision

### Authority and eligible operations

MVP-1 has one operation that may enter the limited-durable approval workflow:

> An AI-generated assessment item or rubric that has passed the automated stages and is intended
> for a scored context, where human review is required by M1-ADR-006.

The approval request is a Spring platform record. The AI response remains a non-authoritative
proposal, and the content remains `UNVERIFIED` until the authoritative Spring promotion succeeds.
Tutor responses, diagnostic/adaptation suggestions, formative AI evaluation, curriculum facts,
learner mastery, progression, evidence, and recommendation decisions do not enter this workflow.
Adding another operation requires a new decision or an amendment to this ADR.

The authority chain is fixed:

```
AI proposal → optional human approval → deterministic revalidation → authoritative Spring operation
```

Human approval is evidence that an authorized human approved a particular immutable proposal
revision at a point in time. It does not promote the AI into an authoritative decision maker and it
does not authorize an AI component to write `core`, `ledger`, or `audit` state.

These distinctions are normative: `APPROVAL_REQUIRED` is a workflow state on the Spring approval
request, not a new value for V017's assessment-item `trust_state`; approval does not itself make an
AI proposal authoritative; and only the deterministic Spring operation may create the authoritative
`VERIFIED_CONTENT` result.

### State machine

The durable approval-request state is separate from assessment-item `trust_state`:

```
APPROVAL_REQUIRED ── approve + revalidate + execute ──> APPROVED
        │                         ├─ stale ──────────> SUPERSEDED
        │                         └─ policy/review refusal -> REJECTED
        ├─ reviewer rejects ──────────────────────────> REJECTED
        ├─ expiry check ──────────────────────────────> EXPIRED
        └─ owner/system cancellation ─────────────────> CANCELLED
```

Legal transitions are:

| From | Command/condition | To |
| --- | --- | --- |
| `APPROVAL_REQUIRED` | authorized approve, final deterministic revalidation passes, authoritative operation commits | `APPROVED` |
| `APPROVAL_REQUIRED` | authorized reject with a non-blank reason | `REJECTED` |
| `APPROVAL_REQUIRED` | expiry is reached and the request is observed or swept | `EXPIRED` |
| `APPROVAL_REQUIRED` | authorized owner/system cancellation before approval | `CANCELLED` |
| `APPROVAL_REQUIRED` | final revalidation finds the proposal or governing state stale | `SUPERSEDED` |
| any terminal state | repeated same command with the same idempotency key | no transition; return the original result |
| any terminal state | a different command, or a reused key with a different request fingerprint | no transition; return a conflict |

Terminal states are immutable. There is no approve-after-reject, reject-after-approve,
reopen, or direct transition to `APPROVED`. `APPROVED` means the authoritative operation committed;
it is not a promise to execute later.

### Persistence ownership and data model

Spring owns the workflow and PostgreSQL is the source of truth. `ramals-ai` has no database
credential for the platform and neither `limits.py` nor the Python graph runtime persists approval
state.

T12 will add a dedicated `core` approval-request table through a forward-only Flyway migration.
The table will contain, at minimum:

- a UUIDv7 request identifier and the target type/identifier;
- current workflow state, created/updated timestamps, and an expiry timestamp;
- an immutable proposal identity, contract version, agent/model/prompt provenance, and a content
  or proposal revision/digest;
- the exact proposal payload or an immutable versioned reference sufficient to reconstruct what was
  reviewed;
- the deterministic policy/engine versions and authoritative context snapshot used to create the
  request;
- the originating `interactionId`, request provenance, and creation idempotency key;
- approval/rejection/cancellation metadata: actor subject, timestamp, reason, and the resulting
  operation identifier where applicable.

The reviewed proposal and its provenance are immutable. A changed proposal creates a new versioned
request; it is never edited underneath a reviewer. Current workflow state may advance only through
the legal transitions above, guarded by database constraints and a compare-and-set update or row
lock. Approval history and privileged outcomes are append-only audit records in `audit`, following
V013; the workflow row is not a substitute for audit history.

The approval request is a coordination record, not learner evidence, mastery, progression, or a
decision record. Authoritative operation records remain in their existing domain tables and
immutable ledger tables.

### API semantics

T12 will expose the following Spring API surface, with representations and problem codes pinned by
its contract tests:

- `POST /api/v1/approval-requests` creates or returns the durable request for an eligible proposal;
- `GET /api/v1/approval-requests/{id}` reads its state and immutable reviewed revision;
- `POST /api/v1/approval-requests/{id}/approve` approves it;
- `POST /api/v1/approval-requests/{id}/reject` rejects it with a required reason; and
- `POST /api/v1/approval-requests/{id}/cancel` cancels a still-pending request for the authorized
  owner/system operation.

The create command is issued by the authenticated Spring platform flow after automated validation;
the AI service does not create approval rows directly. Reads return the current state and immutable
reviewed revision, never an editable AI payload.

Approval and rejection are commands, not toggles. `approve` performs final deterministic
revalidation and the permitted authoritative Spring operation in the same transaction. A stale,
invalid, expired, cancelled, or already-terminal request returns a stable problem code and does not
perform a partial write. Rejection requires a reason; the reason is retained and is not replaced by
later retries.

`LIMITED_DURABLE` means the create request returns after the approval request and its audit entry
are committed. It does not mean the HTTP request waits for a human, and it does not require a
long-lived transaction, WebSocket, queue consumer, or in-memory waiter.

### Authorization and reviewer identity

Approval and rejection require an authenticated human with `CONTENT_AUTHOR` or `ADMIN`, matching
the M1-ADR-006 content-promotion boundary. An `ADMIN` approval also requires the existing MFA
authorization policy. Learner tokens, AI workload identity, service accounts, unauthenticated
callers, and callers without the target permission are refused by Spring Security and method
security. Authorization is enforced at the controller/service boundary and is not inferred from a
request body field.

The reviewer subject is taken from the verified Spring security principal, never accepted from the
client. Every terminal review records reviewer subject, server timestamp, action, reason where
applicable, target and proposal revision. Self-approval rules, if required by a future content
governance policy, are an explicit policy decision; T12 must not silently infer them from the AI
provenance.

### Idempotency and concurrency

Creation, approve, reject, and cancel are consequential commands and require a valid
`Idempotency-Key`. The same key is scoped to the authenticated actor, operation, and target, and
is bound to a request fingerprint. Repeating the same command with the same fingerprint returns the
same durable result without repeating the authoritative operation. Reusing the key for a different
fingerprint returns a conflict.

Approve and reject race on the same pending row. PostgreSQL is the arbiter: T12 will use a row lock
or an atomic `WHERE state = 'APPROVAL_REQUIRED'` transition, and the losing transaction will observe
the committed terminal state. Exactly one operation can win; there is no last-writer-wins overwrite.
The state transition, final revalidation, authoritative write, idempotency result, and audit event
commit or roll back together as defined below.

### Staleness, expiry, and cancellation

An approval is valid only for the immutable proposal revision and the deterministic context captured
by the request. Immediately before authoritative execution, Spring re-runs the applicable
structural, content, policy, authorization, and current-state checks. If learner/mastery/policy or
other governing state has changed such that the operation is no longer valid, the request becomes
`SUPERSEDED`; the old approval cannot be reused. A new proposal and approval request are required.
Existing immutable evidence and historical records are not rewritten to make an old request fit.

Every request has an expiry determined by the versioned deterministic approval policy at creation;
the expiry instant is persisted with the request and is not extended by retries or reviewer activity.
The request may be marked `EXPIRED` lazily when read or commanded and may also be closed by a
bounded Spring maintenance task. Expiry is fail-closed: it cannot be approved or executed after the
expiry instant. MVP-1 does not introduce a workflow engine solely to deliver expiry notifications.

Cancellation is allowed only while `APPROVAL_REQUIRED`, by the authorized owning platform/admin
operation, and is terminal. Cancellation does not delete the proposal or audit history. A reviewer
cannot cancel another review merely by submitting an empty or alternate action.

### Crash, retry, restart, and transaction boundaries

There is no in-memory workflow state. A process restart resumes by reading pending approval requests
from PostgreSQL. A crash before a transaction commits leaves the request pending and leaves no
authoritative partial write. A crash after commit is safe to retry: the idempotency record and
terminal request state return the already-committed result without executing twice.

The boundaries are:

1. Proposal intake, immutable request snapshot, and creation audit are one short transaction.
2. Human waiting occurs with no open transaction.
3. Approve/reject/cancel obtains a short row lock, checks expiry and authorization, and writes its
   terminal state and audit event. Approve additionally performs deterministic revalidation and the
   authoritative operation before commit. No network/model call occurs inside this transaction.
4. If revalidation fails, the transaction records `SUPERSEDED` (or the applicable refusal state) and
   no authoritative operation is written.

This preserves M1-ADR-001's rule that no authoritative transaction is held across an AI call or a
human wait, while making the final state change atomic and restart-safe.

### Correlation and provenance

The originating `interactionId` is preserved across safe retries. Each HTTP attempt may have a new
`requestId` and `traceId`, while logs and audit records retain the interaction correlation. Approval
commands record their own request/trace metadata alongside the originating interaction and proposal
identity. The response exposes the standard Problem Details correlation fields on failures.

The durable record must make it possible to answer: which proposal revision was reviewed, which
agent/model/prompt produced it, which deterministic policy and engine versions were revalidated,
who acted, when, what changed state, and which authoritative operation resulted. No access tokens,
raw secrets, or unnecessary private learner history are persisted.

## Alternatives considered

**Keep a human inside the original HTTP request.** Rejected by M1-ADR-001: reviewer latency turns
an approval queue into request timeout and thread exhaustion.

**Let the AI approve its own proposal after validation.** Rejected by M1-ADR-006 and the authority
boundary: automated validation may reject, but it is not human approval and cannot create authority.

**Persist approval state in Python or in `limits.py`.** Rejected. Spring owns PostgreSQL, security,
authoritative policy, and audit; the AI runtime is explicitly denied platform database access by
V015.

**Use Temporal, Kafka, Redis, or another external workflow engine.** Rejected for MVP-1. A small
durable state machine with short Spring transactions is sufficient; introducing another durable
authority would expand failure, recovery, and deployment semantics before the approval invariant is
proven.

**Allow multi-agent or quorum approval.** Rejected for MVP-1. It adds identity, quorum, timeout, and
conflict semantics without improving the single human accountability requirement established by
M1-ADR-006.

**Edit the proposal or reopen a terminal request.** Rejected. Review must be over an immutable,
versioned revision, and a changed proposal requires a new request.

## Acceptance and implementation gate

This ADR is **Accepted** for M1-T12. Acceptance authorizes design and implementation work against
the decisions above; it does not itself implement T12. The release board remains the authoritative
task-governance record and must be updated separately before T12 is marked ready or started.

## Consequences

- The first durable implementation is a Spring/PostgreSQL approval queue, not a Python feature.
- V017's trust states remain intact: approval request `APPROVAL_REQUIRED` is workflow state;
  successful authoritative promotion remains `VERIFIED_CONTENT` with reviewer metadata.
- Reviewer throughput is an intentional MVP-1 constraint for scored generated content.
- Final deterministic revalidation may invalidate a human-approved request when the world changes;
  this is fail-closed and requires a new review.
- T12 needs a migration, repositories/services/controllers, authorization tests, concurrency and
  idempotency tests, audit tests, restart/recovery tests, and an end-to-end provenance trace.
- No change is authorized in the AI graph budget layer by this ADR.

## Explicitly out of scope for MVP-1

- Temporal, Kafka, Redis, or another external workflow/orchestration engine as the approval authority;
- autonomous, model-based, confidence-based, or scheduled approval;
- multi-agent approval, quorum approval, or reviewer delegation chains;
- Python-owned authoritative persistence or any AI access to Spring PostgreSQL schemas;
- approval of tutor, diagnostic, adaptation, formative-evaluation, mastery, progression, evidence,
  or recommendation decisions;
- editing/retracting historical approvals, evidence, mastery snapshots, or decision records;
- long-lived transactions, human-in-request waiting, streaming approval responses, or background AI
  calls during review;
- automatic re-approval after proposal, learner state, policy, curriculum, or engine-version change.

## Verification required for M1-T12

- Only eligible scored-context generated assessment content can create an approval request.
- Creation returns after durable persistence and survives process restart with no in-memory state.
- The state machine rejects every illegal transition, including approve/reject races.
- Same-key retries are idempotent; different fingerprints conflict; concurrent commands produce one
  authoritative outcome.
- Reviewer identity comes from authenticated Spring security, service/learner tokens are denied,
  and ADMIN MFA is enforced.
- Proposal payload/provenance is immutable and versioned; V017 trust-state invariants remain intact.
- Approve performs deterministic revalidation immediately before the authoritative operation and
  marks changed requests `SUPERSEDED` without a partial write.
- Expired and cancelled requests cannot execute; rejection requires and preserves a reason.
- Every create/review/refusal/expiry/cancellation is auditable with actor, timestamp, target,
  action, outcome, reason, interactionId, and traceId.
- A forced crash/retry drill demonstrates pending work resumes from PostgreSQL and a committed
  approval cannot execute twice.
- An interactionId-only investigation reaches the relevant traces, proposal provenance, approval
  record, and authoritative outcome.
