# M1-PROF-01 Implementation Master Plan

## 1. Objective

Deliver production-grade professional learner self-registration and onboarding while preserving RAMALS identity, Zero Trust, deterministic-learning, migration, and deployment invariants already present on `main`.

Professional is the current implementation beachhead, not the lifetime RAMALS product boundary. See `docs/product/RAMALS_PRODUCT_VISION_AND_SEGMENT_ARCHITECTURE.md`. This plan does not authorize implementation of School, Higher Education, guardian or minor-specific machinery.

## 2. In scope

- RAMALS public `/register` entry point; hosted Keycloak remains the sign-in UI for MVP-1.
- Learner-only public registration.
- First/last name, email, mandatory mobile, country, optional city, password/confirmation, Terms/Privacy acceptance.
- Optional policy-driven adult self-attestation for the professional launch without DOB collection solely for future use.
- Keycloak identity creation and email verification.
- Mandatory authenticated mobile ownership verification after email verification/sign-in.
- Professional profile.
- Learning goals and initial learning journey with compatibility to existing `core.learner_goal`.
- Interrupted-onboarding resume.
- Audit, tracing, metrics, pre-auth and post-auth abuse protection, reliability and qualification.
- Local DEV/CI Keycloak SMTP sink and fake/non-billable SMS path; production provider qualification.

## 3. Out of scope

School/Higher Education implementation, parents/guardians, minor-specific consent or age-proofing machinery, institution administration, billing, subscription/payment, mastery algorithm redesign, Contract B changes, AWS redesign, T15 semantic changes, Keycloak MFA redesign, retirement of `core.learner_goal`, or production SMS vendor procurement unless separately approved.

## 4. Existing-main invariants this plan MUST preserve

### 4.1 OIDC subject and JIT learner provisioning

ADR 0001 anchors learner identity to OIDC `sub` and provisions `core.learner` just in time on first authenticated contact. The current learner row has an operational status whose existing values/consumers must remain compatible.

M1-PROF-01 therefore **does not redefine `core.learner.status` as onboarding state**. Existing JIT provisioning may continue to create an operationally `ACTIVE` learner, but that fact does not imply email/mobile/profile/journey completion. A separate onboarding state terminates in `ONBOARDED`.

Professional product eligibility is evaluated from both dimensions where relevant:

`operational status allows work` **AND** `onboarding_state = ONBOARDED` **AND** normal authorization/resource-ownership policy passes.

Existing background/deterministic consumers that intentionally use operational `ACTIVE` must be inventoried. Do not silently change all `ACTIVE` checks into onboarding checks.

### 4.2 PII-free core learner

The existing `core.learner` no-PII baseline remains authoritative. Names, email, mobile, country and city MUST NOT be added to `core.learner`. M1-PROF-01 introduces/reuses a separate least-privilege contact/registration PII boundary keyed by learner identity. Professional attributes remain in a separate professional-profile model.

Do not collect date of birth merely to prepare for future School/Higher Education segments. If the professional product requires adult assurance, prefer explicit self-attestation with server-known statement/version and timestamp evidence unless legal/product review requires stronger proof.

### 4.3 Existing learner goal

`core.learner_goal` and its existing `/me/goal` contract remain supported in MVP-1. LearningJourney is a product orchestration model, not a silent replacement for the deterministic-core goal. One explicit primary journey/domain is projected into `core.learner_goal`; compatibility behavior is defined in Doc 03.

### 4.4 Existing authentication and MFA

Hosted Keycloak login remains the sign-in mechanism. Existing Keycloak TOTP/MFA policies and `MfaAuthorization` are unchanged. RAMALS SMS OTP proves mobile ownership only and does not satisfy authentication MFA assurance.

## 5. Ownership boundaries

### Keycloak

Owns credentials, password policy, hosted sign-in, password reset, email verification, sessions, token issuance, authoritative subject (`sub`), and existing authentication MFA/TOTP policy.

### learning-platform

Owns registration orchestration, RAMALS learner linkage, onboarding state, mobile ownership verification, professional profile, LearningJourney, `core.learner_goal` compatibility projection, authorization, audit, idempotency and reconciliation.

### PostgreSQL

Owns durable RAMALS application state. `core.learner` remains PII-free. Passwords and plaintext OTPs are prohibited.

### web-ui

Owns presentation and interaction only. It exposes a public registration route/CTA; it does not replace the hosted Keycloak login page and does not assert verification/onboarding state.

### Keycloak email delivery

Keycloak owns verification email generation and state transition. DEV/CI configures Keycloak SMTP to a local non-billable sink (Mailpit or equivalent). Production uses approved SMTP/provider configuration. A RAMALS fake email sender is not an email-verification authority.

### SMS adapter

RAMALS uses a `MobileVerificationSender` port. DEV/CI may use deterministic non-billable SMS behavior. Production uses externalized credentials, bounded timeout and explicit provider qualification.

## 6. Registration credential and Keycloak admin decision

MVP-1 uses **RAMALS-orchestrated registration**. Keycloak native self-registration remains disabled for this capability unless a future ADR explicitly changes the decision.

The backend uses a dedicated confidential Keycloak administrative client/service account for the narrowly required identity operations. It MUST NOT reuse `ramals-core-workload`, whose purpose is Spring → `ramals-ai`. Minimum required realm-management permissions are limited to the user operations implementation actually needs (expected `manage-users` and `view-users`; implementation must verify whether a narrower effective permission set is possible). No `manage-realm` grant is allowed. Public registration APIs expose no generic role-assignment primitive; `LEARNER` is a server constant/policy outcome.

If `learning-platform` receives plaintext password:

- password exists only in request memory for minimum Keycloak handoff duration;
- never persist it in PostgreSQL, cache, idempotency, retry/outbox, audit, trace, metric or log;
- registration request-body logging/tracing is disabled/redacted;
- retries never depend on durable password recovery;
- ambiguous Keycloak create outcome is reconciled before retry-create;
- RAMALS never hashes/stores a substitute credential.

See M1-ADR-014.

## 7. User-visible flow

MVP-1 does not assume RAMALS owns the login page.

`Public RAMALS page/CTA → /register → backend-orchestrated Keycloak identity creation → Keycloak verification email → email verification → hosted Keycloak sign-in → /me/onboarding reconciliation → mobile verification → profile → journey → ONBOARDED`

Do not implement `keycloak.login({action:'register'})`, enable Keycloak self-registration, or modify a Keycloak theme merely to satisfy wording in this package unless a future reviewed ADR changes the architecture.

## 8. Delivery phases

### Phase A — repository discovery and compatibility

Inventory JIT learner provisioning, `core.learner.status` consumers, no-PII constraints/comments, `core.learner_goal`, Keycloak roles/clients/MFA/email settings, migrations, security filter chain, current auth client/routing, tracing/audit/idempotency and local deployment. ADR 0003 governs migration numbering: determine the next Flyway number from the implementation branch at coding time; do not reserve a stale number in this design.

Exit: written mapping to concrete existing classes/files and explicit list of legacy consumers affected by onboarding gating.

### Phase B — identity/registration foundation

Implement separate onboarding state, dedicated PII boundary, dedicated Keycloak admin client integration, public registration security/rate limit, Terms evidence, optional approved adult-attestation evidence, idempotency, partial-failure reconciliation, Keycloak email trigger and canonical email-verification reconciliation.

Exit: JIT `ACTIVE` cannot bypass onboarding; public registration creates only `LEARNER`; no PII is added to `core.learner`.

### Phase C — mobile verification

After email verification and authenticated sign-in, implement E.164 normalization, challenge creation, CSPRNG OTP, keyed HMAC, TTL, verify/resend/attempt ceilings, SMS-specific distributed rate/budget controls, replay prevention, verified-mobile uniqueness/reservation, audit and provider abstraction.

Exit: SMS ownership verification cannot be confused with Keycloak MFA and is safe under retries/concurrency.

### Phase D — professional onboarding and goal compatibility

Implement professional profile and goal/journey capture. LearningJourney owns product orchestration. The designated primary goal/domain projects transactionally/idempotently to existing `core.learner_goal`; legacy `/me/goal` remains supported.

### Phase E — frontend UX

Implement public `/register` and post-login onboarding. Hosted Keycloak stays the login UI. Resume from authoritative server state after refresh/relogin.

### Phase F — hardening

Complete pre-auth registration abuse controls, authenticated SMS controls, admin-client threat controls, failure injection, PII-safe telemetry, performance, migration/backfill safety and operational runbooks.

### Phase G — qualification

Execute Doc 05. Mocks alone do not qualify identity/email/provider boundaries.

Phases A-G are engineering work phases and may coexist within PR-A or PR-B; they are not separate mandatory pull requests.

## 9. Onboarding lifecycle

Persistent onboarding states:

`IDENTITY_CREATED → EMAIL_PENDING → EMAIL_VERIFIED → MOBILE_PENDING → MOBILE_VERIFIED → PROFILE_PENDING → JOURNEY_PENDING → ONBOARDED`

`REGISTRATION_STARTED` is an audit/operation state before durable learner identity exists and need not be a learner onboarding enum value.

Operational account/workflow state remains separately represented by existing `core.learner.status` (`ACTIVE | SUSPENDED | CLOSED`). Never use onboarding `ONBOARDED` as a replacement for operational status and never infer onboarding from operational `ACTIVE`.

## 10. Distributed consistency

There is no distributed transaction across Keycloak, PostgreSQL, SMTP and SMS.

- stable registration idempotency/operation key;
- non-secret recoverable orchestration state;
- lookup/reconcile Keycloak identity after ambiguous create;
- no duplicate Keycloak identities on retry;
- no durable password replay;
- OTP/mobile ownership transactionally established in RAMALS;
- bounded provider timeouts/retries only when safe;
- reconciliation may advance trusted verification state but can never skip required onboarding gates or manufacture `ONBOARDED`.

## 11. Rate-limit architecture

The existing authenticated subject limiter is not sufficient for public registration or SMS pumping prevention.

Required layers:

- **pre-auth registration limiter** before bearer-subject-dependent filters, using source network signal plus privacy-safe normalized email/registration fingerprint dimensions;
- **authenticated mobile limiter** for send/resend/verify keyed by subject + normalized mobile/challenge + source signal;
- **provider/global budget limiter** to bound SMS spend/amplification;
- production multi-replica state must be shared/consistent or enforced at a shared edge plus authoritative application layer. A per-pod in-memory counter alone does not satisfy production qualification.

## 12. Migration strategy

Use additive Flyway migrations per ADR 0003. Prefer expand-first. Existing learners remain usable through an explicit legacy compatibility policy. No destructive retirement of `core.learner_goal` or no-PII core identity. Rollback is application rollback with forward-compatible schema, not production down-migration.

## 13. Feature/configuration strategy

Public registration may be disabled operationally without invalidating existing learners. Local SMTP/SMS test components must be impossible to select in production accidentally. Production profile fails closed when required Keycloak admin credential, SMTP/email-verification configuration, OTP HMAC key, or SMS provider configuration is absent.

## 14. CI/CD and operational readiness

Require backend/frontend tests, real PostgreSQL migration/race tests, Keycloak integration, role-escalation tests including `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, `SERVICE`, log/trace secret scan, API compatibility, E2E identity/onboarding tests, and existing deterministic regression.

Monitor registration success/failure, Keycloak admin failures, email verification, OTP sends/verifies/rejects, rate limits, provider latency/errors/budget, onboarding funnel, mobile conflicts and reconciliation backlog. Never put email/mobile/IP/subject in metric labels.

## 15. Compact implementation PR sequence

Delivery decomposition is a coordination/review mechanism, **not a security control**. For the current solo-founder + AI-pair workflow, M1-PROF-01 should normally be delivered in two coherent implementation PRs while preserving every threat-model, negative-test, concurrency, provider and qualification requirement.

1. **PR-A — Identity + Registration + Verification**
   - additive schema/onboarding state and PII boundary;
   - dedicated Keycloak admin client;
   - public registration and pre-auth abuse control;
   - credential/consent/adult-attestation handling as approved;
   - Keycloak email verification and reconciliation;
   - authenticated mobile verification, HMAC, rate/budget controls, provider abstraction and verified-mobile reservation;
   - idempotency, failure recovery, authorization, concurrency and security tests.

2. **PR-B — Professional Onboarding + Qualification**
   - professional profile;
   - learning goals and LearningJourney;
   - `core.learner_goal` compatibility projection;
   - registration/onboarding UI and authoritative resume;
   - E2E, failure/load/concurrency qualification;
   - observability/runbooks;
   - production email/SMS/provider qualification evidence.

A PR may be split further if reviewability or risk requires it. It MUST NOT be split merely to satisfy ceremony, and combining work MUST NOT weaken security review or permit temporary bypasses on main.

## 16. Completion criteria

Implementation is not complete until security, reliability, concurrency, performance, observability, migration compatibility, Keycloak email verification, goal projection and end-to-end qualification gates pass. Any production provider not exercised in a production-like environment is `NOT VERIFIED` and blocks production enablement.
