# M2-T05 GroundedContext v1 evidence

- **Task:** M2-T05
- **Status:** Implemented; pending PR acceptance
- **Decision:** M2-ADR-006
- **Standard:** `Production_Grade_Coding_and_Performance_Standards_React_Java_Python_v1.0`

## Contract boundary

Spring owns construction of `GroundedContext`; the AI plane only validates and consumes it. The
contract carries an opaque learner reference, package identity/version/freshness, retrieval-policy
version, and a bounded ordered set of scalar facts. Every fact names its stable evidence ID, source
type, source version, authority class, fact type, observation time, and optional expiry.

Authoritative facts and model-generated summaries are explicitly labelled. A summary cannot satisfy
an agent's required authoritative source. Context retrieval itself remains M2-T06 scope.

## Production bounds and fail-closed rules

- Maximum 64 items and 65,536 serialized bytes.
- Scalar values only; nested objects/arrays and arbitrary database dumps are rejected.
- Strings are capped at 2,048 characters; identifiers and versions are capped at 64.
- Package and item freshness are checked against an injected clock value.
- Sensitive fact names covering identity, contact data, credentials, secrets, passwords, and raw
  prompts are rejected.
- Missing required authoritative source types, stale packages, unknown fields, unsupported contract
  versions, and invalid value types fail closed with stable error codes.
- Java construction sorts stable source identities before deriving the deterministic context ID.

## Verification

- Shared golden JSON validates in both Java and Python.
- Java tests cover reproducible ordering/identity, required-source failure, stale context,
  sensitive-field rejection, and structured-dump rejection.
- Python tests cover required authoritative grounding, summary isolation, freshness, strict unknown
  fields, item count, sensitive fields, structured values, and value size.
- Full Java, Python, contract, and security gates are required before merge.
