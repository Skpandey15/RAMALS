# M1-PROF-01 Implementation Master Plan

## 1. Objective

Deliver production-grade professional learner self-registration and onboarding while preserving RAMALS identity, security, deterministic-learning, and deployment invariants.

## 2. In scope

- Login-page `Create Account` entry point.
- Learner-only public registration.
- First/last name, email, mandatory mobile, country, optional city, password/confirmation, Terms acceptance.
- Keycloak identity creation and email verification.
- Mandatory mobile OTP verification.
- Professional profile.
- Learning goals and initial learning journey.
- Interrupted-onboarding resume.
- Audit, tracing, metrics, abuse protection, reliability and qualification.
- DEV/CI fake email/SMS-compatible verification path and production provider abstraction.

## 3. Out of scope

School/college learners, parents, institution administration, billing, subscription/payment, mastery algorithm redesign, Contract B changes, AWS redesign, T15 semantic changes, and production SMS vendor procurement unless separately approved.

## 4. Ownership boundaries

### Keycloak

Owns credentials, password policy, authentication, password reset, email-verification identity state, sessions, token issuance, and authoritative subject (`sub`).

### learning-platform

Owns registration orchestration, RAMALS learner identity linkage, mobile verification workflow, professional profile, learning journey, authorization, audit events, idempotency, reconciliation, and state transitions.

### PostgreSQL

Owns durable RAMALS application state. Passwords and plaintext OTPs are prohibited.

### web-ui

Owns presentation and user interaction only. It must not assert verification or activation state.

### SMS/email adapters

External effects are behind ports/interfaces. Local DEV/CI uses deterministic non-billable adapters; production adapters use externalized secrets and bounded timeouts.

## 4.1 Registration credential-handling decision

MVP-1 uses RAMALS-orchestrated registration against Keycloak unless implementation discovery proves a Keycloak-native registration flow can satisfy the same RAMALS orchestration, Terms, mobile-verification and onboarding requirements without weakening UX or security. This is an explicit architecture decision, not something left to implementation preference.

If `learning-platform` receives a plaintext password in the registration request, all of the following are mandatory:

- the password exists only in request memory for the minimum time required to hand it to Keycloak;
- it is never persisted in PostgreSQL, cache, idempotency storage, retry/outbox payloads, audit records, traces, metrics or logs;
- request-body logging/tracing is disabled or redacted for registration endpoints;
- registration retries must not depend on recovering the original password from durable state;
- after an ambiguous Keycloak outcome, reconciliation queries identity state before any create retry;
- application code must not perform its own password hashing as a substitute for Keycloak ownership.

If discovery chooses a Keycloak-native registration page instead, that choice must be documented in the implementation mapping and RAMALS must still enforce mandatory mobile verification and onboarding before ACTIVE state.

## 5. Delivery phases

### Phase A — repository discovery and compatibility

Before coding, inventory existing user/learner entities, Keycloak realm/client/roles, Flyway migrations, API conventions, tracing, audit infrastructure, rate limiting, error schema, frontend routing/state, tests, and local deployment configuration. Reuse existing abstractions; do not create parallel identity models.

Exit: written implementation mapping from this design to concrete existing classes/files, including the final registration credential path.

### Phase B — identity and registration foundation

Implement learner-only registration orchestration, identity linkage by Keycloak `sub`, lifecycle state, Terms evidence, uniqueness policy, safe error mapping, idempotency, and canonical email-verification reconciliation.

Exit: account can be registered without privileged-role injection; email verification is required.

### Phase C — mobile verification

Implement E.164 normalization, challenge creation, CSPRNG OTP, mandatory keyed HMAC verification storage, TTL, verify, resend, attempt ceilings, rate limits, replay prevention, one-time consumption, verified-mobile uniqueness, audit and provider abstraction.

Exit: mobile verification cannot be bypassed and is safe under retries/concurrency.

### Phase D — professional onboarding

Implement professional profile and learning-goal capture. Fields include current role, experience band, primary expertise, technologies known, declared skill level, goal, target role, selected learning domains, intensity and weekly availability.

Exit: verified learner can persist/resume profile and goals.

### Phase E — learning journey

Create initial professional LearningJourney with stable identity, lifecycle, selected domains and goal context. Kafka must be optional, not default. Existing diagnostic/evidence/mastery behavior consumes journey context later without changing mastery authority.

Exit: onboarding completion produces an ACTIVE learner with at least one valid journey.

### Phase F — frontend UX

Implement Login → Create Account → Email Verification → Mobile Verification → Professional Profile → Learning Goal → Review/Start flow. Persist server-side progress and resume after refresh/login. Include accessible validation, pending states, resend countdown and non-sensitive errors.

### Phase G — hardening

Complete threat-model controls, rate limits, concurrency tests, provider failures, idempotency/reconciliation, PII-safe logs, metrics, alerts, performance tests, migration safety and runbook.

### Phase H — qualification

Execute `05_TESTING_PERFORMANCE_OBSERVABILITY_AND_QUALIFICATION.md`. No closure on unit tests alone.

## 6. Registration lifecycle

Recommended application lifecycle:

`REGISTRATION_STARTED → IDENTITY_CREATED → EMAIL_PENDING → EMAIL_VERIFIED → MOBILE_PENDING → MOBILE_VERIFIED → PROFILE_PENDING → JOURNEY_PENDING → ACTIVE`

Terminal/exception states should be introduced only where operationally useful (for example DISABLED or REGISTRATION_FAILED). Do not encode every transient provider failure as a permanent account state.

Transitions are server-controlled and guarded. Repeating a completed transition must be idempotent or return a deterministic conflict/current-state response.

## 7. Distributed consistency

There is no distributed transaction across Keycloak, PostgreSQL, email and SMS. Design for partial failure.

Required principles:

- Generate a stable registration operation/idempotency key.
- Persist sufficient non-secret state to resume/reconcile.
- Keycloak creation must be discoverable/reconcilable by authoritative subject/external identity reference.
- Do not create duplicate Keycloak identities after client timeout/retry.
- OTP verification and mobile ownership update occur transactionally in RAMALS where feasible.
- External provider calls use bounded timeout and explicit retry policy; no unbounded synchronous retry.
- Retry only operations known to be safe/idempotent.
- Reconciliation must never silently grant ACTIVE status.

## 8. Migration strategy

Use additive Flyway migrations. Prefer expand-first changes. Existing learners must remain usable. New NOT NULL columns require safe defaults/backfill strategy or staged constraints. Destructive migration is prohibited in the feature PR.

Rollback is application rollback plus forward-compatible schema, not database down-migration in production.

## 9. Feature/configuration strategy

If needed, gate public registration with explicit configuration so rollout can be disabled operationally without code rollback. DEV/CI fake SMS must never be accidentally selected in production; production profile must fail closed when mandatory provider configuration is absent.

## 10. CI/CD gates

Required gates: backend compile/unit/integration, frontend lint/type/test/build, migration validation, security/static analysis already required by repository, API compatibility checks where available, secret scanning, and focused registration E2E tests.

## 11. Operational readiness

Before production enablement define dashboards/alerts for registration success/failure, OTP sends, OTP verify success/failure, rate-limit rejects, provider latency/error rate, Keycloak failures, onboarding funnel, duplicate conflicts and reconciliation backlog.

## 12. Rollout

Recommended staged rollout: disabled-by-default configuration → internal/dev qualification → controlled non-production → limited production cohort → general availability. Rollback switch disables new registrations without invalidating already-created learners.

## 13. Mandatory implementation PR sequence

M1-PROF-01 implementation MUST be split into reviewable sequential PRs unless an explicit architecture review approves a different decomposition before coding:

1. **PR-A — schema + identity foundation**: additive migrations, Keycloak linkage, learner-only registration, credential handling, lifecycle, canonical email-verification reconciliation, Terms evidence, idempotency/recovery foundations.
2. **PR-B — mobile verification**: phone normalization, OTP challenge model, mandatory HMAC construction, rate limits, provider abstraction, verified-mobile uniqueness/reuse policy, concurrency and negative tests.
3. **PR-C — professional onboarding + journey + UI**: profile, goals, domains, journey APIs, Create Account/onboarding UX, server-driven resume; Kafka not default.
4. **PR-D — hardening + E2E qualification**: failure injection, abuse/load/concurrency, telemetry, runbook, production-provider qualification and final evidence.

Each PR must leave main buildable/testable and must not temporarily weaken security or bypass verification gates. Do not combine all identity, OTP, database, UI and qualification work into one large implementation PR.

## 14. Completion criteria

Implementation is not complete until security, reliability, concurrency, performance, observability and end-to-end qualification gates pass. Any production provider not exercised in a production-like environment must be reported `NOT VERIFIED`.