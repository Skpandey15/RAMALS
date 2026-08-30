# M1-PROF-01 Testing, Performance, Observability and Qualification

## 1. Test strategy

Use layered tests: unit → persistence/integration → Keycloak/admin-client/SMTP/SMS adapter → API security → frontend → concurrency → performance → E2E/qualification. Mocks alone cannot qualify identity, email verification, DB races or provider boundaries.

## 2. Main-branch compatibility matrix

Before feature qualification, prove these existing invariants remain true:

- OIDC `sub` and `/me` ownership continue to work per ADR 0001.
- Existing JIT provisioning may produce operational `core.learner.status=ACTIVE` but cannot yield `onboarding_state=ONBOARDED` or access a professional endpoint that requires onboarding.
- `core.learner` remains PII-free at schema/entity/API level.
- Existing `ACTIVE | SUSPENDED | CLOSED` operational semantics and background-workflow behavior remain unchanged unless an explicitly inventoried consumer requires the combined professional eligibility gate.
- Existing `core.learner_goal` GET/PUT and deterministic consumers continue to work through the compatibility projection.
- Existing Keycloak TOTP/MFA behavior and `MfaAuthorization` are unchanged by SMS ownership verification.
- Existing `ramals-core-workload` remains dedicated to Spring → ramals-ai and is not reused for Keycloak administration.

## 3. Functional matrix

Registration: happy path; field bounds; invalid email/phone; password-policy rejection; mismatch; missing/unknown consent artifact; duplicate/idempotent retry; ambiguous Keycloak create recovery; role-injection attempt; pre-auth rate limit.

Email: Keycloak verification required; DEV/CI verification link delivered through local SMTP sink; verified transition; expired/repeated link behavior; production-like SMTP qualification; browser `emailVerified=true` rejected; unverified learner cannot continue to mobile/profile.

OTP: send; correct/wrong/expired; max attempts; resend cooldown/window; old challenge rejected; consumed replay; concurrent verify; provider timeout/failure; distributed rate-limit/budget behavior; HMAC/key-version policy.

Mobile: E.164; duplicate/reserved verified mobile; two-identity race; number remains reserved after disable/soft-delete; mobile change requires re-verification.

Profile: valid create/update; invalid enum/catalog/bounds; unauthorized access; declared skill level does not alter mastery.

Journey/goal: create/idempotency; multi-domain; Kafka not default; explicit primary domain; transactional/idempotent `core.learner_goal` projection; GET/PUT `/me/goal` compatibility; projection failure prevents ONBOARDED where required.

Resume: refresh/relogin at every onboarding stage returns server-authoritative nextStep.

Legacy: pre-feature learners retain intended existing behavior under the documented compatibility/backfill rule.

## 4. Security matrix

- public request attempts `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, `SERVICE` role injection;
- public DTO/API has no generic role assignment;
- dedicated Keycloak registration-admin client has only reviewed minimum user-management permissions; no `manage-realm`; secret absent from Git/logs;
- JIT operational ACTIVE cannot bypass onboarding;
- IDOR/cross-learner access;
- forged verification/ONBOARDED client values;
- OTP brute force/replay/generation rollover;
- pre-auth registration limits and authenticated mobile/provider-budget limits;
- account enumeration behavior;
- malicious oversized/injection/XSS payloads;
- sensitive log/trace/audit/idempotency scan for password/OTP/token/provider/admin secret;
- invalid/expired tokens;
- fake/test email or SMS configuration impossible in production;
- SMS ownership verification does not create `amr=otp`, increase `acr`, or satisfy `MfaAuthorization`;
- schema test proves no contact PII columns were added to `core.learner`.

## 5. Concurrency matrix

1. Same registration idempotency key concurrently → one logical result.
2. Same key + different non-secret fingerprint → conflict.
3. Ambiguous Keycloak create + retry → no duplicate identity.
4. Two OTP verifies → at most one transition side effect.
5. Two learners claim same mobile → exactly one wins.
6. Multiple resend requests → policy enforced; only newest challenge valid.
7. Journey retries → no duplicate journey or inconsistent `core.learner_goal` projection.
8. Concurrent journey/legacy goal updates follow documented compatibility locking/version behavior.

Use real PostgreSQL for constraint/race qualification.

## 6. Failure/recovery tests

Inject:

- Keycloak unavailable before create;
- Keycloak timeout with ambiguous create;
- DB failure after Keycloak user creation;
- Keycloak verification-email trigger/SMTP failure;
- privileged admin credential missing/revoked;
- SMS timeout/failure/budget exhaustion;
- DB failure during OTP success;
- restart between onboarding transitions;
- goal projection failure;
- rate-limit shared-state outage or degradation according to fail-safe policy.

Partial failure must never grant ONBOARDED, verification, privileged role or duplicate ownership.

## 7. Performance targets

Initial non-provider application targets under representative load:

- GET onboarding/profile p95 <= 300 ms, p99 <= 750 ms;
- profile/journey DB-backed mutation p95 <= 500 ms excluding external provider time;
- OTP verify p95 <= 250 ms, p99 <= 500 ms because verification should be local HMAC + DB transaction with no SMS call;
- registration reports application, Keycloak-admin and email-trigger dependency latency separately;
- no unbounded request/thread/connection-pool behavior.

Performance budgets are starting qualification gates, not permanent SLO claims; baseline and adjust through evidence.

## 8. Abuse/capacity tests

Exercise steady/burst registration, distributed-source registration, OTP resend pumping, many-account/same-mobile pumping, verify bursts, provider-budget exhaustion, and mixed normal platform traffic. Prove shared production limits cannot be bypassed by switching application replicas. Attacker-controlled identifiers must never become metric labels.

## 9. Observability

Carry W3C trace context and RAMALS interactionId. Record dependency/category/outcome/latency without PII/secrets.

Suggested low-cardinality metrics:

- `registration_attempt_total{outcome}`
- `registration_transition_total{from,to,outcome}`
- `keycloak_registration_admin_request_total{operation,outcome}`
- `email_verification_trigger_total{outcome}`
- `mobile_otp_send_total{provider,outcome}`
- `mobile_otp_verify_total{outcome}`
- `registration_rate_limit_total{operation}`
- `onboarding_completion_total{outcome}`
- `goal_projection_total{outcome}`
- `reconciliation_total{type,outcome}`

Never label with email, mobile, subject/user ID, OTP, IP or interactionId.

## 10. E2E qualification journey

Production-like qualification demonstrates:

1. Open RAMALS public `/register` entry point; do not assume RAMALS owns the hosted Keycloak sign-in page.
2. Submit valid registration.
3. Confirm resulting realm role is only `LEARNER` and attempts for `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, `SERVICE` fail.
4. Confirm PII is absent from `core.learner` and stored only in approved PII boundary.
5. Receive/follow actual Keycloak verification email through the environment-appropriate SMTP path.
6. Sign in through hosted Keycloak.
7. Prove operational `ACTIVE` alone does not bypass onboarding.
8. Reconcile trusted email verification and enter mobile step.
9. Send/verify mandatory SMS ownership OTP; reject wrong/expired/replayed challenge.
10. Prove SMS verification does not satisfy existing MFA-protected authorization.
11. Complete professional profile.
12. Select goal/multiple domains with Kafka not preselected and explicit primary domain.
13. Create initial journey and verify consistent `core.learner_goal` projection.
14. Reach `ONBOARDED`; logout/login and resume correctly.
15. Verify audit/traces/logs contain no sensitive data.
16. Verify existing deterministic diagnostic/evidence/mastery and legacy `/me/goal` paths remain green.

## 11. Production provider qualification

### Email

Before production enablement, qualify Keycloak SMTP/provider authentication, TLS, sender configuration, verification-link delivery, retry/failure behavior, secret rotation and monitoring. If not executed, email delivery is `NOT VERIFIED` and production registration is blocked.

### SMS

DEV fake SMS proves application behavior only. Before GA qualify real provider authentication, TLS, sender configuration, response mapping, timeout/retry semantics, quota/rate behavior, secret rotation and dashboards. If not executed, SMS delivery is `NOT VERIFIED` and production registration is blocked.

## 12. Migration qualification

Follow ADR 0003. Determine next migration number from implementation order at coding time. Test against representative existing learners/goals, including JIT-provisioned rows. Prove additive rollout/backfill, no destructive loss, PII-free core preservation, mobile reservation constraints and clean Flyway validation.

## 13. Definition of qualified

All critical functional/security/concurrency/failure tests pass; main-branch invariants above remain green; performance has no blocking regression; observability is PII-safe; production profiles fail closed without required admin/email/SMS/HMAC configuration; CI is green; no unresolved severity-1/2 defect; all `NOT VERIFIED` items are explicit production blockers.
