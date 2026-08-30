# M1-PROF-01 Security, Privacy, OTP and Threat Model

## 1. Security objective

Public registration is an internet-facing abuse surface. Treat it as an identity-security capability, not a CRUD form.

## 2. Authentication/authorization rules

- OIDC Authorization Code + PKCE for browser authentication per existing architecture.
- Self-registration can only result in learner privileges.
- Role/realm/client-role fields are absent from public registration requests.
- Backend assigns allowed role through server-side policy.
- Admin/service credentials used for Keycloak operations are externalized and least-privilege.
- `/me` resources bind to token `sub`, not request-supplied learner IDs.

## 3. Password rules

Keycloak owns password hashing and policy. RAMALS must not persist, audit, trace, meter or log password values. Avoid passing password through more components than necessary. Redact request bodies at observability boundaries.

## 4. OTP policy baseline

Policy values must be configurable with secure production defaults and bounded ranges. Suggested starting baseline subject to security review:

- numeric OTP: 6 digits generated with CSPRNG
- TTL: approximately 5 minutes
- verification attempts: maximum 5 per challenge
- resend cooldown: approximately 30–60 seconds
- bounded sends per mobile/account/IP over rolling windows
- newest challenge supersedes older active challenge
- successful challenge is consumed exactly once

Do not weaken security by exposing OTP in API/UI/logs in production.

## 5. OTP storage

Because OTP entropy is low, a plain fast hash alone is susceptible to offline enumeration if the DB is compromised. Prefer a keyed construction (for example HMAC with a separately managed server secret/pepper) over `hash(otp)` alone, including challenge-specific context. Store key version, never key material. Compare in constant time.

Key material belongs in the platform secret manager/Kubernetes Secret according to environment standards, never Git.

## 6. Rate limiting / abuse controls

Apply layered controls to registration, OTP send, resend and verify. Dimensions should include combinations of:

- normalized mobile
- account/registration ID
- source IP/network signal
- device/session signal where safely available
- provider-level quota

Return 429 with safe retry metadata where appropriate. Avoid revealing whether a phone/email belongs to another person.

Production edge/WAF rate limiting is complementary; application-level limits remain necessary for identity-aware controls.

## 7. Enumeration resistance

Registration and password/verification-related responses should not unnecessarily disclose account existence. Where product requirements need explicit duplicate resolution for the legitimate owner, design a safe recovery/sign-in path rather than returning rich identity information.

## 8. Mobile ownership

Normalize to E.164 before comparison. One verified mobile maps to one active learner for this MVP. Enforce with DB constraint plus transactional handling. A mobile is not considered owned merely because an OTP was sent.

## 9. Threat model

### Automated account creation
Controls: rate limits, provider quotas, optional bot/risk control at edge, email+mobile verification, monitoring.

### OTP brute force
Controls: short TTL, attempt ceiling, keyed hash, rate limit, lock/supersede, alerts.

### OTP replay
Controls: consumed_at, single successful transaction, old challenge invalidation, concurrency tests.

### SMS pumping/cost abuse
Controls: per-number/IP/account rolling limits, resend cooldown, global/provider budget alarms, country policy if required.

### Privilege escalation
Controls: no public role input, server-side learner assignment, tests attempting admin/mentor injection.

### Account takeover through mobile change
Controls: separate re-verification flow; do not overwrite verified number before proof.

### Duplicate identity/race
Controls: DB uniqueness, transaction isolation/locking, idempotency, Keycloak reconciliation.

### PII leakage
Controls: data minimization, masking, log redaction, restricted DB access, no PII in metrics labels, sanitized traces.

### CSRF/session attacks
Controls: follow existing OIDC/token architecture; if cookies are used for authenticated mutation, apply SameSite/CSRF protections appropriate to deployment. Do not blindly add CSRF tokens to bearer-only flows; threat-model actual credential transport.

### XSS
Controls: React escaping, no unsafe HTML for user profile fields, CSP/headers according to platform standard.

### Injection
Controls: parameterized persistence/JPA, DTO validation, no dynamic query construction from profile fields.

### Provider compromise/failure
Controls: narrow credentials, secret rotation, timeouts, circuit/bulkhead where appropriate, audit provider result categories, no trust in callback unless authenticated.

## 10. Privacy / PII

PII includes name, email, mobile, city and potentially professional attributes. Apply:

- purpose limitation
- data minimization
- least-privilege access
- encryption in transit
- storage encryption through platform/infrastructure controls
- masking in logs/UI where full value is unnecessary
- retention/deletion policy
- export/deletion compatibility
- no PII in metric dimensions

Do not claim GDPR/other regulatory compliance solely because these controls exist; compliance requires broader organizational/legal processes.

## 11. Audit rules

Security audit records contain actor/subject reference, event type, outcome, timestamp, interactionId/traceId, policy/provider category where safe. They must never contain password, OTP, bearer token, refresh token, provider secret or full request bodies.

## 12. Secret management

Production secrets include Keycloak service credentials, OTP HMAC/pepper key and SMS provider credentials. Externalize by environment. Rotation must be possible without code changes. OTP hash key rotation may require verifying challenges with current/previous key versions only for their short lifetime.

## 13. Network policy

Registration does not change the core invariant that ramals-ai cannot access authoritative PostgreSQL. Only learning-platform requires identity/provider egress. Do not broaden AI network access for this feature.

## 14. Security qualification blockers

BLOCK release if any occurs:

- privileged role obtainable through public registration
- plaintext password or OTP stored/logged
- OTP reusable
- unlimited OTP attempts/sends
- mobile uniqueness only application-enforced with race vulnerability
- client can force ACTIVE/VERIFIED state
- `/me` authorization allows cross-user access
- production can silently use fake SMS
- secrets committed
- sensitive values appear in traces/audit/logs
