# M1-PROF-01 HLD, LLD and State Machine

## 1. High-level architecture

```text
Unauthenticated browser
  |
  | HTTPS
  v
web-ui public /register
  |
  | public registration API + pre-auth abuse controls
  v
learning-platform
  |-- dedicated Keycloak registration-admin client
  |-- PostgreSQL
  `-- Keycloak verification-email trigger
          |
          v
       SMTP provider

Verified user -> hosted Keycloak login (OIDC Authorization Code + PKCE)
                    |
                    v
               learning-platform /me/onboarding
                    |
                    +-- PostgreSQL PII/onboarding/profile/journey state
                    +-- Keycloak trusted email state reconciliation
                    `-- MobileVerificationSender -> SMS provider
```

The AI plane is not involved and receives no registration PII.

## 2. Existing identity model compatibility

ADR 0001 remains authoritative that `core.learner` is keyed by OIDC `sub` and may be provisioned just in time on first authenticated contact. Existing JIT provisioning and operational learner status are **not** onboarding authority.

Two independent state dimensions exist:

```text
core.learner.status                onboarding_state
-------------------                ----------------
ACTIVE                             IDENTITY_CREATED
SUSPENDED                          EMAIL_PENDING
CLOSED                             EMAIL_VERIFIED
                                   MOBILE_PENDING
                                   MOBILE_VERIFIED
                                   PROFILE_PENDING
                                   JOURNEY_PENDING
                                   ONBOARDED
```

`ACTIVE` answers whether an operational learner record is active for existing platform workflows. `ONBOARDED` answers whether the professional learner has completed this product onboarding. Code requiring a fully onboarded professional learner must check the latter in addition to normal operational/authz requirements. Existing consumers whose business meaning is only operational `ACTIVE` keep that meaning.

This decision is recorded in M1-ADR-012 and clarifies ADR 0001; it does not abandon OIDC-sub ownership or `/me` JIT identity anchoring.

## 3. PII boundary

`core.learner` remains PII-free. Registration/contact PII is stored in a separate least-privilege boundary keyed by learner ID/subject linkage, e.g. a repository-approved `identity.learner_contact`/equivalent table. Professional attributes are stored separately in `professional_profile`.

The PII boundary is specified in M1-ADR-013. Do not add name, email, phone, country or city to `core.learner`.

## 4. Component responsibilities

### RegistrationController
Public, narrowly scoped transport layer for registration. Applies DTO validation/correlation and runs behind a dedicated **pre-auth** abuse limiter. No generic role field.

### RegistrationService
Orchestrates Keycloak identity creation, consent evidence, RAMALS registration/contact state, idempotency and partial-failure reconciliation.

### IdentityProviderPort
Narrow Keycloak operations only: create learner identity, trigger verification email, locate by stable correlation, and read authoritative email-verification state. It does not expose arbitrary realm administration or arbitrary role assignment.

### KeycloakRegistrationAdminClient
Dedicated confidential service account for `IdentityProviderPort`. Separate from `ramals-core-workload`. Minimum effective user-management permissions only; see M1-ADR-014.

### OnboardingService
Computes next required product step from server state and performs safe trusted email reconciliation. It never treats operational `ACTIVE` as onboarding completion.

### MobileVerificationService
Authenticated service that creates/verifies/resends ownership challenges, applies SMS-specific rate/budget policy, and atomically establishes verified mobile ownership. It is not an authentication-MFA service.

### ProfessionalProfileService
Owns professional profile validation/persistence outside `core.learner`.

### LearningJourneyService
Owns product journey orchestration and the explicit compatibility projection to existing `core.learner_goal`.

## 5. Registration sequence

```text
Browser -> public /register: registration + Idempotency-Key
API -> pre-auth limiter: IP/network + privacy-safe identity fingerprint
API -> DB: create/reserve non-secret registration orchestration state
API -> Keycloak admin client: create user; server policy assigns LEARNER only
Keycloak -> API: subject / identity reference
API -> DB: link subject + contact/consent + onboarding EMAIL_PENDING
API -> Keycloak: execute/send verification email action
API -> Browser: generic accepted + nextStep=EMAIL_VERIFICATION
```

The password, when accepted by RAMALS for immediate Keycloak handoff, is transient request secret material and never enters durable retry/idempotency/logging/tracing/audit state.

If Keycloak create has an ambiguous outcome, reconcile before retry-create.

## 6. DEV/CI and production email verification

Keycloak is the verification authority and email sender/orchestrator.

- **DEV/CI normal path:** configure Keycloak SMTP to a local non-billable SMTP sink such as Mailpit. E2E obtains/follows the real Keycloak verification link and verifies Keycloak state.
- **Test-only shortcut (optional):** a Keycloak Admin API `emailVerified` mutation may exist only under an explicit test profile/configuration, guarded so startup/configuration fails in production if enabled. It cannot be the normal DEV/CI qualification path.
- **Production/production-like:** configure approved SMTP/provider through externalized environment secrets/settings and exercise the actual verification flow. Missing required configuration is fail-closed.

A RAMALS fake-email adapter cannot substitute for Keycloak email verification.

## 7. Canonical email reconciliation

1. Keycloak completes email verification.
2. Learner signs in through hosted Keycloak.
3. `/me/onboarding` derives trusted `sub` from validated token; ADR 0001 ownership semantics apply.
4. RAMALS may use trusted `email_verified` claim where the configured token contract guarantees it and freshness is sufficient.
5. Otherwise/for transition confirmation, backend reads Keycloak user state using `IdentityProviderPort` and `sub`.
6. `EMAIL_PENDING → EMAIL_VERIFIED → MOBILE_PENDING` is idempotent.
7. Browser assertions such as `emailVerified=true` are ignored/rejected as authority.

## 8. Mobile send/verify flow

Mobile send/resend/verify occurs only after authenticated sign-in and trusted email verification. `/me` identity derives from token subject; no learner ID is accepted.

Send applies subject + normalized mobile + source/network + challenge/provider budget limits. Existing `SubjectRateLimitFilter` may contribute after authentication but is not sufficient by itself.

Verification transaction:

1. Load active challenge for authenticated learner.
2. Reject expired/consumed/locked/superseded challenge.
3. Increment attempts safely.
4. Derive mandatory keyed HMAC using documented canonical bytes.
5. Constant-time compare.
6. On mismatch persist failed attempt category only.
7. On match acquire DB-enforced mobile uniqueness/reservation.
8. Consume challenge exactly once.
9. Mark mobile verified.
10. Transition to `PROFILE_PENDING`.
11. Commit.

Concurrent verification yields exactly one ownership transition.

## 9. SMS ownership verification is not Keycloak MFA

The existing Keycloak realm TOTP/OTP policy and `MfaAuthorization` remain unchanged.

RAMALS SMS verification:

- proves ownership/control of the registered mobile for the product onboarding requirement;
- does **not** set or claim `amr=otp`;
- does **not** raise `acr`;
- does **not** satisfy `MfaAuthorization`;
- does **not** inherit Keycloak password/TOTP brute-force protections.

RAMALS therefore implements its own challenge attempts, send/resend ceilings and SMS-pumping controls. See M1-ADR-015.

## 10. Onboarding state machine

```text
IDENTITY_CREATED
      |
      v
 EMAIL_PENDING -- trusted Keycloak verification --> EMAIL_VERIFIED
                                                    |
                                                    v
                                               MOBILE_PENDING
                                                    |
                                             verified SMS ownership
                                                    v
                                               MOBILE_VERIFIED
                                                    |
                                                    v
                                               PROFILE_PENDING
                                                    |
                                               valid profile
                                                    v
                                               JOURNEY_PENDING
                                                    |
                                      valid journey + goal projection
                                                    v
                                                ONBOARDED
```

Forbidden examples:

- operational `core.learner.status=ACTIVE` being interpreted as ONBOARDED;
- EMAIL_PENDING or MOBILE_PENDING → ONBOARDED;
- browser setting verification/onboarding state;
- public registration assigning `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, or `SERVICE`;
- SMS verification satisfying Keycloak MFA policy;
- profile mutation overwriting verified mobile/email without re-verification.

`REGISTRATION_STARTED` is an audit/orchestration event before identity creation, not necessarily a persistent learner onboarding enum.

## 11. Resume algorithm

`GET /me/onboarding`:

- reconcile trusted email state;
- email unverified → EMAIL_VERIFICATION;
- email verified + mobile unverified → MOBILE_VERIFICATION;
- mobile verified + profile incomplete → PROFESSIONAL_PROFILE;
- profile complete + no valid initial journey/goal projection → LEARNING_GOAL/JOURNEY;
- all gates complete → COMPLETE (`onboarding_state=ONBOARDED`).

Frontend storage is never authoritative.

## 12. LearningJourney and existing `core.learner_goal`

MVP-1 uses LearningJourney as product orchestration and retains `core.learner_goal` as the deterministic-core compatibility projection.

- one explicit primary journey/domain maps to the single current goal row;
- journey creation/update and its goal projection are committed transactionally/idempotently in the authoritative DB where feasible;
- existing GET `/me/goal` remains readable;
- existing PUT `/me/goal` must have an explicit compatibility service: for learners with a journey it updates the primary journey/projection atomically; legacy learners without a journey retain existing behavior;
- no destructive migration or silent retirement of `core.learner_goal` occurs in M1-PROF-01.

## 13. Mobile reuse/change

Verified mobile remains reserved to the owning RAMALS identity after disable/soft-delete. Reassignment requires a separate audited account-deletion/admin policy. A mobile change uses pending candidate/challenge state; the current verified number is not overwritten until proof and uniqueness succeed.

## 14. Failure/recovery matrix

- DB unavailable before Keycloak call → fail without external mutation.
- Keycloak timeout/unknown create → reconcile before retry-create.
- Keycloak created, RAMALS write failed → recover by stable external identity correlation.
- Keycloak verification-email dispatch failure → remain EMAIL_PENDING; safe resend.
- production SMTP unavailable/misconfigured → fail closed for production enablement; never mark verified.
- SMS timeout unknown outcome → bounded resend policy; do not fan out challenges.
- OTP DB commit failure → not verified; retry idempotently.
- goal projection failure → journey completion does not become ONBOARDED until consistent projection succeeds.
- admin credential unavailable → registration unavailable; existing authenticated platform paths remain isolated from the failure where possible.

## 15. Authorization and compatibility

Public surface is limited to registration/recovery operations explicitly designed as unauthenticated. Mobile/profile/journey operations are authenticated `/me` operations after appropriate gates. Never accept arbitrary learner ID.

Existing learners require an explicit backfill/compatibility policy. Do not infer incomplete onboarding merely from absence of new rows without a rollout decision. Legacy deterministic workflows continue to use their established operational status/goal semantics until deliberately migrated.
