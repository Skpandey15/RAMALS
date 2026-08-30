# M1-ADR-015: Professional registration uses RAMALS orchestration, Keycloak email verification, and separate SMS ownership proof

- **Status:** Accepted
- **Date:** 2026-08-30
- **Relates to:** ADR 0001, M1-ADR-012, M1-ADR-014, M1-PROF-01
- **Required before:** M1-PROF-01 PR-A/PR-B

## Context

The current web application signs users in by redirecting to Keycloak's hosted login. The realm already has authentication OTP/TOTP and brute-force policy, while M1-PROF-01 needs a public professional registration flow, mandatory email verification, and mandatory mobile ownership verification. Treating these as one mechanism creates several hazards: enabling an undocumented Keycloak self-registration path, pretending a RAMALS email adapter can verify Keycloak state, or allowing a product SMS OTP to satisfy authentication MFA.

The current authenticated subject rate limiter also cannot protect an unauthenticated registration endpoint, and a subject-only limiter cannot prevent per-mobile SMS pumping.

## Decision

1. MVP-1 professional self-registration is **RAMALS-orchestrated** through a narrowly exposed public registration API/page. Hosted Keycloak remains the sign-in UI.
2. Keycloak native self-registration remains disabled for this capability. Do not use `keycloak.login({action:'register'})` or depend on `registrationAllowed` unless a future reviewed ADR changes this decision.
3. The dedicated client from M1-ADR-014 creates the Keycloak learner identity; public registration can produce only realm role `LEARNER`.
4. Keycloak is authoritative for email verification. RAMALS triggers the Keycloak verification action and later reconciles trusted Keycloak state/claim; browser `emailVerified` assertions are never authoritative.
5. DEV/CI normal email qualification configures Keycloak SMTP to a local non-billable sink such as Mailpit and follows the actual verification link. Any Admin-API shortcut is test-profile-only and must be impossible in production. Production/production-like uses and qualifies approved SMTP/provider configuration and fails closed when required configuration is missing.
6. After email verification, the learner signs in through hosted Keycloak. Mobile send/resend/verify are authenticated `/me` operations bound to token `sub`.
7. RAMALS SMS OTP proves **mobile ownership for onboarding only**. It does not set/claim `amr=otp`, raise `acr`, satisfy `MfaAuthorization`, change Keycloak TOTP policy, or inherit Keycloak brute-force protection.
8. Public registration has a dedicated pre-auth limiter before bearer-subject-dependent enforcement. SMS has additional subject + normalized-mobile/challenge + source + provider/global-budget limits. Production multi-replica enforcement must be shared/consistent; a per-pod counter alone is insufficient.
9. Verified mobile ownership is DB-enforced and remains reserved to the same learner after disable/soft-delete unless a separate audited lifecycle policy releases it.

## Alternatives considered

**Keycloak native self-registration for MVP-1.** Rejected because current product orchestration needs RAMALS-owned consent evidence, application idempotency/recovery, mandatory mobile onboarding and explicit PII boundaries. It may be revisited later with equivalent guarantees.

**RAMALS fake email verification.** Rejected because RAMALS cannot authoritatively mutate/replace Keycloak email-verification semantics through a local email adapter.

**Treat SMS OTP as MFA.** Rejected because mobile ownership proof is not an authentication assurance event and must not be laundered into `amr`/`acr` or existing MFA-protected authorization.

**Rely on current subject/IP limiter.** Rejected because registration is unauthenticated and SMS pumping requires mobile/provider-budget dimensions.

## Consequences

- UX uses a RAMALS public registration entry point followed by Keycloak email verification and hosted sign-in, rather than claiming RAMALS owns the login page.
- Local deployment/CI needs a Keycloak-compatible local SMTP sink for realistic email verification.
- Mobile verification endpoints move behind authentication and trusted email prerequisite.
- The platform maintains two intentionally separate OTP concepts: Keycloak authentication MFA/TOTP and RAMALS SMS ownership challenge.
- Rate-limit architecture gains both pre-auth and SMS-specific shared controls.

## Verification

- E2E receives and follows a Keycloak verification email in DEV/CI and reconciles verified state after hosted login.
- Browser-supplied verification flags cannot advance onboarding.
- SMS success does not satisfy an endpoint protected by existing `MfaAuthorization` unless the token independently meets Keycloak MFA requirements.
- Pre-auth registration and mobile/provider-budget abuse tests reject configured bursts, including across application replicas.
- Production startup/registration enablement rejects test-only email shortcuts and missing required production email/SMS configuration.
