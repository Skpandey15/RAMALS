# M2-T01 architecture and contract freeze

- **Status:** Accepted
- **Date:** 2026-08-22
- **Baseline:** MVP-1 `v0.1.0-rc8`, `PASS_WITH_ACCEPTED_DEBT`
- **Decision register:** [M2-ADR register](../adr/M2-ADR-register.md)

## Codebase findings

The current implementation matches the documented MVP-1 baseline:

- `AdaptationComparisonListener` dispatches only after commit, so the model call is outside the
  authoritative transaction, but the in-memory event is not durable (`TD-T18-01`).
- `core.ai_execution` is append-only and idempotent by `request_id`, but cannot establish the
  resolved provider or route/configuration version (`TD-RC8-02`).
- The Python Diagnostic Agent and Spring `DiagnosticProposalGate` exist, but no learner-domain
  invocation/workflow connects them. MVP-2 must not treat this as simple wiring.
- LangGraph is already contained in `ramals-ai`; the AI plane has no database authority.
- Existing request/proposal envelopes provide a compatible v1.0 base but do not represent durable
  work or a fully grounded context.

## Seven-question integration resolution

| Question | MVP-2 resolution | State |
| --- | --- | --- |
| 1. What invokes the capability? | A versioned `AgentWork` item, persisted with its source domain decision/evidence. Explicit workflow eligibility creates subsequent work. | Resolved |
| 2. Who owns invocation? | Spring domain/application services create work; the PostgreSQL dispatcher delivers it. The AI plane cannot self-schedule authoritative work. | Resolved |
| 3. What does the agent return? | A versioned structured `DiagnosticProposal` or `AssessmentEvaluationProposal`, with evidence references and proposal metadata. | Resolved |
| 4. What gate applies? | Spring-owned schema, evidence, semantic, policy, bounds, and confidence checks with stable reason codes. | Resolved |
| 5. Who has authority? | Deterministic Spring services and PostgreSQL. Agent output alone has no domain effect. | Resolved |
| 6. What durable evidence exists? | Source state + outbox work + execution provenance + proposal/gate decision, joined by interaction, trace, request, agent-run, and decision identifiers. | Resolved |
| 7. What proves it end to end? | The versioned MVP-2 qualification scenarios, including crash, replay, fabricated evidence, rejection, correlation, and selective live-provider cases. | Resolved |

## State ownership

| State | Owner | Persistence rule |
| --- | --- | --- |
| Mastery, attempts, progression, policy, evidence, decision records | Spring/PostgreSQL domain services | Authoritative and historically reconstructable |
| Agent work, leases, attempts, terminal status, execution provenance | Spring/PostgreSQL execution infrastructure | Durable operational/audit state |
| Retrieved context, graph node state, repair attempts, model/tool intermediates | Python AI plane/LangGraph | Transient and reconstructable; never authoritative |
| Proposals and gate outcomes | Proposal-specific Spring persistence | Immutable/versioned evidence linked to the execution and source decision |

## Frozen contracts

The machine-testable schemas are:

- `contracts/mvp2/agent-work.v1.schema.json`
- `contracts/mvp2/grounded-context.v1.schema.json`
- `contracts/mvp2/diagnostic-proposal.v1.schema.json`
- `contracts/mvp2/assessment-evaluation-proposal.v1.schema.json`

Contract rules:

1. Identifiers are bounded strings and correlation IDs cross every boundary.
2. `requestId` is the stable logical idempotency identity; transport attempts do not replace it.
3. Evidence references must be drawn from the supplied `GroundedContext`; gates fail closed on
   unknown, unauthorized, stale, or incompatible references.
4. Context facts and model-generated summaries are distinct. MVP-2 v1 permits authoritative facts
   only; summaries require an additive contract revision and explicit provenance.
5. Schemas reject unknown fields to prevent silent authority expansion.
6. Raw prompts, hidden reasoning, credentials, and unrestricted learner records are excluded.

## Task entry gates

- **T02 may start:** ADRs and `AgentWork` are frozen.
- **T03 may start after T02:** outbox schema and transaction tests must exist first.
- **T04 may start after T01/T02:** provenance additions must be additive and historical rows remain
  untouched.
- **T05-T07:** use the frozen grounded-context contract.
- **T08 is hard-blocked until T02, T03, T04, and T07 pass.**
- T11-T14 remain blocked by the dependencies in the MVP-2 execution board.

## Debt and documentation reconciliation

- `TD-T18-01` and `TD-RC8-02` are mandatory P0 foundation work in T02-T04.
- `TD-R1-03` and `TD-RC8-01` remain qualification/hardening work; this freeze assigns
  `TD-RC8-01` priority P1 to match the implementation master plan.
- The authoritative task sequence contains T01 through T16. The architecture review's T01-T15
  sequence is interpreted as omitting the separate T16 release-closure task; the master plan and
  execution board control.

## T01 exit decision

Ownership, authority, transaction placement, durability, retry semantics, state separation,
grounding, proposal gating, orchestration boundaries, routing provenance, and test strategy are
resolved. M2-T01 is complete when these files pass repository validation. M2-T02 is the next
authorized implementation task; no new agent implementation is authorized yet.
