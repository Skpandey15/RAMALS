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

M1-ADR-000 through M1-ADR-010 cite the external MVP-1 Canonical Package v1.3 as their authority. **That package is not in this repository.** It is a set of `.docx` files held outside the working tree, and a fresh clone therefore cannot check those cited sections against their source.

This is an asymmetry worth naming rather than tolerating quietly: MVP-0's source documents are frozen in `docs/` with a manifest/checksums, while the older MVP-1 canonical package is external. The repository-native decisions added after that package are grounded in repository code/design artifacts and are indexed here.

Which task each earlier decision gates is recorded in [the MVP-1 release board](../release/mvp1-release-board.md), which owns that mapping. This index says what each decision is and does not duplicate release-board status.

| ADR | Decision |
| --- | --- |
| [M1-ADR-000](M1-ADR-000-mvp1-engineering-before-r1.md) | MVP-1 engineering may begin before R1 closes; R1 still blocks the MVP-1 RC and comparative claims |
| [M1-ADR-001](M1-ADR-001-interaction-classes-and-deadlines.md) | Interaction classes, absolute deadlines, stricter-wins precedence and timeout semantics |
| [M1-ADR-002](M1-ADR-002-contract-generation-ownership.md) | Generate Python from the OpenAPI contract; hand-write Java records and validate with golden round-trip fixtures |
| [M1-ADR-003](M1-ADR-003-workload-identity.md) | Spring authenticates to ramals-ai with Keycloak client credentials and a distinct `ramals-ai` audience |
| [M1-ADR-004](M1-ADR-004-tutor-response-is-bounded-non-streaming.md) | Tutor V1 returns one complete validated response and does not stream |
| [M1-ADR-005](M1-ADR-005-ai-execution-persistence.md) | Persist one bounded, append-only execution record per request ID with digests and no raw prompts or outputs |
| [M1-ADR-006](M1-ADR-006-generated-assessment-trust-promotion.md) | Generated assessment content is UNVERIFIED on creation and only a human promotes it |
| [M1-ADR-007](M1-ADR-007-limited-durable-human-approval-workflow.md) | Spring/PostgreSQL limited-durable human approval with immutable proposal revisions and atomic revalidation |
| [M1-ADR-008](M1-ADR-008-model-routing-fallback-and-rollback.md) | Routes are versioned configuration; budgets are enforced before dispatch; rollback moves pointers rather than rewriting history |
| [M1-ADR-009](M1-ADR-009-ai-evaluation-release-gates.md) | AI evaluation uses hard PR gates and release quality gates with explicit baseline/exception governance |
| [M1-ADR-010](M1-ADR-010-assessment-evaluation-is-formative-only.md) | AI assessment evaluation is FORMATIVE_ONLY and can never create scored evidence |
| [M1-ADR-011](M1-ADR-011-prompt-identity-and-rollback.md) | Prompt identity is `promptTemplateId` + `promptVersion`; rollback is a validated configuration pin |
| [M1-ADR-012](M1-ADR-012-learner-jit-provisioning-and-onboarding-state-separation.md) | JIT operational learner provisioning remains compatible with ADR 0001 but never implies professional onboarding; onboarding terminates in `ONBOARDED` |
| [M1-ADR-013](M1-ADR-013-professional-learner-pii-storage-boundary.md) | Professional learner contact PII stays outside the PII-free `core.learner` boundary |
| [M1-ADR-014](M1-ADR-014-keycloak-administrative-client-for-learner-registration.md) | Learner registration uses a dedicated least-privilege Keycloak administrative client, separate from `ramals-core-workload` |
| [M1-ADR-015](M1-ADR-015-professional-registration-email-mobile-and-mfa-boundary.md) | RAMALS orchestrates registration; Keycloak verifies email; authenticated SMS proves mobile ownership but does not satisfy Keycloak MFA |

M1-ADR-011 through M1-ADR-015 are repository-native follow-on decisions rather than registrations from the external MVP-1 Canonical Package. M1-ADR-012 through M1-ADR-015 are the architecture authority for the M1-PROF-01 professional learner registration/onboarding capability.

## MVP-2

The proposed DOCX source package is retained under `docs/MVP02/RAMALS_MVP2_ADR_Package_v1.0`. Its fifteen decisions were accepted during M2-T01 on 2026-08-22. The repository-native [M2-ADR register](M2-ADR-register.md) owns task mapping, compatibility, and implementation status; the source documents retain detailed rationale and revisit triggers.
