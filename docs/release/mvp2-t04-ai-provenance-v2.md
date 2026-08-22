# M2-T04 AI execution provenance v2 evidence

- **Task:** M2-T04
- **Status:** Implemented; pending PR acceptance
- **Closes:** TD-RC8-02
- **Decisions:** M2-ADR-005, M2-ADR-014, M2-ADR-015
- **Standard:** `Production_Grade_Coding_and_Performance_Standards_React_Java_Python_v1.0`

## Implemented boundary

The governed Python gateway now attaches the resolved provider, concrete model identifier, and
route-table/configuration version to every successful proposal. These values originate from the
server-owned route resolution after fallback/pinning, never from the caller. Spring persists them
with the existing logical route, prompt identity, agent-run identity, usage/cost fields, and
SHA-256 request/proposal digests. Trace correlation is captured at Spring's trusted persistence
boundary.

V027 is additive and rollback compatible. Historical executions remain valid with null v2 fields;
the system does not invent historical values from today's route table. The existing append-only
trigger prevents later route changes from rewriting recorded provenance.

## Security and privacy

- Only bounded metadata and hashes are stored.
- No prompt, raw model output, learner context, provider response, or credential column exists.
- `ci-fake` records provider `ci-fake` and model `ci-fake-deterministic-v1`; live bindings resolve
  to their governed `anthropic` or `openai` identity and dated model ID.

## Verification

- Generated Python contract model drift check: PASS.
- Python suite: 557 passed.
- Java `:learning-platform:check`: PASS.
- Migration rollback compatibility: PASS across 27 migrations.
- Fresh PostgreSQL 18 migration and provenance integration suites: PASS.
- Real database evidence verifies provider, concrete model, route version, trace ID, and both
  64-character SHA-256 digests on one execution row.
