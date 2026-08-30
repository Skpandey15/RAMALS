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

For a RAMALS-orchestrated registration endpoint that receives plaintext password, the following are mandatory: no request-body logging, no trace payload capture, no audit capture, no cache persistence, no idempotency payload persistence, no retry/outbox serialization, and no application-side password hashing. The password is transient secret material used only for the immediate Keycloak handoff.

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

## 5. Mandatory OTP storage construction

A six-digit OTP has low entropy; an ordinary unkeyed fast hash is not acceptable because an attacker with database access can enumerate the small OTP space offline.

RAMALS MUST use a keyed construction for persisted verification state. Baseline construction:

`otp_hmac = HMAC-SHA-256(Kv, canonical(challengeId) || 0x00 || canonical(mobileE164) || 0x00 || canonical(otp))`

Requirements:

- `Kv` is a separately managed secret key/pepper identified by `hmac_key_version`.
- `Kv` never enters Git or PostgreSQL.
- challenge ID and normalized E.164 mobile are included as context/domain separation so equal OTPs across challenges do not yield reusable verification state.
- the exact canonical byte encoding must be documented and deterministic in implementation; do not rely on ambiguous string concatenation.
- compare HMAC values in constant time.
- store `otp_hmac` and key version, never plaintext OTP or reversible OTP.
- key rotation must support the current key and, only for the short lifetime of in-flight challenges, required previous key versions.

Equivalent stronger keyed constructions require explicit security review; an unkeyed hash does not satisfy this requirement.

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

## 8. Mobile ownership and reuse

Normalize to E.164 before comparison. One verified mobile maps to one active learner for this MVP. Enforce with DB constraint plus transactional handling. A mobile is not considered owned merely because an OTP was sent.

For MVP-1, once a mobile is verified it remains reserved to that RAMALS identity even if the identity is disabled or soft-deleted. Ordinary registration MUST NOT automatically recycle/reassign the number. Release/reassignment requires a separate, audited account-deletion/administrative policy with stronger ownership proof. This rule must be represented in schema/constraints so a soft-delete flag does not accidentally free the unique number.

## 9. Threat model

### Automated account creation
Controls: rate limits, provider quotas, optional bot/risk control at edge, email+mobile verification, monitoring.

### OTP brute force
Controls: short TTL, attempt ceiling, keyed HMAC, rate limit, lock/supersede, alerts.

### OTP offline enumeration after DB compromise
Controls: mandatory keyed HMAC with external secret; never unkeyed low-entropy hash.

### OTP replay
Controls: consumed_at, single successful transaction, old challenge invalidation, concurrency tests.

### SMS pumping/cost abuse
Controls: per-number/IP/account rolling limits, resend cooldown, global/provider budget alarms, country policy if required.

### Privilege escalation
Controls: no public role input, server-side learner assignment, tests attempting admin/mentor injection.

### Account takeover through mobile change
Controls: separate re-verification flow; do not overwrite verified number before proof.

### Number recycling / deleted-account takeover
Controls: verified number remains reserved in MVP-1; release only through explicit audited policy.

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

## 10. Terms and Privacy evidence

A boolean acceptance flag is not sufficient production evidence. Persist the exact server-known immutable Terms/Privacy artifact version/reference accepted and acceptance timestamp. If documents are separate, record each independently. Audit events may include the non-sensitive immutable version/reference, never passwords/OTP/full request bodies.

The server must reject unknown/client-invented consent versions. Document content storage/version publication may live outside this capability, but the accepted reference must resolve to an immutable artifact under platform governance.

## 11. Privacy / PII

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

## 12. Audit rules

Security audit records contain actor/subject reference, event type, outcome, timestamp, interactionId/traceId, policy/provider category where safe. They must never contain password, OTP, bearer token, refresh token, provider secret or full request bodies.

## 13. Secret management

Production secrets include Keycloak service credentials, OTP HMAC key and SMS provider credentials. Externalize by environment. Rotation must be possible without code changes. OTP HMAC key rotation may verify challenges with current/previous key versions only for their short lifetime.

## 14. Network policy

Registration does not change the core invariant that ramals-ai cannot access authoritative PostgreSQL. Only learning-platform requires identity/provider egress. Do not broaden AI network access for this feature.

## 15. Security qualification blockers

BLOCK release if any occurs:

- privileged role obtainable through public registration
- plaintext password or OTP stored/logged/traced
- registration password placed in idempotency/cache/retry/outbox storage
- OTP protected only by an unkeyed hash
- OTP reusable
- unlimited OTP attempts/sends
- verified mobile becomes automatically reusable after disable/soft-delete
- mobile uniqueness only application-enforced with race vulnerability
- client can force ACTIVE/VERIFIED state
- `/me` authorization allows cross-user access
- production can silently use fake SMS
- secrets committed
- sensitive values appear in traces/audit/logs
