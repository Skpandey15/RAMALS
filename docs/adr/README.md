# Architecture decision records

Consequential implementation decisions must be recorded as ADRs with context, decision, alternatives, consequences, and verification evidence.

| ADR | Decision |
| --- | --- |
| [0001](0001-learner-identity-from-oidc-subject.md) | Learner identity is anchored to the OIDC subject |
| [0002](0002-synchronous-adaptive-pipeline.md) | The adaptive pipeline runs synchronously inside diagnostic submission |
| [0003](0003-migration-numbering-follows-implementation-order.md) | Migration numbering follows implementation order |
| [0004](0004-container-scanning-and-dependency-pinning.md) | Scan with the Trivy container image, and pin dependencies ahead of the BOM |
| [0005](0005-correlation-component-naming.md) | Correlation components keep repository-idiomatic names |

## MVP-1

### Where the source package lives

Every M1 ADR cites "MVP-1 Canonical Package v1.3 Doc NN §X" as its authority. **That package is not
in this repository.** It is a set of ten `.docx` files held outside the working tree, and a fresh
clone therefore cannot check any MVP-1 decision against its source.

This is an asymmetry worth naming rather than tolerating quietly: MVP-0's nine source documents are
frozen in `docs/` with a manifest and SHA-256 checksums, so an MVP-0 decision can be audited from the
clone alone. MVP-1's cannot.

It is recorded here rather than fixed here because bringing the package in is a decision about what
belongs in the repository, not a documentation edit — the files are large, binary, and versioned
outside this project. Until that is settled, a reviewer who needs a cited section has to ask for it.

Decisions adopted from the MVP-1 Canonical Package. **Which task each decision gates is recorded in
[the MVP-1 release board](../release/mvp1-release-board.md), which owns that mapping.** This index
says what each decision is; it deliberately does not restate what it blocks.

The package registers M1-ADR-000 through
M1-ADR-010; only those listed here are authored and adopted in this repository. The remainder must
be authored before the implementation task each one gates.

| ADR | Decision |
| --- | --- |
| [M1-ADR-000](M1-ADR-000-mvp1-engineering-before-r1.md) | MVP-1 engineering may begin before R1 closes; R1 still blocks the MVP-1 RC and any comparative claim |
| [M1-ADR-001](M1-ADR-001-interaction-classes-and-deadlines.md) | Interaction classes, absolute deadlines, stricter-wins precedence and timeout semantics |
| [M1-ADR-002](M1-ADR-002-contract-generation-ownership.md) | Generate Python from the OpenAPI contract; hand-write Java records and validate them with golden round-trip fixtures |
| [M1-ADR-003](M1-ADR-003-workload-identity.md) | Spring authenticates to ramals-ai with Keycloak client credentials and a distinct `ramals-ai` audience |
| [M1-ADR-004](M1-ADR-004-tutor-response-is-bounded-non-streaming.md) | Tutor V1 returns one complete validated response and does not stream: unsupported learner-state claims are only detectable on a finished response, and streamed text cannot be un-read |
| [M1-ADR-005](M1-ADR-005-ai-execution-persistence.md) | Persist one bounded, append-only execution record per request ID with digests and no raw prompts or outputs |
| [M1-ADR-006](M1-ADR-006-generated-assessment-trust-promotion.md) | Generated assessment content is UNVERIFIED on creation and only a human promotes it; automated validation may reject but never approve |
| [M1-ADR-007](M1-ADR-007-limited-durable-human-approval-workflow.md) | Spring/PostgreSQL limited-durable human approval with immutable proposal revisions, atomic revalidation, and no AI authority |
| [M1-ADR-008](M1-ADR-008-model-routing-fallback-and-rollback.md) | Routes are versioned configuration; hard budgets are enforced before dispatch; failure never escalates to a costlier route; rollback moves pointers and never rewrites recorded proposal metadata |
| [M1-ADR-009](M1-ADR-009-ai-evaluation-release-gates.md) | Doc 07 owns the evaluation thresholds; hard gates block every pull request while quality gates block a release candidate, a baseline is approved by a named person, a quality regression needs a named owner with scope and expiry, a hard-gate regression cannot be accepted, and dataset changes never land with model changes |
| [M1-ADR-010](M1-ADR-010-assessment-evaluation-is-formative-only.md) | AI assessment evaluation is FORMATIVE_ONLY and can never create scored evidence |
| [M1-ADR-011](M1-ADR-011-prompt-identity-and-rollback.md) | A prompt is identified by `promptTemplateId` + `promptVersion`, the identity resolves to the artifact that builds it, and a rollback is a configuration pin validated at startup and verified against the running service |

Not yet authored: none. All eleven decisions the package registers are authored and Accepted. Each
was written just before the task it gates rather than as a batch, so it had the information its
implementation produced.

**M1-ADR-011 does not come from the package.** It originates here, from M1-T17, and amends
M1-ADR-008 rather than adopting a decision made elsewhere. Its sources are the Business Logging /
Exception / Observability HLD-LLD v1.0 and the Updated Implementation Master Plan v2.0 — the
19 August 2026 architecture pack, which is also held outside the working tree and so carries the
same auditability asymmetry named above. It is numbered in the same sequence because a reader
looking for MVP-1 decisions should find it there, not because the package registers it.

Which tasks are unblocked, and which are done, is deliberately not restated here — that is the
[release board](../release/mvp1-release-board.md)'s, and a mapping kept in two places is a mapping
that will disagree with itself.

## MVP-2

The proposed DOCX source package is retained under
`docs/MVP02/RAMALS_MVP2_ADR_Package_v1.0`. Its fifteen decisions were accepted during M2-T01 on
2026-08-22. The repository-native [M2-ADR register](M2-ADR-register.md) owns task mapping,
compatibility, and implementation status; the source documents retain the detailed rationale and
revisit triggers.
