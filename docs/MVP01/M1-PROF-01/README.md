# M1-PROF-01 — Professional Learner Registration & Onboarding

Status: DESIGN / IMPLEMENTATION PLAN
MVP: MVP-1
Scope: Professional learners only
Production-grade target: Yes

## Purpose

M1-PROF-01 establishes the production-grade identity, registration, verification, professional-profile, and first-learning-journey boundary for RAMALS professional learners.

This package is intentionally implementation-oriented so Claude or Codex can code from it without inventing architecture.

## Product outcome

A new professional learner can:

1. Discover `Create Account` from the login page.
2. Register with first name, last name, email, mandatory mobile, country, password, and Terms acceptance.
3. Verify email.
4. Verify the mandatory mobile using OTP.
5. Complete a professional profile.
6. Define learning goals, target role, selected domains, intensity, and weekly availability.
7. Create the initial learning journey.
8. Resume onboarding after interruption.
9. Reach ACTIVE state only after required verification and onboarding gates pass.

Kafka is one professional domain only; it must not be the global/default platform identity.

## Architecture invariants

- Keycloak remains authoritative for authentication credentials and login.
- RAMALS never stores plaintext passwords.
- Public self-registration grants only the learner role.
- Mobile verification is mandatory.
- Email verification is mandatory.
- The server, never the browser, decides onboarding/verification state transitions.
- Self-declared skill level is profile input, not authoritative mastery evidence.
- The deterministic evidence/mastery engine remains authoritative and is not redesigned by this capability.
- OTPs are never logged or persisted in plaintext.
- Mobile numbers are normalized to E.164.
- One verified mobile number maps to one active learner identity for MVP-1.
- No paid SMS provider is required for local DEV/CI.

## Documents

1. `01_IMPLEMENTATION_MASTER_PLAN.md` — scope, phases, work breakdown, dependencies, delivery sequencing, rollback.
2. `02_HLD_LLD_AND_STATE_MACHINE.md` — component design, boundaries, flows, state machine, failure/recovery semantics.
3. `03_DATA_MODEL_AND_API_CONTRACTS.md` — database design, constraints, API contracts, idempotency, validation.
4. `04_SECURITY_PRIVACY_OTP_AND_THREAT_MODEL.md` — OTP security, abuse controls, PII protection, Keycloak rules, threats.
5. `05_TESTING_PERFORMANCE_OBSERVABILITY_AND_QUALIFICATION.md` — test matrix, performance targets, telemetry, qualification gates.
6. `06_IMPLEMENTATION_EXECUTION_CHECKLIST.md` — concrete coding sequence and PR acceptance checklist for Claude/Codex.

## Definition of Done

M1-PROF-01 is CLOSED only when the qualification matrix passes, including real email verification, mandatory mobile OTP verification, learner-only self-registration, interrupted-onboarding recovery, duplicate/race handling, security/abuse controls, end-to-end auditability, production configuration separation, and no regression of existing deterministic learning behavior.
