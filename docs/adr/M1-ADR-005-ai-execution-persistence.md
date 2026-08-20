# M1-ADR-005: Persist AI execution provenance

- **Status:** Accepted
- **Relates to:** M1-T13, M1-ADR-001, M1-ADR-003, M1-ADR-008

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
