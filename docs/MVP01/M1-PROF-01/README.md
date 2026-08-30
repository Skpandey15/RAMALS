# M1-PROF-01 — Professional Learner Registration & Onboarding

Status: DESIGN / IMPLEMENTATION PLAN
MVP: MVP-1
Scope: Professional learners only
Production-grade target: Yes

## Purpose

M1-PROF-01 establishes the production-grade identity, registration, verification, professional-profile, and first-learning-journey boundary for RAMALS professional learners.

This package is intentionally implementation-oriented so Claude or Codex can code from it without inventing architecture. It is subordinate to accepted repository invariants and explicitly reconciles the existing OIDC-subject JIT learner model, PII-free `core.learner`, operational learner status, Keycloak MFA, `core.learner_goal`, security filter chain, and workload-identity decisions already on `main`.

## Product outcome

A new professional learner can:

1. Open the RAMALS public registration entry point (`/register`) while hosted Keycloak remains the sign-in UI.
2. Register with first name, last name, email, mandatory mobile, country, password, and Terms/Privacy acceptance.
3. Verify email through Keycloak.
4. Sign in through hosted Keycloak and resume authoritative onboarding.
5. Verify the mandatory mobile using RAMALS SMS ownership verification.
6. Complete a professional profile.
7. Define learning goals, target role, selected domains, intensity, and weekly availability.
8. Create the initial learning journey.
9. Resume onboarding after interruption.
10. Reach onboarding state `ONBOARDED` only after all required gates pass.

`core.learner.status=ACTIVE` is an operational account/workflow status and **does not mean onboarding is complete**. Professional product eligibility requires the applicable authorization/ownership checks plus the onboarding gate defined by this package.

Kafka is one professional domain only; it must not be the global/default platform identity.

## Architecture invariants

- Keycloak remains authoritative for authentication credentials, login, sessions and email-verification identity state.
- OIDC `sub` remains the immutable external learner identity anchor; `/me` derives ownership from the authenticated subject.
- Existing JIT provisioning may create/retain an operationally `ACTIVE` `core.learner`, but JIT provisioning never sets or implies `ONBOARDED`.
- `core.learner` remains PII-free. Registration/contact PII is stored only in a separate least-privilege PII boundary; professional attributes remain in the professional-profile boundary.
- RAMALS never stores plaintext passwords; if RAMALS receives a registration password, it is transient secret material only and is never logged, traced, cached, audited, persisted, queued or placed in idempotency storage.
- Public self-registration grants only the actual Keycloak realm role `LEARNER`. Public inputs can never assign `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, or `SERVICE`.
- RAMALS-orchestrated user creation uses a dedicated Keycloak administrative client, separate from `ramals-core-workload`, with minimum required user-management privileges and externalized credentials.
- Mobile verification is mandatory for the professional onboarding path.
- Email verification is mandatory and RAMALS reconciles it only from trusted Keycloak state/claims, never a browser boolean.
- Keycloak TOTP/MFA and RAMALS SMS mobile ownership verification are separate mechanisms. SMS verification does not satisfy `MfaAuthorization`, change `amr`, or increase `acr`.
- The server, never the browser, decides onboarding/verification state transitions.
- Self-declared skill level is profile input, not authoritative mastery evidence.
- The deterministic evidence/mastery engine remains authoritative and is not redesigned by this capability.
- OTPs are never logged or persisted in plaintext and persisted verification state uses the mandatory keyed HMAC construction defined in the security design.
- Mobile numbers are normalized to E.164.
- One verified mobile number maps to one RAMALS learner identity for MVP-1 and remains reserved after disable/soft-delete unless explicitly released through a separate audited policy.
- Terms/Privacy acceptance records an immutable server-known document/version reference and timestamp, not only a boolean.
- Keycloak, not a RAMALS fake email adapter, owns verification mail. DEV/CI uses a non-billable local SMTP sink such as Mailpit wired to Keycloak; production qualification uses the configured production SMTP/provider and fails closed when absent.
- SMS abuse protection includes a pre-auth registration limiter plus authenticated subject/mobile/challenge/provider-budget controls; the existing bearer-subject limiter alone is insufficient.
- Existing `core.learner_goal` remains the deterministic-core compatibility projection during MVP-1; LearningJourney does not silently replace it.
- No paid SMS provider is required for local DEV/CI; production must fail closed if the production sender is not configured.
- Implementation is delivered through the mandatory staged PR sequence defined in the master plan/checklist rather than one oversized PR.

## ADRs

The architecture decisions for this capability are recorded under `docs/adr/`:

- `M1-ADR-012` — learner JIT provisioning and onboarding-state separation.
- `M1-ADR-013` — professional learner PII storage boundary.
- `M1-ADR-014` — dedicated Keycloak administrative client for learner registration.
- `M1-ADR-015` — professional registration, email/mobile verification and MFA boundary.

## Documents

1. `01_IMPLEMENTATION_MASTER_PLAN.md` — scope, phases, work breakdown, dependencies, delivery sequencing, rollback.
2. `02_HLD_LLD_AND_STATE_MACHINE.md` — component design, boundaries, flows, state machine, failure/recovery semantics.
3. `03_DATA_MODEL_AND_API_CONTRACTS.md` — database design, constraints, API contracts, idempotency, validation.
4. `04_SECURITY_PRIVACY_OTP_AND_THREAT_MODEL.md` — OTP security, abuse controls, PII protection, Keycloak rules, threats.
5. `05_TESTING_PERFORMANCE_OBSERVABILITY_AND_QUALIFICATION.md` — test matrix, performance targets, telemetry, qualification gates.
6. `06_IMPLEMENTATION_EXECUTION_CHECKLIST.md` — concrete coding sequence and PR acceptance checklist for Claude/Codex.

## Definition of Done

M1-PROF-01 is CLOSED only when the qualification matrix passes, including Keycloak-controlled email verification, mandatory mobile OTP verification, learner-only self-registration, JIT/onboarding bypass negative controls, interrupted-onboarding recovery, duplicate/race handling, security/abuse controls, end-to-end auditability, production configuration separation, `core.learner_goal` compatibility, and no regression of existing deterministic learning behavior.
