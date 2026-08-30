# M1-PROF-01 Implementation Execution Checklist

This is the coding handoff checklist for Claude/Codex. Read all sibling documents and M1-ADR-012 through M1-ADR-015 before implementation.

## Gate 0 — discover before editing

- [ ] Map ADR 0001 JIT provisioning (`sub` → `core.learner`) and every consumer of `core.learner.status`.
- [ ] Confirm `core.learner` no-PII schema/comment/entity invariant.
- [ ] Inventory existing `core.learner_goal`, `/me/goal`, and deterministic consumers.
- [ ] Inventory Keycloak realm roles exactly: `LEARNER`, `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, `SERVICE`.
- [ ] Inventory existing `ramals-core-workload`; do not reuse it for Keycloak administration.
- [ ] Inventory Keycloak SMTP, verification actions, TOTP/MFA policy, `MfaAuthorization`, brute-force settings.
- [ ] Inventory `SecurityConfig`, bearer filter ordering and current subject/IP rate limiters.
- [ ] Inventory web-ui auth flow; hosted Keycloak login remains sign-in UI.
- [ ] Inventory current Flyway migrations and choose next version at implementation time per ADR 0003.
- [ ] Inventory audit, interactionId/traceId and idempotency patterns.
- [ ] Confirm no AWS/T15/Contract-B/mastery redesign.

## Gate 1 — ADR/invariant mapping

- [ ] M1-ADR-012 applied: operational learner status and onboarding state are independent; terminal onboarding state is `ONBOARDED`.
- [ ] M1-ADR-013 applied: no PII added to `core.learner`; separate least-privilege PII boundary used.
- [ ] M1-ADR-014 applied: dedicated Keycloak registration-admin client with minimum user-management permission; no `manage-realm`; no reuse of `ramals-core-workload`.
- [ ] M1-ADR-015 applied: RAMALS-orchestrated registration, Keycloak email authority, hosted sign-in, authenticated SMS ownership verification distinct from MFA.

## Gate 2 — schema

- [ ] Add only additive Flyway migration(s), numbered by implementation order per ADR 0003.
- [ ] Existing OIDC subject uniqueness preserved.
- [ ] Existing operational `ACTIVE|SUSPENDED|CLOSED` semantics preserved.
- [ ] Separate onboarding state ends `ONBOARDED`; JIT `ACTIVE` cannot set/imply it.
- [ ] No name/email/mobile/country/city columns added to `core.learner`.
- [ ] Separate contact/registration PII table/schema has least-privilege grants.
- [ ] Mobile normalized E.164 and DB reservation/uniqueness survives disable/soft-delete.
- [ ] OTP challenge stores `otp_hmac`, `hmac_key_version`, `attempt_count`, `max_attempts`, `policy_version`, expiry/consume/supersede state; no plaintext/unkeyed OTP.
- [ ] Terms/Privacy immutable refs + timestamps persisted.
- [ ] Profile/journey follow existing ID/audit/version conventions.
- [ ] `core.learner_goal` remains supported with explicit journey projection.
- [ ] Legacy learner compatibility/backfill defined.

## Gate 3 — backend identity/public registration

- [ ] Public `/register` API is narrowly exposed; unrelated APIs remain authenticated.
- [ ] Dedicated pre-auth registration limiter runs before bearer-subject-dependent enforcement.
- [ ] Public request cannot carry arbitrary realm/client role fields.
- [ ] Server assigns only `LEARNER`; negative paths cover `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, `SERVICE`.
- [ ] Dedicated Keycloak admin client secret externalized and rotation-capable.
- [ ] Admin client effective permissions reviewed; `manage-realm` absent.
- [ ] IdentityProviderPort exposes only needed user/verification operations, not generic realm administration.
- [ ] Password never enters logs/traces/audit/cache/idempotency/retry/outbox/persistence.
- [ ] Keycloak ambiguous-create reconciliation implemented before retry-create.
- [ ] Consent versions validated against server-known immutable artifacts.
- [ ] Keycloak triggers verification email; RAMALS does not fake verification authority.

## Gate 4 — email verification/onboarding separation

- [ ] DEV/CI Keycloak SMTP points to Mailpit/equivalent non-billable sink and E2E follows actual verification link.
- [ ] Any Admin-API emailVerified shortcut is explicit test-only and impossible in production.
- [ ] Production profile fails closed without approved Keycloak SMTP/provider setup.
- [ ] `/me/onboarding` trusts token/Keycloak state only; browser booleans never authoritative.
- [ ] `core.learner.status=ACTIVE` alone cannot unlock endpoints requiring professional onboarding.
- [ ] Existing consumers that intentionally use operational ACTIVE retain their semantics.

## Gate 5 — mobile verification

- [ ] Mobile APIs are authenticated `/me` operations and require trusted email verification.
- [ ] Mature E.164 parser/normalizer.
- [ ] CSPRNG OTP.
- [ ] Mandatory `HMAC-SHA-256(Kv, canonical(challengeId) || 0x00 || canonical(mobileE164) || 0x00 || canonical(otp))` or security-approved stronger keyed equivalent.
- [ ] Canonical byte encoding documented; constant-time compare.
- [ ] HMAC key externalized with version/rotation.
- [ ] TTL, attempt ceiling, resend cooldown/window, supersede, single-use consume.
- [ ] Authenticated limits combine subject + mobile/challenge + source; provider/global budget hard limits exist.
- [ ] Production multi-replica rate enforcement cannot be bypassed by switching pods.
- [ ] Concurrent verification/mobile claim race safe.
- [ ] Verified number remains reserved after disable/soft-delete.
- [ ] DEV fake/non-billable SMS sender and production fail-closed sender config.
- [ ] SMS verification does NOT set/claim `amr=otp`, raise `acr`, satisfy `MfaAuthorization`, or alter Keycloak OTP/TOTP policy.

## Gate 6 — professional profile/journey/goal compatibility

- [ ] Profile fields are outside `core.learner`; declared skill remains non-authoritative.
- [ ] Journey supports target role, goal, selected domains, intensity, weekly availability.
- [ ] Kafka not default/preselected.
- [ ] Explicit primary journey/domain identified.
- [ ] Primary journey goal projects transactionally/idempotently to `core.learner_goal`.
- [ ] Existing GET `/me/goal` remains supported.
- [ ] Existing PUT `/me/goal` has explicit compatibility behavior for journey and legacy learners.
- [ ] Goal projection inconsistency cannot yield `ONBOARDED` where projection is required.
- [ ] `/me` ownership enforced.

## Gate 7 — frontend

- [ ] RAMALS exposes public registration page/CTA; do not claim hosted Keycloak login is a RAMALS page.
- [ ] Hosted Keycloak remains sign-in UI for MVP-1.
- [ ] Do not enable Keycloak native self-registration or use `keycloak.login({action:'register'})` without a new reviewed ADR.
- [ ] Registration form includes mandatory mobile and consent.
- [ ] Post-verification sign-in resumes email/mobile/profile/journey flow from server state.
- [ ] Mobile OTP UX has resend/expiry/safe errors.
- [ ] No authoritative onboarding/business state in localStorage.
- [ ] Accessible UX and sensitive-value minimization.

## Gate 8 — tests

- [ ] Real PostgreSQL integration/race tests.
- [ ] Keycloak user-create/admin-permission integration tests.
- [ ] Keycloak SMTP verification-link E2E in DEV/CI.
- [ ] JIT operational ACTIVE cannot bypass onboarding.
- [ ] Schema regression proves `core.learner` remains PII-free.
- [ ] Role escalation negatives for INSTRUCTOR/CONTENT_AUTHOR/ADMIN/SERVICE.
- [ ] Browser verification/ONBOARDED forgery negatives.
- [ ] Password/admin-secret/OTP/token leakage scan.
- [ ] Unknown consent version rejection.
- [ ] OTP wrong/expired/replay/resend/max-attempt/HMAC storage tests.
- [ ] Pre-auth registration and authenticated mobile/provider-budget rate-limit tests.
- [ ] Cross-replica limiter qualification.
- [ ] Mobile uniqueness/reservation concurrency tests.
- [ ] SMS verification does not satisfy existing MFA authorization tests.
- [ ] `core.learner_goal` projection and legacy GET/PUT regression tests.
- [ ] Partial-failure/recovery and frontend/E2E tests.
- [ ] Existing RAMALS deterministic regression suite green.

## Gate 9 — observability/security/operations

- [ ] interactionId/traceId propagated without PII/secrets.
- [ ] Keycloak admin operations and anomalies auditable.
- [ ] Low-cardinality metrics; no email/mobile/IP/subject/OTP metric labels.
- [ ] External dependency timeouts bounded and retries safe/idempotent.
- [ ] Registration/OTP abuse metrics and provider budget alarms.
- [ ] AI→Postgres isolation unchanged.
- [ ] DEV/CI local SMTP/SMS deterministic/non-billable.
- [ ] Production cannot select test email/SMS shortcuts.
- [ ] Production secrets externalized.
- [ ] Public registration operational kill switch if rollout requires.

## Gate 10 — performance/reliability

- [ ] GET onboarding/profile baseline recorded.
- [ ] OTP verify target measured (initial p95 <=250 ms, p99 <=500 ms excluding provider calls).
- [ ] Registration reports Keycloak/email dependency latency separately.
- [ ] DB pool/thread behavior validated under load.
- [ ] Registration/SMS abuse cannot amplify provider sends without bound.
- [ ] Intermediate-state restart/recovery converges safely.

## Gate 11 — final qualification evidence

Report exact evidence, not inference:

- [ ] learner registration creates only LEARNER
- [ ] dedicated Keycloak admin client least privilege evidenced
- [ ] Keycloak email verification success via canonical path
- [ ] no PII in `core.learner`
- [ ] operational ACTIVE cannot bypass ONBOARDED gate
- [ ] mandatory mobile ownership verification success
- [ ] SMS-vs-MFA separation negative control
- [ ] OTP abuse/replay negatives
- [ ] immutable consent evidence
- [ ] profile persisted
- [ ] journey + `core.learner_goal` projection consistent
- [ ] Kafka not default
- [ ] interrupted onboarding resumes
- [ ] duplicate/race/reserved-mobile tests pass
- [ ] logs/traces/audit clean
- [ ] performance/load results
- [ ] existing deterministic/goal regressions green
- [ ] CI green
- [ ] production email provider qualification or `NOT VERIFIED` + blocked
- [ ] production SMS provider qualification or `NOT VERIFIED` + blocked

## Mandatory PR sequence

1. **PR-A — schema + identity foundation**
2. **PR-B — mobile verification**
3. **PR-C — professional onboarding + LearningJourney + goal compatibility + UI**
4. **PR-D — hardening + E2E/production qualification**

Each PR must leave main buildable/testable and must not temporarily weaken security. Do not merge automatically. Final review verdict is MERGE / FIX / BLOCKED / WAIT FOR CI based on evidence.
