# MVP-2 architecture decision register

- **Status:** Accepted for MVP-2 implementation
- **Accepted:** 2026-08-22
- **Source package:** `docs/MVP02/RAMALS_MVP2_ADR_Package_v1.0`
- **Governing invariant:** Agents recommend. Deterministic services decide.

This register adopts the decisions in the MVP-2 ADR package as the architecture baseline for
M2-T01. The source DOCX files retain the context, alternatives, rationale, consequences, and revisit
triggers. This repository-native register owns implementation status and task mapping.

| ADR | Accepted decision | Gates |
| --- | --- | --- |
| M2-ADR-001 | Spring Boot/PostgreSQL remain authoritative; agents emit proposals only. | T01-T16 |
| M2-ADR-002 | Persist agent work transactionally with the authoritative change. | T02 |
| M2-ADR-003 | Use at-least-once transport and idempotent, exactly-once business effects. | T03, T09, T12, T14 |
| M2-ADR-004 | Use a PostgreSQL claim/lease dispatcher first; Kafka is deferred. | T02-T03 |
| M2-ADR-005 | Persist immutable resolved provider/model/route/prompt and correlation provenance. | T04 |
| M2-ADR-006 | Spring builds a bounded, authorized, versioned grounded-context package. | T05-T07 |
| M2-ADR-007 | Spring-owned gates validate schema, evidence, semantics, policy, and confidence. | T07, T09, T12 |
| M2-ADR-008 | LangGraph is confined to transient AI-plane execution. | T08, T11, T14 |
| M2-ADR-009 | Workflows are explicit and bounded; agents do not invoke peers freely. | T14 |
| M2-ADR-010 | Deterministic assessment scoring is preferred; AI evaluation is proposal-only. | T11-T13 |
| M2-ADR-011 | Retries are bounded; failures become observable terminal/quarantine state; replay is explicit. | T03, T14-T15 |
| M2-ADR-012 | Domain, durable execution, and transient orchestration state have separate owners. | T02-T16 |
| M2-ADR-013 | Deterministic fakes drive regression; live-provider qualification is selective. | T10, T15-T16 |
| M2-ADR-014 | Logical model routes and their concrete resolution are versioned. | T04 |
| M2-ADR-015 | Correlation identifiers and stable persisted joins are part of every agent contract. | T02-T16 |

## Acceptance conditions and bounded open parameters

The decisions above are accepted. Values that require measurement are deliberately implementation
parameters, not unresolved architecture decisions: lease duration, retry count/backoff, context-size
limits, retention beyond the existing 400-day execution policy, and confidence thresholds. Each
must be configuration with deterministic tests and must be recorded in the implementing task's
evidence. Changing the authority, delivery, state-ownership, or replay semantics requires a new ADR.

## Decisions originating in this repository

Numbered into the same sequence so a reader looking for MVP-2 decisions finds them, but **not part
of the accepted package above** and not covered by its acceptance. Each carries its own status.

| ADR | Decision | Status | Gates |
| --- | --- | --- | --- |
| [M2-ADR-016](M2-ADR-016-provider-execution-contract-capability.md) | Provider capability profile for execution contracts: the synchronous Messages API is Contract B unsupported; Message Batches satisfies every mandatory row except replay-safe admission; execution contract binds to a route and never silently degrades to Contract A or to redispatch; a Contract-B `DIAGNOSE` may be asynchronous, a Contract-A `DIAGNOSE` may not. | **Accepted** 2026-08-27 | T15.2, and any future Contract B task |

M2-ADR-016 is the capability gate the Contract B design document requires before implementation. It
was Proposed pending one product decision — whether a Contract-B `DIAGNOSE` may become asynchronous
— which was answered on review of PR #161 and is recorded in its §6 with the six rules bounding the
grant. Asynchrony is confined to Contract-B routes and is not a relaxation of M1-ADR-001.

**Acceptance authorizes design and construction, not traffic and not schema.** No Contract-B
production route is activated, and no durable execution ledger may be built until two further
decisions are taken: where Contract B durable state lives, given M2-ADR-008 and M2-ADR-012, and
whether model output may be stored at all, given the `V035` invariant. Both are recorded as
consequences in M2-ADR-016 and each needs its own ADR.

**Contract A remains the default and current execution contract.** Every route is on Contract A, it
stays correct and supported for routes that never move, and this decision neither deprecates it nor
schedules its removal.

## Supersession and compatibility

- M2-ADR-002 supersedes `AFTER_COMMIT` as the correctness boundary for agent delivery. An
  `AFTER_COMMIT` listener may remain only as a best-effort wake-up optimization after T02.
- M2-ADR-005 extends M1-ADR-005 without rewriting historical `core.ai_execution` rows.
- M2-ADR-010 preserves M1-ADR-010: AI evaluation remains formative/proposal-only unless a later ADR
  deliberately changes that product boundary.
- Existing contract v1.0 remains compatible. MVP-2 contracts use independent version `1.0` schemas
  and are introduced additively.

## Implementation evidence

- M2-ADR-002 persistence boundary: [M2-T02 transactional outbox](../release/mvp2-t02-transactional-outbox.md)
- M2-ADR-003/004/011 delivery boundary: [M2-T03 durable dispatcher](../release/mvp2-t03-durable-dispatcher.md)
- M2-ADR-005/014 provenance boundary: [M2-T04 AI provenance v2](../release/mvp2-t04-ai-provenance-v2.md)
- M2-ADR-006 context boundary: [M2-T05 GroundedContext v1](../release/mvp2-t05-grounded-context-contract.md)
