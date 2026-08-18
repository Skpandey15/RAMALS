# S0-03 Architecture Guardrails

`learning-platform:architectureTest` runs compiled-class dependency rules through ArchUnit. It is
separate from the fast `test` task and is included by `check`.

## Enforced boundaries

| Module | Allowed direction / boundary |
| --- | --- |
| curriculum | Owns curriculum facts and identifiers |
| evidence | Owns the authoritative evidence write path; may use curriculum/assessment identifiers |
| mastery | Uses evidence and curriculum facts to compute authoritative mastery |
| recommendation | Uses mastery and learner state to produce recommendations |
| learning | Coordinates curriculum, learner, mastery, and progression state |
| assessment | Coordinates learner submissions, evidence, mastery, and recommendation application services |
| ai | Uses contracts, curriculum facts, learner values, and read-only status values; cannot use authoritative repositories/services/engines or JDBC |
| controllers/API | Use application services or ports; cannot use JDBC or repositories directly |
| repositories | Cannot depend on controllers or Spring MVC implementation types |

The core business slices (`curriculum`, `assessment`, `evidence`, `mastery`, `recommendation`,
`learner`, `learning`, and `ai`) must be acyclic. `observability` and `security` are shared delivery
infrastructure and are excluded from that business-cycle rule because exception mapping and security
filters intentionally cross those technical boundaries.

Generic modules are also forbidden from depending on domain-specific Java packages such as Kafka,
CBSE, CISCE, or B.Tech. Domain content assets remain governed by the existing release tests.

## Existing-test disposition

`EvaluationAuthorityBoundaryTests` was replaced by `ArchitectureGuardrailTests` for compiled AI
writer/JDBC dependencies. The evidence persistence integration tests remain because ArchUnit cannot
prove SQL uniqueness, append-only behaviour, database privileges, or idempotency. `DomainNeutralityTests`
remains for repository/contract asset checks that are not Java class dependencies.

The database authority invariant remains runtime-owned: PostgreSQL tests exercise the evidence
append path and its database constraints. ArchUnit additionally ensures AI code cannot acquire that
writer type.
