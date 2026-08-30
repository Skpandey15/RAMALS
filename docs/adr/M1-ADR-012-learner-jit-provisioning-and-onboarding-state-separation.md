# M1-ADR-012: JIT learner provisioning does not imply professional onboarding completion

- **Status:** Accepted
- **Date:** 2026-08-30
- **Relates to:** ADR 0001, M1-PROF-01
- **Required before:** M1-PROF-01 PR-A

## Context

ADR 0001 deliberately anchors learner identity to OIDC `sub` and provisions `core.learner` just in time on first authenticated contact. The existing schema gives that learner an operational status, currently used as `ACTIVE | SUSPENDED | CLOSED`; existing repository logic uses operational `ACTIVE` to decide whether background/deterministic work may proceed.

M1-PROF-01 adds a different concern: professional-product onboarding requires verified email, verified mobile ownership, professional profile and a valid initial learning journey. Reusing `ACTIVE` for this lifecycle would overload a load-bearing existing status. Worse, treating JIT `ACTIVE` as onboarding completion would create a direct bypass of the new verification gates.

## Decision

Keep the two dimensions separate.

1. ADR 0001 remains authoritative for OIDC-sub identity anchoring and JIT provisioning.
2. `core.learner.status` keeps its existing operational meaning (`ACTIVE | SUSPENDED | CLOSED`).
3. M1-PROF-01 uses a separate onboarding state with terminal value `ONBOARDED`.
4. JIT provisioning may create an operationally `ACTIVE` learner but MUST NOT create, set or imply `ONBOARDED`.
5. A professional operation that requires completed onboarding checks normal authentication/authorization/resource ownership, applicable operational status, and `onboarding_state=ONBOARDED`.
6. Existing consumers whose business meaning is only operational `ACTIVE` are not silently changed. PR-A must inventory status consumers and explicitly identify any path that requires the combined gate.
7. Legacy learners receive an explicit rollout/backfill/compatibility policy; absence of new onboarding state must never be guessed into a security decision.

This ADR **clarifies and constrains** ADR 0001 for the professional product. It does not supersede the OIDC-sub or `/me` decisions.

## Alternatives considered

**Change JIT provisioning to create a non-ACTIVE learner.** Rejected as the default because it changes existing operational semantics and can break deterministic/background workflows unrelated to professional onboarding.

**Reuse `core.learner.status` and add many new values.** Rejected because it mixes account/workflow operability with a product onboarding state machine and changes the meaning of existing queries such as active-subject lookups.

**Remove JIT provisioning entirely.** Rejected because ADR 0001 intentionally uses JIT to eliminate dependency on optional JWT claims and client-supplied learner IDs.

## Consequences

- Implementations have an explicit product gate that cannot be bypassed by JIT `ACTIVE`.
- Existing deterministic/background consumers retain their current operational semantics unless deliberately changed.
- APIs, tests and telemetry must use `ONBOARDED` when referring to onboarding completion; `ACTIVE` is not a synonym.
- Schema needs a separate onboarding record/column outside the overloaded operational status.

## Verification

- Integration test: first authenticated `/me` contact may JIT-provision operational `ACTIVE`, but onboarding remains incomplete.
- Negative test: operational `ACTIVE` cannot access a professional endpoint that requires `ONBOARDED`.
- Regression tests: existing operational ACTIVE/SUSPENDED/CLOSED behavior remains green.
- Review artifact inventories all `core.learner.status` consumers and classifies whether each requires operational-only or combined eligibility.
