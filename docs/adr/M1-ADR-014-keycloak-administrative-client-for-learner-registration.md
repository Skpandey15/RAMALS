# M1-ADR-014: Learner registration uses a dedicated least-privilege Keycloak administrative client

- **Status:** Accepted
- **Date:** 2026-08-30
- **Relates to:** M1-ADR-003, M1-PROF-01
- **Required before:** M1-PROF-01 PR-A

## Context

RAMALS-orchestrated registration requires server-to-server Keycloak operations such as creating a user, locating/reconciling that user, reading authoritative email-verification state, triggering verification actions, and assigning the allowed learner role. The repository's existing `ramals-core-workload` identity has a different trust purpose: Spring authenticates to `ramals-ai`. Reusing it would collapse trust domains and either fail due to missing realm-management rights or broaden a workload credential into an identity-administration credential.

A Keycloak administrative client is high impact: compromise can create or modify users. This privilege therefore needs its own identity, secret lifecycle, audit and narrow API surface.

## Decision

1. Introduce a dedicated confidential service-account client for registration administration, expected name `ramals-registration-admin` unless repository conventions require an equivalent name.
2. Do **not** reuse `ramals-core-workload`, `ramals-api`, or `ramals-web-ui`.
3. Grant only the minimum effective realm-management user permissions required by the implemented flow. The expected upper bound is `manage-users` plus `view-users`; implementation must verify whether a narrower effective set is possible. `manage-realm` and unrelated administration are prohibited.
4. Public registration cannot pass realm/client roles to a generic admin method. `LEARNER` assignment is a narrow server-side policy/operation. The public API must not be capable of assigning `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, or `SERVICE`.
5. The client secret is supplied by environment secret management, never committed in realm files/source, and must support independent rotation/revocation.
6. Network access from `learning-platform` to Keycloak is restricted to the required path where deployment controls allow it.
7. Keycloak administrative events and RAMALS sanitized audit records are used to detect unexpected administrative operations/role assignment without recording secrets or passwords.
8. Registration/admin-client failure degrades new registration rather than weakening verification/authorization or changing existing AI workload identity.

This ADR complements M1-ADR-003: both use dedicated workload identities, but their audiences, privileges and trust purposes remain separate.

## Alternatives considered

**Reuse ramals-core-workload.** Rejected because Spring→AI authentication and Keycloak realm administration are different privilege domains. Combining them violates least privilege and increases compromise blast radius.

**Use a human Keycloak administrator credential.** Rejected because human credentials have broad privileges, poor machine rotation semantics and weak service attribution.

**Enable Keycloak self-registration instead.** Not selected for MVP-1 because RAMALS must orchestrate immutable consent evidence, mobile requirement, application state/idempotency and recovery. A future architecture may reconsider this only through a reviewed ADR.

## Consequences

- Environment/bootstrap configuration gains a new confidential client/service-account definition but no committed secret.
- Production startup/feature enablement must fail closed if the required admin credential is absent or invalid.
- Security review must verify the effective Keycloak permissions, not merely client-name intent.
- Monitoring treats anomalous admin-client use or privileged role assignment as an identity-plane security signal.

## Verification

- Integration test proves registration admin client can perform only required user operations.
- Negative test proves `manage-realm`/unrelated realm administration is unavailable.
- Negative registration tests attempt `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, and `SERVICE` and result only in the permitted `LEARNER` role or safe rejection.
- Secret scan confirms no admin client secret in Git/logs/traces.
- Existing M1-ADR-003 Spring→ramals-ai workload identity tests remain unchanged and green.
