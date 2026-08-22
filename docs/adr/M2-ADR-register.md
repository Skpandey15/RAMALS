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
