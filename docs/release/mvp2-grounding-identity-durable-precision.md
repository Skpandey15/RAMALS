# Grounded context identity must survive persistence

> **Status: `PRODUCTION REMEDIATION — AWAITING REVIEW`**
>
> Fixes the defect that Phase-2 qualification classified `FIX FAILED` on candidate
> `62cbed8171180d73d425407ff0f126c2c57b562c`. Application code only. No qualification harness
> change, no migration, no candidate rebuild in this PR.

## What Phase 2 observed

The DIAGNOSE post-commission / pre-provider pod-death scenario was run twice against the attested
`#154` candidate. Both runs failed identically:

- worker A commissioned the exact diagnostic request; dispatch row `AVAILABLE`, `fence 0`;
- provider invocation count `0`;
- A was killed at `WORKFLOW_AFTER_DIAGNOSTIC_COMMISSION`;
- the natural 60-second lease expired without `claimed_at` being touched;
- worker B reclaimed as attempt 2 with a new workflow token, same deterministic `requestId`;
- B called `retrieveAt(prior.asOf())` and got a **different `context_id`**;
- `AI_EXECUTION_COMMISSION_CONTEXT_MISMATCH` correctly rejected it;
- retries exhausted: `FAILED / STEP_ATTEMPTS_EXHAUSTED`.

Evidence: `deploy/k8s/t15/evidence/m2-t15.2-diagnostic-commission-post-154-20260826T055501Z/` and
`…-rerun-20260826T061459Z/`, each with a hand-captured `post-mortem-durable-state.json`.

The `#154` dispatch fencing itself was never at fault. Exactly one commission existed, the row never
left `AVAILABLE`, the provider was never called, and nothing was redispatched. The remediation could
not *begin* because the request could not be reconstructed.

## Confirmed root cause

`ledger.grounding_retrieval_record` held two rows for the same commission with **identical**
`as_of`, `expires_at`, `retrieval_policy_version` and all 27 `source_refs` in the same order — and
two different `context_id` values.

The identity is minted in `GroundedContextFactory.create` as
`UUID.nameUUIDFromBytes(learnerRef|policyVersion|asOf|items…)`, using `asOf.toString()`.

| Stage | Precision |
| --- | --- |
| `Clock.systemUTC().instant()` (Linux, `eclipse-temurin:25`) | nanoseconds — measured 1000/1000 samples with non-zero sub-microsecond digits |
| value hashed into `contextId` | nanoseconds |
| `ledger.grounding_retrieval_record.as_of` (`timestamptz`) | microseconds (`datetime_precision = 6`) |
| `core.ai_execution_dispatch.context_as_of` (`timestamptz`) | microseconds |
| value read back into `DiagnosticCommissionContext.asOf()` | microseconds |
| value re-hashed by `retrieveAt(prior.asOf())` | microseconds |

So the identity was a function of a precision the store cannot return. Demonstrated directly in the
build image:

```text
original  = 2026-08-26T06:33:55.920889721Z
persisted = 2026-08-26T06:33:55.920889Z
contextId(original)  = 43e7b037-5a09-3c39-89f4-39d36c942b5e
contextId(persisted) = 70be2f4f-6ed3-3f90-82a1-fb2b478b6805
```

Every stored attribute matched precisely *because storage is what truncated them*. Only the derived
id, computed before the write, disagreed.

## The fix

One helper, applied at the boundary that mints or reconstructs a durable grounding identity:

- **`DurableInstant.canonical(Instant)`** — truncates to `ChronoUnit.MICROS`, the precision
  PostgreSQL `timestamptz` preserves. Idempotent, which is what lets an original and a
  reconstruction agree exactly.
- **`GroundedContextFactory.create`** canonicalizes `asOf` before hashing it into the identity and
  before constructing the context, and canonicalizes `expiresAt`. This is the one place a
  `contextId` comes into existence, so no caller can mint an identity that depends on precision the
  database will drop.
- **`GroundingRetrievalService.retrieveAt`** canonicalizes its input once, so the retrieval query and
  the resulting context are pinned to the same instant.

`retrieve()` already delegates to `retrieveAt(clock.instant())`, so the fresh-retrieval path is
covered by the same canonicalization rather than a parallel one.

**The mismatch guard is unchanged and still compares exactly.** Nothing compares timestamps
"approximately". What changed is that the input is now well-defined, so a correct reconstruction
genuinely produces the same identity.

## Tests

`DurableInstantTests` (6): precision matches `timestamptz`, idempotence, truncation rather than
rounding, differences above the precision preserved, null pass-through, pre-epoch stability.

`GroundingIdentityDurablePrecisionIntegrationTests` (5, real PostgreSQL): a mock cannot reproduce
this — an in-memory store returns the `Instant` it was given, nanoseconds intact, and every
reconstruction agrees. Only a real `timestamptz` column truncates.

1. `durableTimestampColumnsKeepMicrosecondPrecision` — asserts `datetime_precision = 6` on all three
   durable columns and that PostgreSQL truncation equals `DurableInstant.canonical`, so the schema
   and the constant cannot drift apart silently.
2. `contextIdentitySurvivesPostgresRoundTripAndReconstruction` — create at nanosecond resolution,
   persist, read back, `retrieveAt(readBackAsOf)`, require identical `contextId`; canonical `asOf`
   equals the persisted value; no second audit row.
3. `identityIsStableForEqualInputsAndDistinctForDifferentCanonicalInstants` — instants differing only
   below the durable precision are one identity; a difference the store can represent still changes
   it.
4. `recoveredDiagnosticCommissionReconstructsTheSameGroundedContext` — the Phase-2 round trip:
   commission, `findRecoverableDiagnosticCommission`, re-ground at the recovered timestamp, identity
   matches; dispatch still `AVAILABLE` with one `STARTED` event.
5. `commissionRequestDigestStaysDeterministicAcrossReconstruction` — the commission `request_digest`
   embeds the whole context, so re-commissioning with a reconstructed context must not raise the
   reused-requestId conflict.

Negative control: with the two production files reverted and everything else unchanged, 4 of the 5
integration tests fail with exactly the Phase-2 signature —
`expected 2026-08-26T06:42:03.750152457Z but was 2026-08-26T06:42:03.750152Z`, and diverging
`contextId` values. The schema-precision test correctly still passes, being a fact about the column
rather than about behaviour.

## Durable identity audit beyond DIAGNOSE

`asOf` was the only `Instant` minted from a clock *and* hashed into a durable identity. Everything
else either does not hash an `Instant` or reads it back from PostgreSQL already canonical.

| Durable identity | Instant input | Affected |
| --- | --- | --- |
| `GroundedContext.contextId` | `asOf` from `Clock.systemUTC()` | **yes — fixed** |
| `ai_execution_event.request_digest` | serializes the whole `GroundedContext` | **yes — fixed transitively** |
| `GroundedContextItem.observedAt` / `expiresAt` | constructed only in `JdbcGroundingRetrievalRepository.mapItem`, read from the column | no — canonical by construction |
| `ai_execution.proposal_digest` (`AiProposalEnvelope`) | none | no |
| `assessment_evaluation_decision` digest | none — ids, enums, numbers, strings | no |
| `CandidateCanonicalizer` content/approval digests | none | no |
| `ApprovalRequestService` idempotency fingerprint | none | no |
| `RecommendationRepository.deterministicId` | none — derived from a decision UUID | no |
| Evidence lineage ids | `UuidV7.generate()`, stored not hashed, never reconstructed | no |

`ai_execution.started_at` / `completed_at` are persisted but never hashed, so they are provenance,
not identity. No unrelated timestamp semantics were changed.

## Candidate rebuild and attestation impact

This changes application code, so the current T15 candidate is superseded. Once merged:

- the `#154` candidate `62cbed8…` / backend `sha256:04098418…` no longer represents `main`;
- a new candidate must be built from the merged commit, `images.lock.json` and `kustomization.yaml`
  repinned, and `candidate-integrity.ps1` re-run to PASS;
- **no migration is added**, so the Flyway set stays `V001`–`V035` and the schema is unchanged;
- Phase 2 must then be re-run on the new candidate before the fix can be called verified.

Phase 2 remains `FIX FAILED` against `62cbed8…` and nothing here changes that record.

## Not in this PR

The two qualification-harness defects Phase 2 exposed — the per-attempt barrier that can strand
attempt 3, and the driver-stage failure that exits before `scenario.json` / `dispatchProof` is
emitted — are recorded separately and belong to a harness PR, not to this one.
