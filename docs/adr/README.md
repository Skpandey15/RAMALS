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

Decisions adopted from the MVP-1 Canonical Package. The package registers M1-ADR-000 through
M1-ADR-010; only those listed here are authored and adopted in this repository. The remainder must
be authored before the implementation task each one gates.

| ADR | Decision | Gates |
| --- | --- | --- |
| [M1-ADR-000](M1-ADR-000-mvp1-engineering-before-r1.md) | MVP-1 engineering may begin before R1 closes; R1 still blocks the MVP-1 RC and any comparative claim | M1-T00 |
| [M1-ADR-001](M1-ADR-001-interaction-classes-and-deadlines.md) | Interaction classes, absolute deadlines, stricter-wins precedence and timeout semantics | M1-T02, M1-T08 |
| [M1-ADR-002](M1-ADR-002-contract-generation-ownership.md) | Generate Python from the OpenAPI contract; hand-write Java records and validate them with golden round-trip fixtures | M1-T02 |
| [M1-ADR-003](M1-ADR-003-workload-identity.md) | Spring authenticates to ramals-ai with Keycloak client credentials and a distinct `ramals-ai` audience | M1-T03 |
| [M1-ADR-008](M1-ADR-008-model-routing-fallback-and-rollback.md) | Routes are versioned configuration; hard budgets are enforced before dispatch; failure never escalates to a costlier route; rollback moves pointers and never rewrites recorded proposal metadata | M1-T05 |
| [M1-ADR-010](M1-ADR-010-assessment-evaluation-is-formative-only.md) | AI assessment evaluation is FORMATIVE_ONLY and can never create scored evidence | M1-T10 |

Not yet authored: M1-ADR-004 (Tutor streaming), 005 (ai_execution persistence), 006
(generated-assessment promotion), 007 (LIMITED_DURABLE approval), 009 (evaluation thresholds). Each
gates the task named in the package register; M1-T02, M1-T03 and M1-T05 are now unblocked.

