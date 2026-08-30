# M1-PROF-01 Data Model and API Contracts

## 1. Design rules

- Inspect/reuse existing learner/user tables first.
- Keycloak `sub` is the external identity key; email is mutable and is not the immutable join key.
- UUID/UUIDv7 and timestamp conventions follow existing RAMALS standards.
- Use optimistic locking/version where repository conventions require it.
- PII columns receive least-privilege access and must not be copied into unrelated tables/events.

## 2. Logical model

```text
LearnerIdentity
  1 --- 1 ProfessionalProfile
  1 --- * MobileVerificationChallenge
  1 --- * LearningJourney
LearningJourney
  1 --- * LearningJourneyDomain
```

Names are logical; implementation must reuse existing tables/entities where suitable.

## 3. Learner identity/application registration fields

Recommended fields or equivalents:

- id
- keycloak_subject (unique, non-null once identity created)
- first_name
- last_name
- normalized_email / display email as existing model permits
- mobile_e164 (nullable until captured; verified uniqueness handled explicitly)
- mobile_verified_at
- country_code
- city (optional)
- terms_version
- terms_accepted_at
- onboarding_state
- created_at / updated_at
- version

Do not persist password.

## 4. Mobile verification challenge

Recommended:

- id (opaque UUID)
- learner_id
- mobile_e164
- otp_hash
- hash/key version if needed
- expires_at
- attempt_count
- max_attempts or policy version
- resend lineage/generation
- provider_message_ref (non-secret, optional)
- consumed_at
- superseded_at
- created_at

Do not persist plaintext OTP. Expired/consumed challenge retention must be bounded by policy and purgeable.

## 5. Professional profile

Recommended:

- learner_id PK/FK or id + unique learner_id
- current_role
- experience_band
- primary_expertise
- declared_skill_level (explicitly non-authoritative)
- created_at / updated_at / version

Technologies known should use normalized child rows/catalog references rather than uncontrolled comma-separated strings when an existing taxonomy/catalog exists.

## 6. Learning journey

Recommended:

- id
- learner_id
- goal_type
- target_role
- learning_intensity
- weekly_hours
- status
- created_at / updated_at / version

Selected domains use child rows/references. Kafka is not inserted automatically unless explicitly selected or existing compatibility requires a clearly documented migration.

## 7. Constraints

Required database-level controls:

- unique Keycloak subject
- verified mobile uniqueness (implementation may require a partial unique index depending on schema/state)
- FK integrity
- allowed lifecycle/status values via enum/check/application + migration conventions
- non-negative/realistic weekly-hours bounds
- no duplicate domain within one journey
- challenge lookup indexes for learner/mobile/active expiry paths

Application checks alone are insufficient for mobile uniqueness under concurrency.

## 8. API principles

- Version according to current RAMALS API conventions.
- JSON only unless existing conventions differ.
- Explicit request DTOs; never bind persistence entities directly.
- RFC/problem-style existing error envelope should be reused.
- Validation errors must be deterministic and safe.
- Mutating retry-sensitive endpoints support idempotency.
- `/me` endpoints derive identity from authenticated subject.

## 9. Candidate contracts

Exact paths may be adapted to existing routing conventions.

### POST registration

Request: firstName, lastName, email, mobileNumber, country, city?, password, confirmPassword, acceptedTermsVersion. Header: `Idempotency-Key` strongly recommended/required.

Response: registrationId/opaque reference, lifecycle status, nextStep. Never echo password. Avoid revealing whether unrelated accounts exist beyond the chosen enumeration policy.

### POST mobile/send-otp

Requires correct registration/auth context. Request contains intended mobile only where not already bound. Response contains challengeId, expiresAt, resendAfter. Never OTP.

### POST mobile/verify-otp

Request: challengeId, otp. Response: verified boolean/status and nextStep. Repeated successful request should be idempotent where possible; consumed challenge with same completed state must not create duplicate side effects.

### POST mobile/resend-otp

Returns new/current challenge metadata under policy. Previous OTP becomes unusable.

### GET /me/onboarding

Returns authoritative lifecycle and nextStep plus completion flags safe for UI.

### GET/PUT /me/profile

Authenticated learner only. PUT validates enumerations/catalog references. It cannot mutate password, roles or verified mobile ownership directly.

### POST /me/learning-journeys

Requires verified identity/profile prerequisites. Use Idempotency-Key. Request includes goal, targetRole, domains, intensity, weeklyHours. Response returns journey ID/status.

### GET /me/learning-journeys

Only caller-owned journeys.

## 10. Validation

- names: bounded length, Unicode-safe, trim/canonicalization; reject control characters.
- email: use established validator; normalize carefully without provider-specific destructive assumptions.
- phone: parse with country context and normalize E.164 using a mature library.
- country: ISO-style controlled value/catalog.
- password: defer authoritative policy to Keycloak; frontend hints are advisory.
- terms: accepted=true plus server-known current/accepted version.
- free text: strict size limits and output encoding.
- weekly hours: bounded numeric value.

## 11. Idempotency

Registration and journey creation require a deterministic idempotency design. Persist key scope, request fingerprint, result reference and expiration according to existing RAMALS patterns. Same key + materially different request returns conflict. Do not cache secrets/passwords in idempotency payload storage.

## 12. PII API minimization

Responses return only fields required by the screen. Mobile should be masked outside dedicated verification/profile contexts (for example `+91******3210`). Logs and audit events use masked or stable non-PII identifiers.

## 13. Events/audit

Emit/reuse structured audit events:

- LEARNER_REGISTRATION_STARTED
- IDENTITY_CREATED
- EMAIL_VERIFICATION_REQUIRED
- EMAIL_VERIFIED
- MOBILE_OTP_SENT
- MOBILE_VERIFICATION_FAILED (reason category, no OTP)
- MOBILE_VERIFIED
- PROFESSIONAL_PROFILE_COMPLETED
- LEARNING_JOURNEY_CREATED
- ONBOARDING_COMPLETED

Carry interactionId/traceId according to RAMALS conventions. Event payloads must be schema-controlled and PII-minimized.

## 14. OpenAPI

Update/generate OpenAPI using repository conventions. Contracts must document authentication, idempotency header, 400/401/403/409/422/429/5xx behavior, examples without real PII/secrets, and state-transition conflicts.