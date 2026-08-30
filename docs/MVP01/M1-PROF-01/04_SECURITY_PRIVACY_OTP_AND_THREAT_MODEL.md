# M1-PROF-01 Security, Privacy, OTP and Threat Model

## 1. Security objective

Public registration is an internet-facing identity and abuse surface. Treat it as a Zero-Trust security capability, not a CRUD form. Authentication, operational learner status, onboarding completion and authorization are distinct facts.

## 2. Existing security invariants preserved

- OIDC Authorization Code + PKCE remains the browser authentication architecture.
- ADR 0001 OIDC-`sub` ownership and `/me` semantics remain authoritative.
- `core.learner` remains PII-free.
- `core.learner.status=ACTIVE` is not proof of onboarding completion.
- Public self-registration creates only actual realm role `LEARNER`; `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, and `SERVICE` are forbidden public outcomes.
- Existing Keycloak TOTP/MFA and `MfaAuthorization` remain separate from RAMALS SMS ownership verification.
- ramals-ai remains unable to access authoritative PostgreSQL and receives no registration PII.

## 3. Keycloak administrative registration credential

M1-PROF-01 introduces a new privileged identity-plane credential because RAMALS-orchestrated registration must create/read Keycloak users and trigger verification actions.

A dedicated confidential service-account client (implementation name expected to be `ramals-registration-admin` or repository-approved equivalent) MUST be used. It MUST NOT reuse `ramals-core-workload`.

Required controls:

- grant only the minimum effective realm-management user permissions the implementation actually needs; expected upper bound is `manage-users` + `view-users` after verification that narrower permissions cannot satisfy the flow;
- never grant `manage-realm` or broad unrelated administration;
- public business API does not expose arbitrary role assignment; LEARNER assignment is a narrow server-side constant/policy;
- client secret is externalized per environment and never committed;
- rotate/revoke without code change;
- restrict network path to Keycloak where deployment controls permit;
- enable/retain Keycloak admin-event audit appropriate to the environment;
- alert/review unexpected privileged role assignment or admin-client use anomalies;
- treat compromise as critical because user-management privilege can create/modify identities.

See M1-ADR-014.

## 4. Password rules

Keycloak owns password hashing/policy. If RAMALS receives a password for immediate administrative user creation, it exists only transiently in request memory. It must never be persisted, audited, traced, metered, logged, cached, serialized to retry/outbox/idempotency state, or application-hashed as a substitute credential.

After ambiguous Keycloak create outcome, reconcile by stable identity correlation before retry-create; do not require recovery of the password from durable state.

## 5. Email verification authority

Keycloak is the only verification authority for email ownership in this capability. Browser claims are untrusted.

DEV/CI normal qualification configures Keycloak SMTP to a local non-billable sink such as Mailpit and follows the actual Keycloak verification link. An optional Admin-API verification shortcut may exist only under an explicit test profile and must be impossible to enable in production.

Production/production-like environment must configure and exercise approved SMTP/provider delivery. Missing required email-verification delivery/configuration is fail-closed for production registration enablement.

## 6. OTP policy baseline

Configurable secure defaults with bounded ranges:

- numeric 6-digit CSPRNG OTP;
- TTL about 5 minutes;
- maximum 5 verification attempts/challenge;
- resend cooldown about 30–60 seconds;
- bounded sends per subject/mobile/source/provider budget over rolling windows;
- newest challenge supersedes older active challenge;
- success consumes exactly once.

## 7. Mandatory OTP storage construction

A six-digit OTP is low entropy. Unkeyed hashing is prohibited.

Baseline:

`otp_hmac = HMAC-SHA-256(Kv, canonical(challengeId) || 0x00 || canonical(mobileE164) || 0x00 || canonical(otp))`

Requirements:

- `Kv` is external secret material identified by persisted `hmac_key_version`;
- key never enters Git/PostgreSQL/logs;
- challenge and E.164 mobile provide context/domain separation;
- canonical byte encoding is explicit/deterministic;
- HMAC comparison is constant time;
- persisted challenge records include both `max_attempts` and `policy_version`;
- key rotation supports current and only needed previous versions for short-lived in-flight challenges;
- stronger alternative requires explicit security review; unkeyed hash never qualifies.

## 8. Pre-auth registration abuse controls

The current authenticated subject rate limiter cannot protect an unauthenticated registration endpoint because no subject exists yet. M1-PROF-01 requires a dedicated pre-auth limiter positioned so it executes for the public registration route before bearer-subject-dependent enforcement.

Dimensions should combine source network/IP signal with privacy-safe normalized identity/request fingerprint. Responses remain enumeration-resistant. Edge/WAF controls complement rather than replace the application rule.

Production multi-replica deployment requires shared/consistent enforcement at an authoritative application/shared edge layer; a per-pod in-memory counter alone is not sufficient.

## 9. Authenticated SMS abuse controls

SMS send/resend/verify happens only after trusted email verification and authenticated sign-in. Apply layered limits using:

- authenticated subject;
- normalized mobile;
- challenge/registration identity;
- source network/session signal;
- rolling send/verify windows;
- provider/global budget/quota.

The existing subject limiter may contribute but does not replace mobile-keyed or provider-budget controls. Prevent SMS pumping even when an attacker distributes requests across accounts/IPs.

## 10. SMS ownership verification is not authentication MFA

RAMALS SMS OTP proves mobile ownership for onboarding only. It MUST NOT:

- create or assert `amr=otp`;
- increase `acr`;
- satisfy `MfaAuthorization`;
- alter Keycloak TOTP/OTP policy;
- assume Keycloak password/TOTP brute-force protection applies to RAMALS challenges.

See M1-ADR-015.

## 11. Mobile ownership and reuse

Normalize E.164 before comparison. Verified ownership is DB-enforced and transactionally established only after successful challenge verification.

For MVP-1 a verified number stays reserved to the same RAMALS identity after disable/soft-delete. Ordinary registration cannot recycle it. Release/reassignment requires separate audited lifecycle policy and stronger ownership proof. Schema constraints must preserve this rule rather than condition uniqueness on an `active`/soft-delete predicate that frees the number.

## 12. PII boundary

Name, email, mobile, country/city and some professional attributes are PII. The existing PII-free `core.learner` baseline is preserved.

Store contact/registration PII only in a separately permissioned boundary keyed to learner identity. Apply purpose limitation, least privilege, encryption in transit/storage controls, masking, retention/deletion/export compatibility, and no PII in metric dimensions. AI-plane access is prohibited.

Do not claim regulatory compliance solely from these controls.

See M1-ADR-013.

## 13. Threat model

### JIT onboarding bypass
Threat: existing JIT provisioning creates operational `ACTIVE`, and a consumer mistakes that for onboarding completion.
Controls: separate onboarding state ending ONBOARDED; professional product gates check it explicitly; regression tests inventory existing ACTIVE consumers.

### Privileged Keycloak admin-client compromise
Threat: credential can create/modify identities and potentially abuse role assignment.
Controls: dedicated client, minimum user roles, no manage-realm, external secret/rotation, network restriction, admin audit, no generic role API, anomaly tests/monitoring.

### Automated account creation
Controls: pre-auth limiter, provider quotas, optional edge bot/risk controls, email verification, monitoring.

### OTP brute force/offline enumeration/replay
Controls: short TTL, max attempts, keyed HMAC, constant-time compare, send/verify limits, consumed/superseded state, concurrency tests.

### SMS pumping/cost abuse
Controls: mobile/subject/source windows, resend cooldown, provider/global budget alarms and hard ceilings.

### Role escalation
Controls: public role field absent; LEARNER server-controlled; negative tests for INSTRUCTOR, CONTENT_AUTHOR, ADMIN, SERVICE.

### Account takeover through mobile change/number recycling
Controls: pending re-verification; existing verified value not overwritten before proof; number remains reserved after disable/soft-delete.

### Duplicate identity/race
Controls: DB uniqueness, transaction/locking, idempotency, Keycloak reconciliation.

### PII leakage / core invariant erosion
Controls: separate PII boundary and narrow grants; no contact columns on `core.learner`; masking/redaction; no PII metric labels/traces/audit payloads.

### Email-verification spoofing
Controls: browser cannot assert verification; trusted Keycloak claim/server lookup only; DEV/CI uses real Keycloak verification path.

### MFA confusion
Controls: explicit SMS-vs-Keycloak-MFA contract and tests proving SMS verification does not grant MFA-protected authorization.

### CSRF/session, XSS and injection
Follow actual credential transport. Cookie-authenticated mutations require appropriate SameSite/CSRF defenses; bearer-only paths are not protected by adding irrelevant CSRF state. Use React escaping/CSP/platform headers, bounded DTOs and parameterized persistence.

### Provider failure/compromise
Use narrow credentials, external secrets/rotation, bounded timeouts, authenticated callbacks if any, circuit/bulkhead as appropriate, provider-result categorization and fail-closed production configuration.

## 14. Terms and Privacy evidence

Boolean acceptance is insufficient. Persist server-known immutable Terms/Privacy artifact reference/version plus timestamp. Reject client-invented versions. Audits may carry immutable references, never credential/OTP/full request data.

## 15. Secret management

Production secrets include dedicated Keycloak registration-admin client secret, OTP HMAC key, SMS provider credentials and any SMTP/provider secret managed for Keycloak. None enter Git. Rotation must not require code modification.

## 16. Security qualification blockers

BLOCK release if any occurs:

- JIT/operational ACTIVE can bypass required onboarding;
- PII is added to `core.learner` without a superseding reviewed ADR;
- public registration can obtain INSTRUCTOR, CONTENT_AUTHOR, ADMIN or SERVICE;
- registration reuses `ramals-core-workload` or grants unnecessarily broad Keycloak realm administration;
- privileged Keycloak registration credential is committed/logged or lacks rotation/externalization;
- browser can force email/mobile/ONBOARDED state;
- production email verification can silently use a fake RAMALS adapter or run without approved Keycloak SMTP/provider configuration;
- public registration lacks effective pre-auth abuse limiting;
- SMS lacks per-mobile/subject/provider-budget protection across replicas;
- RAMALS SMS verification satisfies/claims Keycloak MFA assurance;
- plaintext password or OTP is stored/logged/traced;
- password enters idempotency/cache/retry/outbox storage;
- OTP uses unkeyed hashing, is replayable, or has unlimited attempts/sends;
- verified mobile is automatically reusable after disable/soft-delete;
- mobile uniqueness is only application-enforced;
- `/me` allows cross-user access;
- production silently uses fake SMS;
- AI→authoritative PostgreSQL isolation is weakened;
- sensitive values appear in logs/traces/audit/metric labels.
