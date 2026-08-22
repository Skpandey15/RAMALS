# M2-T06/T07 - deterministic grounding retrieval and validation

- **Status:** Implemented
- **Architecture:** M2-ADR-006 and M2-ADR-007
- **Invariant:** Agents recommend; deterministic Spring services decide.

## Delivered boundary

Spring retrieves a bounded `GroundedContext` from learner evidence, latest mastery, the published
skill graph, curriculum policy, and verified assessment content. The storage adapter starts every
query branch from the active learner resolved by the authenticated OIDC subject; callers cannot
provide a different learner ID. Each category is independently capped, queries have a database
timeout, ordering is stable, and the fixed v1 policy can select at most 60 contract items.

The exact selected source/type/version identities are stored in the append-only
`ledger.grounding_retrieval_record`. Context payloads, prompts, answer keys, learner PII, and model
reasoning are not stored there.

`ProposalGroundingGate` validates a normalized diagnostic or assessment-evaluation proposal against
the exact supplied context. Every claim needs a bounded authoritative evidence reference of an
allowed source type; fabricated, summary-only, stale, mismatched, missing-source, and low-confidence
proposals fail closed. Confidence is an additional proposal-type policy check, never a substitute
for evidence. Stable reason codes and normalized referenced IDs are appended to
`ledger.proposal_gate_decision`.

## Qualification mapping

| Scenario | Evidence |
| --- | --- |
| D01 authorized learner only | Subject-rooted SQL plus PostgreSQL cross-learner integration assertion |
| D02 stable ordering | Fixed clock/state produces byte-equivalent record values and context ID |
| D03 missing grounding | Existing context validator and retrieval empty/missing-source failures |
| D04 fabricated evidence ID | Gate rejection with `EVIDENCE_REFERENCE_UNKNOWN` |
| D05 stale evidence/context | `GROUNDING_INVALID` before claim evaluation |
| D06 oversized context | Policy constructor and context validator enforce hard item/byte caps |
| D07 malicious retrieved instruction | Retrieval emits typed scalar facts only; no content text or prompts |
| D08 approved content only | SQL requires `VERIFIED_CONTENT` and a published/retired pinned version |
| D09 source metadata | Every selected item carries source type, stable ID, and source version |
| D10 latency timeout | JDBC statement timeout plus elapsed-time fail-closed guard |

## Production-grade controls

- Immutable records and ports separate domain policy from JDBC side effects.
- No unrestricted SQL, learner-ID selector, arbitrary data-source selector, or unbounded read API.
- Audit inserts are idempotent on context identity and proposal/policy identity.
- PostgreSQL constraints re-enforce size, JSON shape, proposal type, referential integrity, and
  append-only behavior.
- Unit tests cover deterministic positive and negative paths; PostgreSQL integration coverage runs
  when the repository's guarded database-test environment is enabled.

## Rollback

Application rollback is safe because V028 is additive. Keep the two audit tables during rollback so
already-recorded retrieval and gate evidence remains reconstructable. A later forward migration may
retire unused structures only after retention and audit obligations are satisfied.
