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
| [M2-ADR-017](M2-ADR-017-contract-b-durable-state-and-result-storage.md) | Contract B durable state lives in Spring/PostgreSQL under Flyway and RAMALS-AI stays stateless; only a normalized `diagnostic-proposal.v1` result may be persisted, in dedicated tables rather than `core.ai_execution*`, encrypted at rest, deleted on adoption with a 30-day ceiling, and never containing chain-of-thought or raw provider responses. | **Accepted** 2026-08-27 | Any future Contract B task; gates `V037` |
| [M2-ADR-018](M2-ADR-018-contract-b-result-classification-and-encryption.md) | Contract B result classification, access matrix and encryption at rest: RESTRICTED learner-derived model output, owned by the RAMALS Platform Data Owner role, no reporting/analytics/AI-plane grant, application-layer AES-256-GCM envelope encryption with `pgcrypto` rejected, key access behind a port with no vendor KMS wired, delete-on-adoption with a 30-day ceiling, fail-closed throughout. | **Accepted for MVP/research** 2026-08-27 — not approved for production | Gates `V037` |
| [M2-ADR-019](M2-ADR-019-contract-b-purge-semantics.md) | Contract B purge semantics: the result row is purged entire while execution identity, provider identifiers, `custom_id`, usage and cost survive for audit and reconciliation; two distinct purge paths with a windowed sweep that cannot target a row; a terminal-state test so a live execution is never purged; key destruction as a consequence of purge and never a substitute; and an executable proof before `V037` to resolve the ordering defect in prerequisite 5. | **Proposed** | Unblocks M2-ADR-017 §6 prerequisite 5 |
| [M2-ADR-023](M2-ADR-023-diagnostic-reasoning-is-evidence-not-a-gate.md) | Prerequisite-aware diagnostic reasoning (H1-H7): a weak prerequisite caps/deprioritizes a dependent skill's selection, never excludes it; diagnostic/causal confidence is a distinct, versioned, deterministic construct, never AI-decided and never fed back into mastery computation; H7 (diagnosis verification via reassessment) inherits the already-deferred retention/spaced-reassessment constraint and needs its own decision when scoped. Numbered `023`, not `022`, to avoid colliding with the still-unwritten M1-ADR-010-vs-MVP-2 evaluation-authority ADR. | **Proposed** | Gates H2, H5, H7 (not H1) |
| [M2-ADR-024](M2-ADR-024-hypothesis-driven-probe-relationship-foundation.md) | H4b probe-relationship foundation: `SAME_OBJECTIVE_CONFIRMATION`/`PREREQUISITE_VALIDATION` are read from existing `assessment_item_objective`/`skill_prerequisite`, never re-stored; only `ROOT_CAUSE_PROBE`/`CONTRADICTION_CHECK` get a new `core.diagnostic_probe_relationship` table; H4b code lives in `assessment`, not `diagnosis`, to keep H1's read-only-mastery boundary and its distinct `rootCauses` meaning intact; hypothesis/evidence stay non-authoritative (no `confirmedRootCause`-shaped field, evidence is a three-valued `SUPPORTING`/`CONTRADICTORY`/`INCONCLUSIVE` outcome, never a raw boolean); this ADR authorizes the foundation only, not `DIAGNOSTIC_SELECTION_V5`. §5 (added on review of PR #251): more than one candidate target objective is surfaced as `AMBIGUOUS_TARGET_OBJECTIVE`, never picked by an arbitrary tie-break. | **Proposed** | Gates the H4b foundation PR; does not authorize `DIAGNOSTIC_SELECTION_V5` |
| [M2-ADR-025](M2-ADR-025-hypothesis-driven-probe-runtime-selection.md) | `DIAGNOSTIC_SELECTION_V5` runtime: composition is V3 → V4 → V5 → frozen V2; V5 is a pool restriction + skill-priority adjustment, never a V2 code change, so V3's band cap is never silently undone and the probe quota (`MAX_HYPOTHESIS_PROBES_PER_PACKET = 1`) is enforced structurally; trigger eligibility is exactly "incorrect response in the single immediately-preceding COMPLETED same-version attempt, first miss by presentation_order, first relationship type by a fixed priority order, that resolves CANDIDATES_AVAILABLE" -- not "unexpectedness"; V5 overrides V4 on a shared skill because composition runs V5 last; ambiguity/no-items/all-exposed stay non-actionable, never arbitrated or converted to bank exhaustion; provenance is one new additive table, `core.diagnostic_probe_relationship` untouched; one new `SelectionReason` (`HYPOTHESIS_DRIVEN_PROBE`), not four. §8a (added on review, before merge): provenance-row consistency (source item really in source attempt, objectives really tag the right items, authorizing relationship required/forbidden per type and, when present, must exist/be PUBLISHED/exactly match) is enforced by composite FKs, one CHECK, and a trigger lookup at the database boundary, not trusted from the application writer alone. | **Proposed** | Gates the H4b runtime-integration PR |
| [M2-ADR-026](M2-ADR-026-granular-diagnostic-ontology-foundation.md) | Granular diagnostic ontology foundation: `Concept`/`Sub-concept` (`core.diagnostic_node`) are a content-driven, optional refinement of exactly one `LearningObjective` each -- never `learning_objective` rows themselves, never counted in `objectiveCoverage` or mastery, no third nesting level; `Misconception` is a separate, orthogonal entity with a DB-enforced exclusive-arc target (an objective, concept, or sub-concept) and its own `DRAFT`/`PUBLISHED` lifecycle, immutable once published; a wrong-option mapping ties one incorrect `SINGLE_CHOICE` option to one misconception and may only publish once its misconception is itself published; a new, separate `MisconceptionEvidenceOutcome` classifier governs evidence semantics and never modifies `HypothesisEvidenceOutcome`; V1 uses local, DB-enforceable exclusive-arc typed references, explicitly not frozen as the permanent representation for every future diagnostic subject type; foundation only -- no `core.diagnostic_node_relationship`, no granular provenance/confidence persistence, no wiring into `DiagnosticService`/`DiagnosticSubmissionService`/selection; a future runtime/provenance/confidence milestone needs its own separately reviewed design, with an additional ADR required only if it introduces a decision not already governed here or elsewhere. | **Proposed** | Gates the granular-diagnostic-ontology foundation PR; does not authorize runtime/provenance/confidence integration |

M2-ADR-016 is the capability gate the Contract B design document requires before implementation. It
was Proposed pending one product decision — whether a Contract-B `DIAGNOSE` may become asynchronous
— which was answered on review of PR #161 and is recorded in its §6 with the six rules bounding the
grant. Asynchrony is confined to Contract-B routes and is not a relaxation of M1-ADR-001.

**Addendum A (2026-08-27)** records the OpenAI Responses background-mode evaluation its second
revisit trigger called for, since `gpt-4.1-2025-04-14` is already an approved alternate binding.
Neither provider documents replay-safe admission. OpenAI loses on the lost-acknowledgement path:
its requests can carry a caller-supplied `metadata` label, but responses cannot be enumerated or
searched, so an unacknowledged execution is permanently unreachable and a duplicate is
undetectable — which makes the T15 cost-evidence scenario unbuildable. Anthropic Message Batches remains the selected path, and still provides no
provider-level exactly-once. The addendum adds evidence and reverses no decision.

**Acceptance authorizes design and construction, not traffic and not schema.** No Contract-B
production route is activated. The two further decisions M2-ADR-016 recorded as consequences —
where Contract B durable state lives, given M2-ADR-008 and M2-ADR-012, and whether model output may
be stored at all, given the `V035` invariant — are **both closed by M2-ADR-017**.

M2-ADR-017 keeps the AI plane stateless and puts Contract B durable state in Spring/PostgreSQL
under Flyway; permits only a normalized `diagnostic-proposal.v1` result, in dedicated tables that
leave every `core.ai_execution*` and `V035` structural-redaction guarantee literally intact;
prohibits chain-of-thought and raw provider responses outright; and requires encryption at rest,
deletion in the adoption transaction, and a 30-day ceiling chosen against the provider's own
retention window. It authorizes no schema and no code: its §6 lists seven prerequisites, of which
1–5 gate `V037` and 6–7 gate any route activation.

**Contract B status as of 2026-08-27: `V037` is no longer blocked on governance for the
MVP/research environment.** Prerequisite 2 is satisfied by the [amended Definition of
Done](../release/mvp2-contract-b-definition-of-done.md). Prerequisites 3 and 4 are satisfied for
that scope by M2-ADR-018, which carries the classification, access matrix, isolation, audit,
encryption architecture, key lifecycle and failure semantics, and now records the sign-off — Sunil
Pandey as Platform Data Owner and interim Key Custodian, classification `RESTRICTED —
LEARNER-DERIVED MODEL OUTPUT`.

**That approval is scoped to MVP/research and does not travel to production.** A production
deployment requires both roles to be reassigned under the deploying organisation's governance, and
the Data Owner and Key Custodian should then be different people — they are the same individual
here, which M2-ADR-018 records as accepted for a single-maintainer research environment rather than
as sound segregation of duties.

**Prerequisite 1 was qualified on 2026-08-27** against the real Anthropic API — one Message Batch
via the #170 adapter, with separate-process recovery by durable `msgbatch_…` identity, `custom_id`
correlation and exactly one submission. See the [prerequisite 1 qualification
evidence](../release/mvp2-contract-b-prerequisite-1-qualification.md). It qualifies **provider
capability only**: Anthropic still documents no replay-safe admission, cancellation was not
attempted and must not be represented as proven, and the lost-acknowledgement window was not
exercised.

**Prerequisite 5 was satisfied on 2026-08-27** by an [executable purge
proof](../release/mvp2-contract-b-purge-proof.md) — eight behaviours proven and five negative
controls caught against an isolated throwaway schema, per M2-ADR-019 §6.

~~`V037` remains blocked on M2-ADR-018 criteria 3, 4, 5 and 9 (the key-provider port, the envelope
format, fail-closed behaviour and log hygiene), none of which is done.~~ Criteria 6, 7 and 8 were
reclassified by M2-ADR-019 as `V037` completion criteria.

**Superseded 2026-08-28.** All nine M2-ADR-018 criteria are satisfied and `V037` has shipped —
the key-provider port in `#177`, the envelope and fail-closed behaviour in `#178`, and the grants,
transactional adoption and purge in `#179`. The durable lifecycle followed in `#181` and its
crash-recovery qualification in `#182`.

`V037` shipping is not Contract B being available, and neither is the lifecycle. No route is bound
to Contract B, the reconciliation worker is off, and four Definition-of-Done criteria remain unmet —
see the [MVP-2 closure assessment](../release/mvp2-closure-assessment.md). Approving the model was
not the same as building it; building it is not the same as qualifying it.

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
