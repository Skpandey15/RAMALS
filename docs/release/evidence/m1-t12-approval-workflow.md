# M1-T12 approval workflow evidence

Implementation is complete on the MVP-1 readiness branch.

The Spring/PostgreSQL workflow provides:

- durable `APPROVAL_REQUIRED` through terminal approval, rejection, expiry, cancellation, or supersession;
- immutable candidate payload and provenance snapshots;
- actor/operation/request-scoped idempotency with fingerprint conflict detection;
- controller and service authorization, including MFA for `ADMIN` review actions;
- final deterministic candidate and current-curriculum revalidation under a row lock;
- atomic authoritative assessment-item creation, approval-state transition, command result, and audit;
- rollback-safe failure semantics and a refusal guard for the previous direct promotion bypass.

Validation run:

- `./gradlew :learning-platform:test --no-daemon`
- `./gradlew :learning-platform:governanceTest :learning-platform:architectureTest --no-daemon`

Both completed successfully. PostgreSQL-reset integration suites require the configured shared
PostgreSQL environment variables and were not runnable from this workstation session.
