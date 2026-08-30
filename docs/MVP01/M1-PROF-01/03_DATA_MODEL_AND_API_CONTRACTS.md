# M1-PROF-01 Data Model and API Contracts

## 1. Design rules

- Preserve ADR 0001: Keycloak `sub` is the external identity anchor and `/me` ownership key.
- Preserve the existing Zero Trust invariant that `core.learner` contains no PII.
- Preserve existing `core.learner.status` semantics (`ACTIVE | SUSPENDED | CLOSED`); onboarding is a separate state ending `ONBOARDED`.
- Preserve existing `core.learner_goal` as the deterministic-core compatibility projection during MVP-1.
- Migration numbering follows ADR 0003 and implementation order; do not hard-code a migration number in this design package.
- Use existing UUID/timestamp/version conventions.
- PII is minimized and held in a separately permissioned boundary; it is not copied into unrelated core/AI/event tables.

## 2. Logical model

```text
core.learner (NO PII; OIDC-sub anchored; operational status)
   1 --- 1 ProfessionalOnboarding
   1 --- 1 LearnerContactPII
   1 --- 1 ProfessionalProfile
   1 --- * MobileVerificationChallenge
   1 --- * LearningJourney
   1 --- 0..1 core.learner_goal   <-- deterministic compatibility projection

LearningJourney
   1 --- * LearningJourneyDomain
```

Physical names must follow repository/schema conventions discovered in PR-A. `LearnerContactPII` is a boundary concept, not permission to alter `core.learner`.

## 3. `core.learner` — unchanged identity/operational boundary

Do not add first name, last name, email, mobile, country or city to `core.learner`.

Existing identity remains anchored to opaque OIDC `sub`. Existing operational status remains separate from onboarding. JIT provisioning may create an operationally `ACTIVE` row; it does not create onboarding completion.

## 4. Professional onboarding

Recommended new/reused state record keyed by learner ID:

- learner_id PK/FK
- onboarding_state: `IDENTITY_CREATED | EMAIL_PENDING | EMAIL_VERIFIED | MOBILE_PENDING | MOBILE_VERIFIED | PROFILE_PENDING | JOURNEY_PENDING | ONBOARDED`
- created_at
- updated_at
- version where repository convention requires

`REGISTRATION_STARTED` is an audit/idempotency orchestration event and does not need to be a persistent learner onboarding enum.

Legacy learners require an explicit backfill/compatibility rule in PR-A; absence of an onboarding row must not accidentally lock out existing deterministic workflows.

## 5. Registration/contact PII boundary

Recommended separate least-privilege table/schema boundary (physical name selected during implementation discovery):

- learner_id PK/FK
- first_name
- last_name
- email_normalized / display_email as required
- mobile_e164
- email_verified_at
- mobile_verified_at
- country_code
- city optional
- terms_version
- terms_document_ref / immutable content identifier
- privacy_version
- privacy_document_ref / immutable content identifier
- terms_accepted_at
- privacy_accepted_at where separately captured
- created_at / updated_at / version

Password is never stored.

DB grants should allow only the application components that need contact/profile PII. AI-plane access is prohibited. Audit/events use stable non-PII identifiers and immutable consent references rather than contact values.

The verified-mobile reservation constraint must remain effective for disabled/soft-deleted learners. A soft-delete predicate must not silently release the number.

See M1-ADR-013.

## 6. Mobile verification challenge

Required fields/equivalents:

- id opaque UUID
- learner_id
- mobile_e164
- otp_hmac
- hmac_key_version
- expires_at
- attempt_count
- **max_attempts**
- **policy_version**
- resend_generation / lineage
- provider_message_ref optional/non-secret
- consumed_at
- superseded_at
- created_at

Both `max_attempts` and `policy_version` are recorded so an in-flight challenge has deterministic enforcement/audit semantics even if configured policy changes.

Never store plaintext OTP, reversible OTP, or an unkeyed fast hash. Expired/consumed challenge retention is bounded and purgeable.

## 7. Professional profile

Recommended:

- learner_id PK/FK or id + unique learner_id
- current_role
- experience_band
- primary_expertise
- declared_skill_level (non-authoritative)
- created_at / updated_at / version

Known technologies use normalized catalog references/child rows where the repository has a taxonomy. Professional attributes are not added to `core.learner`.

## 8. LearningJourney and `core.learner_goal`

LearningJourney is the product-level multi-domain orchestration model:

- id
- learner_id
- goal_type
- target_role
- learning_intensity
- weekly_hours
- status
- primary_domain_id (or an equivalent explicit primary-domain relation)
- created_at / updated_at / version

Selected domains use child rows/catalog references. Kafka is never auto-selected.

During MVP-1, existing `core.learner_goal` remains the deterministic-core compatibility projection. The designated primary journey/domain maps to the existing one-goal-per-learner model (`target_domain_id`, target proficiency/date fields as currently defined).

Required compatibility semantics:

1. Journey creation/update writes the primary-goal projection transactionally/idempotently with the authoritative journey mutation where feasible.
2. Existing GET `/me/goal` remains supported.
3. Existing PUT `/me/goal` is not silently broken. For a learner with a journey, route through a compatibility application service that updates primary journey context and projection atomically. For a legacy learner without a journey, preserve current behavior.
4. Failure to create/maintain the required projection prevents transition to `ONBOARDED` where that journey depends on it.
5. Retirement of `core.learner_goal` requires a separate future ADR/migration; it is not part of M1-PROF-01.

## 9. Database controls

Required:

- existing unique OIDC subject preserved;
- `core.learner` PII-free;
- one onboarding row per learner;
- DB-enforced verified-mobile reservation/uniqueness including disabled/soft-deleted identities;
- challenge FKs and active lookup indexes;
- no duplicate domain within journey;
- primary-domain/goal-projection integrity;
- realistic weekly-hours bounds;
- lifecycle/status check constraints according to repository conventions;
- least-privilege grants for PII tables/schema.

Application-only mobile uniqueness is insufficient under concurrency.

## 10. Role vocabulary

Use actual realm roles: `LEARNER`, `INSTRUCTOR`, `CONTENT_AUTHOR`, `ADMIN`, `SERVICE`.

Public registration always produces `LEARNER`. Do not expose `role`, realm-role or client-role fields in public DTOs. Product terminology such as mentor/reviewer must not be invented as a Keycloak role; if mapped in future, current instructor semantics require an explicit decision.

## 11. Public registration API

Candidate path following current versioning convention: `POST /api/v1/registration` (final path may adapt to repository convention).

This is an intentionally unauthenticated endpoint behind a dedicated pre-auth abuse limiter. Request:

- firstName
- lastName
- email
- mobileNumber
- country
- city optional
- password
- confirmPassword
- server-recognized Terms/Privacy version/reference fields

Header: `Idempotency-Key` required.

Server validates consent references against currently acceptable immutable artifacts. No role input. Never echo password. Responses are enumeration-resistant.

The backend creates the Keycloak user through the dedicated registration-admin client, assigns only `LEARNER` through a narrow server policy, persists RAMALS linkage/contact/consent state, and triggers Keycloak verification email.

## 12. Authenticated onboarding/mobile APIs

After Keycloak email verification and hosted-Keycloak sign-in, `/me` APIs derive learner from validated token subject.

Candidate contracts:

- `GET /api/v1/me/onboarding`
- `POST /api/v1/me/mobile/send-otp`
- `POST /api/v1/me/mobile/verify-otp`
- `POST /api/v1/me/mobile/resend-otp`
- `GET/PUT /api/v1/me/profile`
- `POST/GET /api/v1/me/learning-journeys`

Mobile APIs require trusted email verification/onboarding prerequisite and authenticated subject. They do not accept learner ID and do not provide authentication MFA tokens/claims.

Send response: challengeId, expiresAt, resendAfter; never OTP.
Verify request: challengeId + OTP. Repeated success is idempotent/current-state safe; it creates no duplicate ownership side effects.

## 13. Existing goal API compatibility

Existing `/me/goal` remains part of the compatibility contract during M1-PROF-01. Implementation must inventory the exact current controller/service behavior and add tests proving journey introduction does not change deterministic consumers unexpectedly.

## 14. Validation and idempotency

- bounded Unicode-safe names; reject controls;
- established email validation; avoid provider-specific destructive normalization;
- mature phone parsing + E.164;
- controlled country values;
- authoritative password policy remains Keycloak;
- immutable server-known consent artifacts;
- bounded free text and weekly hours;
- registration/journey idempotency stores non-secret request fingerprint/result only;
- registration fingerprint/storage excludes plaintext/reversible credentials;
- ambiguous Keycloak outcomes reconcile rather than replay a stored password.

## 15. PII API minimization

Return only screen-required fields. Mask mobile outside dedicated contact/verification contexts. Do not put email/mobile/IP/subject/OTP in metric labels. Logs/traces/audits use non-sensitive record identifiers and redaction.

## 16. Audit events

Use/reuse structured events such as:

- LEARNER_REGISTRATION_STARTED
- IDENTITY_CREATED
- EMAIL_VERIFICATION_REQUIRED
- EMAIL_VERIFIED
- MOBILE_OTP_SENT
- MOBILE_VERIFICATION_FAILED (category only)
- MOBILE_VERIFIED
- PROFESSIONAL_PROFILE_COMPLETED
- LEARNING_JOURNEY_CREATED
- ONBOARDING_COMPLETED
- TERMS_ACCEPTED / PRIVACY_ACCEPTED with immutable artifact reference

Carry interactionId/traceId per repository conventions. Never include password, OTP, token, provider secret or full request body.

## 17. OpenAPI

Document public vs authenticated endpoints, idempotency, prerequisite state, 400/401/403/409/422/429/5xx, enumeration-resistant error behavior, and examples containing no real PII/secrets. Explicitly state that mobile ownership verification is not Keycloak MFA.
