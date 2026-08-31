# M1-ADR-017: Administrative APIs use explicit permissions, step-up controls, and a separate Keycloak workload identity

- **Status:** Accepted
- **Date:** 2026-08-31
- **Relates to:** M1-ADR-014, M1-ADR-016, Zero Trust administrative operations
- **Required before:** Administrative backend integration

## Context

M1-ADR-016 establishes that the browser is not an authorization authority and that privileged RAMALS personas fail closed. Adding learner administration, identity administration, audit visibility, operational data, and content controls introduces a second question: how the backend itself obtains authority to perform identity-plane mutations in Keycloak.

The existing `ramals-registration-admin` service account is deliberately dedicated to the public professional-registration workflow. Reusing it for interactive staff administration would collapse two trust purposes into one credential, make registration compromise sufficient to perform unrelated staff administration, and weaken attribution even though both flows currently need user-management APIs.

Administrative reads and writes also do not have equal impact. Reading operational summaries or audit evidence is privileged, but changing a learner lifecycle state, disabling an identity, or changing a staff role is a higher-impact command that must require stronger authentication.

## Decision

1. Every `/api/v1/admin/**` operation enforces backend authorization. React routing is never authorization evidence.
2. Administrative reads require an authenticated `ADMIN` authority. Sensitive mutations additionally require backend-verified MFA/step-up evidence using the established `amr`/`acr` policy.
3. Learner lifecycle mutations are restricted to the explicit server-owned state model. `CLOSED` is terminal for this administrative surface; reopening requires a separately designed recovery workflow.
4. Identity administration is intentionally narrow. The interactive surface may enable/disable ordinary staff identities and assign/remove only `INSTRUCTOR` and `CONTENT_AUTHOR`.
5. `ADMIN` and `SERVICE` identity mutation remains out-of-band. A learner identity cannot be promoted into a staff persona, and an administrator cannot mutate its own identity through the same authenticated session.
6. RAMALS uses a dedicated confidential Keycloak service account, `ramals-identity-admin`, for interactive administrative identity operations. It is separate from `ramals-registration-admin`, `ramals-core-workload`, `ramals-api`, and `ramals-web-ui`.
7. The identity-admin service account receives only the effective user-management permissions required by the implemented API. The upper bound is `manage-users` plus `view-users`; `manage-realm`, client administration, and unrelated realm privileges are prohibited.
8. Service-account credentials stay server-side and are supplied by environment secret management. They are never returned to or stored by the browser.
9. Administrative mutations append correlation-aware audit evidence. Security denials remain in the security audit stream. Audit tables remain append-only.
10. Operational and audit reads are bounded. UI behavior must degrade without granting broader permissions if an identity-provider dependency is unavailable.

## Alternatives considered

**Reuse `ramals-registration-admin`.** Rejected. Similar Keycloak permissions do not make the trust purposes equivalent; independent credentials preserve blast-radius containment, revocation, rotation, and attribution.

**Call Keycloak Admin REST directly from React.** Rejected. It would expose administrative credentials/capabilities to a user-controlled execution environment and turn UI code into a security boundary.

**Grant ADMIN all identity roles including ADMIN and SERVICE.** Rejected for MVP-1. Privileged-role administration requires stronger separation-of-duties and recovery controls than this product surface currently provides.

**Require MFA for every read.** Not selected. ADMIN is mandatory for all reads; step-up is applied to state-changing operations where the impact justifies it. This can be tightened later without weakening the current write boundary.

## Consequences

- Deployment configuration gains a separate identity-admin client id/secret lifecycle.
- Registration remains independently revocable and cannot be used as the credential for interactive staff administration.
- The admin dashboard can expose real learner/content/operations/audit data while backend authorization remains authoritative.
- Privileged identity collisions and role escalation attempts fail closed rather than being resolved by UI ordering.
- Keycloak or identity-admin credential failure degrades identity administration; it does not authorize a fallback path.

## Verification

- API tests prove learner/unsupported identities cannot access administrative endpoints.
- Mutation tests prove ADMIN without MFA is denied and ADMIN with accepted MFA evidence can invoke the allowed command.
- Unit tests prove self-mutation, learner-to-staff conversion, ADMIN/SERVICE target mutation, and assignment of unapproved roles are rejected.
- Keycloak realm/deployment configuration proves `ramals-identity-admin` is distinct from the registration workload identity and receives no `manage-realm` privilege.
- Secret scanning proves no identity-admin secret is committed.
- Frontend tests prove the dashboard consumes authenticated backend contracts and does not create a browser-side authorization path.
