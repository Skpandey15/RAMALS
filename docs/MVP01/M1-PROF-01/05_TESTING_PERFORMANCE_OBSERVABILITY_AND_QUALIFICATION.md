# M1-PROF-01 Testing, Performance, Observability and Qualification

## 1. Test strategy

Use layered tests: unit → persistence/integration → Keycloak/provider adapter → API security → frontend → concurrency → performance → E2E/qualification. Mocks alone cannot qualify the capability.

## 2. Functional matrix

Registration: happy path; required-field failures; malformed/boundary names; invalid email; invalid/unsupported phone; missing country; password-policy rejection; password mismatch; Terms absent; duplicate/retried registration; role-injection attempt.

Email: verification required; verified transition; expired/repeated verification link behavior according to Keycloak; resume after verification; unverified learner cannot bypass gate.

OTP: send; correct verify; wrong OTP; expired OTP; max attempts; resend before cooldown; resend after cooldown; old OTP rejected after resend; consumed OTP replay; concurrent verify; provider timeout/failure; rate-limit response.

Mobile: E.164 normalization; country parsing; duplicate verified mobile; concurrent two-account claim of same mobile; mobile change requires re-verification.

Profile: valid create/update; invalid enum/catalog; bounds; unauthorized access; declared skill level does not alter mastery directly.

Journey: create; retry idempotency; multiple domains; no duplicate domains; Kafka not default; own-list/get only; prerequisites enforced.

Resume: refresh/browser restart/login at every lifecycle stage returns correct nextStep from server.

Legacy: existing learner login remains functional according to explicit compatibility rule.

## 3. Security matrix

- public request attempts ADMIN/MENTOR/CONTENT_AUTHOR role injection
- IDOR/cross-learner access
- forged client `verified=true`/`ACTIVE`
- OTP brute force ceiling
- OTP replay
- OTP old-generation after resend
- registration/OTP rate limiting
- account enumeration behavior
- malicious oversized fields
- injection payloads
- XSS payload persistence/rendering
- sensitive log scan for password/OTP/token/provider secret
- authorization with expired/invalid tokens
- fake provider prohibited in production profile

## 4. Concurrency matrix

At minimum:

1. Two simultaneous registrations with same idempotency key → one logical result.
2. Same key with different request fingerprint → conflict.
3. Two simultaneous OTP verifications → at most one transition side effect.
4. Two learners verify same mobile concurrently → exactly one wins; other deterministic conflict.
5. Multiple resend requests → policy enforced and only newest valid challenge accepted.
6. Journey create retries → no duplicates.

Use real PostgreSQL integration tests for DB constraint/race qualification.

## 5. Failure/recovery tests

Inject failures at boundaries:

- Keycloak unavailable before create
- Keycloak timeout with ambiguous create outcome
- DB failure after Keycloak identity creation
- email trigger failure
- SMS timeout/failure
- DB failure during OTP success transaction
- restart between lifecycle transitions
- duplicate callback/retry if provider callbacks exist

Verify no privileged/ACTIVE state is granted by partial failure and retry/reconciliation converges safely.

## 6. Performance targets

Final SLOs should be baselined from environment, but implementation must set measurable gates. Suggested initial non-provider application targets under representative non-production load:

- GET onboarding/profile p95 <= 300 ms, p99 <= 750 ms
- profile/journey DB-backed mutation p95 <= 500 ms excluding external provider latency
- OTP verify p95 <= 500 ms excluding unavoidable external dependency (verification itself should not call SMS provider)
- registration orchestration latency separately reports Keycloak/email external time
- no unbounded thread/request blocking or connection-pool exhaustion

Do not hide provider latency inside aggregate metrics; record dependency histograms separately.

Load scenarios should include steady registration, burst registration, OTP resend abuse, OTP verification burst, profile/journey traffic and mixed normal platform traffic. Verify rate limiter and DB pools degrade safely.

## 7. Capacity/abuse tests

Prove configured limits work under bursts and do not permit SMS amplification. Verify provider budget/quota protection and that high-cardinality attacker-controlled values are not used as metric labels.

## 8. Observability

### Traces

Carry W3C trace context and RAMALS interactionId. Trace registration orchestration and provider calls, but redact PII/secrets. Record dependency outcome and latency.

### Metrics

Suggested low-cardinality metrics:

- registration_attempt_total{outcome}
- registration_transition_total{from,to,outcome}
- mobile_otp_send_total{provider,outcome}
- mobile_otp_verify_total{outcome}
- registration_rate_limit_total{operation}
- onboarding_completion_total{outcome}
- dependency_request_duration_seconds{dependency,operation,outcome}
- reconciliation_total{type,outcome}

Never label metrics with email, mobile, user ID, OTP, IP or interactionId.

### Logs

Structured logs with event/category, correlation IDs and non-sensitive stable record IDs. No request-body dumping on registration/OTP endpoints.

## 9. Alerts

Define thresholds after baseline for: registration failure spike, Keycloak errors, SMS errors/latency, OTP failure anomaly, rate-limit spike, reconciliation backlog, DB uniqueness/conflict anomaly and onboarding completion collapse.

## 10. E2E qualification journey

Production-like qualification must demonstrate:

1. Open login page and choose Create Account.
2. Submit valid professional learner registration.
3. Confirm no privileged role can be requested.
4. Complete real/test-environment email verification through supported mechanism.
5. Send and verify mandatory mobile OTP through environment-appropriate provider.
6. Confirm wrong/expired/replayed OTP rejection.
7. Complete professional profile.
8. Select learning goal and multiple domains with Kafka not preselected.
9. Create initial journey.
10. Logout/login and confirm ACTIVE learner resumes correctly.
11. Verify audit/traces without sensitive data.
12. Verify existing deterministic diagnostic/evidence/mastery paths still behave as before.

## 11. Production-provider qualification

DEV fake SMS proves application behavior, not production delivery. Before production GA, qualify the configured real SMS provider for authentication, TLS, sender configuration, delivery response mapping, timeout, retry semantics, quota/rate behavior, secret rotation procedure and operational dashboards. If not executed, status is `NOT VERIFIED` and production enablement is blocked.

## 12. Migration qualification

Test migration on representative existing data; restart/rollback application compatibility; duplicate/dirty mobile data handling before unique constraint; no destructive loss; Flyway validation clean.

## 13. Definition of qualified

All critical functional/security/concurrency/failure tests pass; performance has no blocking regression; observability is present and PII-safe; production profile fails closed without mandatory provider configuration; existing platform regression suite passes; CI is green; no unresolved severity-1/2 defect; all NOT VERIFIED items are explicit and production blockers where applicable.