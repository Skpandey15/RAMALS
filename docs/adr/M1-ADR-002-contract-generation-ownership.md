# M1-ADR-002: Generate Python from the contract, validate Java against it

- **Status:** Accepted
- **Date:** 2026-08-16
- **Relates to:** MVP-1 Canonical Package v1.3 Doc 03 §2, Doc 08 §3
- **Required before:** M1-T02

## Context

Doc 03 makes `contracts/ai-internal.openapi.yaml` the only normative cross-language contract source,
with Java and Python "generated or validated from it", and prohibits hand-maintained duplicate DTOs.

That leaves the mechanism open. Both sides could be generated, one could be generated, or neither —
and the trade is between parity guarantees and code that matches its surroundings.

The Java side already has a settled idiom: records with Jackson 3 (`tools.jackson`), the same shape
as `ApiProblem` and every existing DTO. Generated Java would not look like that, and MVP-0's
readability came partly from every DTO being written the same way.

## Decision

**Python models are generated** from the OpenAPI document at build time. Generated output is
committed, and CI fails on drift between the committed models and a fresh generation.

**Java DTOs are hand-written records** in the existing style, and **validated** against the contract
rather than generated from it. Validation is three things, all required in CI:

1. **Golden round-trip payloads.** A shared fixture set under `contracts/golden/` is deserialized
   and re-serialized by both languages and compared against the expected JSON. A field added to the
   contract without a matching Java record change fails here.
2. **OpenAPI lint and schema validation**, so the contract itself stays well-formed.
3. **Breaking-change detection** against the previous released contract version.

The fixtures are the contract's executable form. They are not illustrative examples.

## Alternatives considered

**Generate both.** Strongest parity guarantee, and the obvious answer if the Java codebase had no
established DTO idiom. Rejected because it adds two generators to the build and produces Java that
reads unlike everything around it, for a guarantee the golden tests already provide. Worth revisiting
if the contract grows large enough that hand-maintaining records becomes the bottleneck.

**Hand-write both.** Rejected: Doc 03 explicitly warns against duplicate hand-maintained DTOs, and
without generation on at least one side there is nothing forcing the contract file to stay in step
with either implementation.

**Derive the contract from FastAPI's generated OpenAPI.** Rejected because it inverts Doc 03 — the
contract would become a description of the Python implementation rather than an agreement between
two services, and a Python refactor could silently change the Java-facing contract.

## Consequences

- The residual risk is Java drift, and it is bounded by the golden fixtures. If those are ever
  weakened, this decision stops being safe — so the fixture suite is a hard CI gate, not a quality
  one.
- Adding a contract field is a three-part change: the OpenAPI document, the Java record, and a
  golden fixture covering it. That friction is deliberate.
- The contract is versioned via `contractVersion` on every envelope; a breaking change requires a new
  version rather than an edit.
