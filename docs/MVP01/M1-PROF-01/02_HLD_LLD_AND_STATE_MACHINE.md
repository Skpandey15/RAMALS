# M1-PROF-01 HLD, LLD and State Machine

## 1. High-level architecture

```text
Browser
  |
  | HTTPS / OIDC Authorization Code + PKCE
  v
web-ui
  |
  | authenticated/public registration APIs as explicitly defined
  v
learning-platform
  |-- Keycloak Admin/identity integration (narrow service credential)
  |-- PostgreSQL (authoritative RAMALS application state)
  |-- Email verification integration / Keycloak
  `-- MobileVerificationSender
         |-- Fake/DEV sender
         `-- Production SMS adapter
```

The AI plane is not involved in registration and receives no need-to-know registration PII.

## 2. Trust boundaries

1. Internet → ingress/web-ui/API.
2. Browser → Keycloak for authentication.
3. learning-platform → Keycloak administrative identity operations.
4. learning-platform → PostgreSQL.
5. learning-platform → external messaging provider.

Every boundary requires TLS where applicable, least privilege, bounded payloads, explicit timeout and sanitized telemetry.

## 3. Component responsibilities

### RegistrationController
Thin transport layer: validation envelope, correlation IDs, request/response mapping. No identity business rules in controller.

### RegistrationService
Orchestrates account creation and lifecycle transitions. Handles idempotency and partial-failure recovery.

### IdentityProviderPort
Abstraction for Keycloak operations: create learner identity, obtain subject, request email verification, query verification status. Must not permit caller-supplied privileged roles.

### MobileVerificationService
Creates/verifies/resends OTP challenges and atomically establishes verified mobile ownership.

### MobileVerificationSender
Sends message; does not decide verification state.

### ProfessionalProfileService
Owns professional profile validation and persistence.

### LearningJourneyService
Owns journey creation/access control. Journey context does not replace evidence/mastery authority.

### OnboardingService
Computes next required onboarding step from authoritative server state.

## 4. Registration sequence

```text
Client -> learning-platform: POST registration + Idempotency-Key
learning-platform -> DB: reserve/create registration record
learning-platform -> Keycloak: create ROLE_LEARNER identity
Keycloak --> learning-platform: subject
learning-platform -> DB: persist subject + IDENTITY_CREATED/EMAIL_PENDING
learning-platform -> Keycloak: trigger verification email
learning-platform --> Client: accepted + nextStep=EMAIL_VERIFICATION
```

If Keycloak succeeds but DB persistence fails, subsequent retry/reconciliation must locate the existing identity rather than create another account.

## 5. Email verification sequence

Email ownership is verified by the IdP. RAMALS must derive/confirm verified status from a trusted IdP assertion/admin lookup, not a browser boolean. Once confirmed, transition EMAIL_PENDING → EMAIL_VERIFIED → MOBILE_PENDING.

## 6. OTP send sequence

```text
Client -> API: send OTP
API -> rate limiter: account/mobile/IP/device dimensions
API -> DB: invalidate/supersede eligible prior challenge; create challenge
API -> sender: send OTP
sender --> API: provider result/message reference
API -> audit/metrics
API --> Client: generic accepted response + resendAfter/expiresAt
```

Never return OTP.

## 7. OTP verify sequence

Within transaction/locking semantics appropriate to PostgreSQL:

1. Load active challenge by opaque challenge ID and learner context.
2. Verify not expired/consumed/locked.
3. Increment attempts safely.
4. Constant-time compare submitted OTP-derived hash against stored hash.
5. On mismatch, persist failed attempt and return generic failure.
6. On match, acquire/validate verified-mobile uniqueness.
7. Mark challenge consumed.
8. Mark mobile verified and timestamped.
9. Transition to PROFILE_PENDING.
10. Commit.

Concurrent verify requests must yield exactly one successful ownership transition.

## 8. Resend semantics

Resend is not unlimited. Enforce cooldown, rolling-window send ceilings, and risk/rate limits. A new challenge supersedes prior active challenges for the same learner/mobile. Old OTPs must fail after resend.

## 9. Onboarding state machine

```text
REGISTRATION_STARTED
   |
   v
IDENTITY_CREATED
   |
   v
EMAIL_PENDING --trusted verification--> EMAIL_VERIFIED
                                      |
                                      v
                                 MOBILE_PENDING
                                      |
                                verified OTP
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
                              >=1 valid journey
                                      v
                                    ACTIVE
```

Forbidden examples:

- EMAIL_PENDING → ACTIVE
- MOBILE_PENDING → ACTIVE
- Browser request directly setting ACTIVE
- Self-registration assigning ADMIN/MENTOR/CONTENT_AUTHOR
- Professional-profile update modifying verified identity attributes without dedicated re-verification flow

## 10. Resume algorithm

`GET /me/onboarding` should calculate the next step from trusted state:

- email unverified → EMAIL_VERIFICATION
- email verified + mobile unverified → MOBILE_VERIFICATION
- verified + profile incomplete → PROFESSIONAL_PROFILE
- profile complete + no valid journey → LEARNING_GOAL/JOURNEY
- all gates complete → COMPLETE

Frontend localStorage must not be authoritative for progress.

## 11. Mobile change after activation

Changing a verified mobile is a separate re-verification operation. Do not overwrite the verified number immediately. Store pending candidate/challenge; only switch verified ownership after successful verification and uniqueness check.

## 12. Email change after activation

Delegate credential/identity semantics to Keycloak and require re-verification. RAMALS must resynchronize trusted identity claims/state rather than accepting an arbitrary profile email update.

## 13. Deletion/disable considerations

Account deletion and retention are broader lifecycle capabilities, but this feature must avoid schema choices that make deletion impossible. Mobile uniqueness policy must define behavior for disabled/deleted identities; production policy should be explicit before reuse of a formerly verified number.

## 14. Failure matrix

- DB unavailable before Keycloak call: fail, no external mutation.
- Keycloak timeout with unknown outcome: query/reconcile before retry-create.
- Keycloak created, DB write failed: recover by external identity correlation.
- Email dispatch failure: remain pending; safe resend.
- SMS timeout unknown outcome: do not generate multiple uncontrolled challenges; safe resend policy.
- OTP DB commit failure: verification not considered complete; retry idempotently.
- Profile save timeout: idempotent PUT/upsert semantics.
- Journey creation retry: Idempotency-Key prevents duplicate journey.

## 15. Authorization

Public endpoints are narrowly limited to registration/verification initiation where required. Authenticated onboarding APIs derive learner identity from token subject. Never accept arbitrary learnerId for `/me` mutations. Privileged role assignment is not present in public request DTOs.

## 16. Compatibility

Existing learners must continue to authenticate. Define a migration/backfill rule for legacy learners without the new onboarding state so rollout does not lock them out unexpectedly. Prefer an explicit compatibility decision rather than inferring incomplete registration from NULL fields.