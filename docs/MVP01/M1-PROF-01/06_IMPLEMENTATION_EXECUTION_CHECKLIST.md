# M1-PROF-01 Implementation Execution Checklist

This is the coding handoff checklist for Claude/Codex. Read all sibling documents before implementation.

## Gate 0 — discover before editing

- [ ] Inventory existing identity/learner/profile entities and migrations.
- [ ] Inventory Keycloak realm, clients, learner role, email verification and service-account permissions.
- [ ] Inventory current login UI/routing and auth client.
- [ ] Inventory error, audit, interactionId/traceId and idempotency patterns.
- [ ] Inventory rate-limit/security dependencies already present.
- [ ] Map planned concepts to existing code; document reuse vs new code.
- [ ] Confirm no AWS/T15/Contract-B/mastery redesign is required.

## Gate 1 — schema

- [ ] Add only necessary additive Flyway migration(s).
- [ ] External Keycloak subject unique.
- [ ] Mobile normalized E.164.
- [ ] Verified-mobile uniqueness is DB-enforced safely.
- [ ] OTP challenge table/state contains no plaintext OTP.
- [ ] Profile and journey models follow existing IDs/audit/version conventions.
- [ ] Index challenge and journey access paths.
- [ ] Define legacy learner compatibility/backfill.

## Gate 2 — backend identity

- [ ] Implement/reuse IdentityProviderPort for Keycloak.
- [ ] Registration assigns learner only; role is server-controlled.
- [ ] Keycloak partial-failure reconciliation implemented.
- [ ] Registration idempotency implemented.
- [ ] Email verification trusted from Keycloak, not browser.
- [ ] Server lifecycle transition guards implemented.

## Gate 3 — mobile verification

- [ ] Mature phone parser/normalizer.
- [ ] CSPRNG OTP.
- [ ] Keyed OTP hash/HMAC with externalized key.
- [ ] TTL.
- [ ] attempt ceiling.
- [ ] resend cooldown/window limits.
- [ ] layered rate limits.
- [ ] supersede old challenge on resend.
- [ ] single-use consume.
- [ ] concurrent verify safe.
- [ ] verified mobile uniqueness race safe.
- [ ] fake DEV sender.
- [ ] production sender port/config fail-closed.
- [ ] no OTP logs/responses.

## Gate 4 — professional profile/journey

- [ ] Current role.
- [ ] Experience band.
- [ ] Primary expertise.
- [ ] Technologies known.
- [ ] Declared skill level explicitly non-authoritative.
- [ ] Goal type.
- [ ] Target role.
- [ ] Selected domains.
- [ ] Learning intensity.
- [ ] Weekly availability.
- [ ] Kafka not default/preselected.
- [ ] Journey creation idempotent.
- [ ] `/me` ownership enforced.

## Gate 5 — frontend

- [ ] Login page has Create Account.
- [ ] Account form includes mandatory mobile.
- [ ] Email verification screen/state.
- [ ] Mobile OTP screen with resend countdown/expiry/errors.
- [ ] Professional profile step.
- [ ] Learning goal/domain step.
- [ ] Review/start step.
- [ ] Server-driven resume after refresh/relogin.
- [ ] No auth/business state in browser localStorage beyond existing safe architecture.
- [ ] Accessible labels/errors/focus and keyboard behavior.
- [ ] Sensitive values not exposed unnecessarily.

## Gate 6 — tests

- [ ] Unit tests.
- [ ] Real PostgreSQL integration tests.
- [ ] Keycloak integration tests.
- [ ] Provider adapter tests.
- [ ] API authorization/IDOR tests.
- [ ] Role escalation negative tests.
- [ ] OTP wrong/expired/replay/resend/max-attempt tests.
- [ ] Rate-limit tests.
- [ ] Concurrency tests for OTP/mobile uniqueness/idempotency.
- [ ] Partial failure/recovery tests.
- [ ] Frontend tests.
- [ ] E2E onboarding.
- [ ] Existing RAMALS regression tests.

## Gate 7 — observability/security

- [ ] Audit events implemented.
- [ ] interactionId/traceId propagated.
- [ ] PII-safe structured logs.
- [ ] No password/OTP/token/provider secret logs.
- [ ] Low-cardinality metrics.
- [ ] Dependency latency/outcome metrics.
- [ ] Registration/OTP abuse metrics.
- [ ] Secret scanning passes.
- [ ] AI→Postgres isolation unchanged.

## Gate 8 — performance/reliability

- [ ] API performance baseline recorded.
- [ ] External dependency timeout bounded.
- [ ] Retry only safe/idempotent operations.
- [ ] DB pool/thread behavior validated under load.
- [ ] OTP abuse load cannot amplify sends without bound.
- [ ] Restart/recovery from intermediate onboarding states tested.

## Gate 9 — environment/configuration

- [ ] DEV works with fake/non-billable sender.
- [ ] CI deterministic.
- [ ] production cannot accidentally use fake sender.
- [ ] production secrets externalized.
- [ ] feature can be operationally disabled if rollout requires it.
- [ ] no unrelated AWS/T15 changes.

## Gate 10 — final qualification evidence

Report exact evidence, not inference:

- [ ] new learner registration success
- [ ] email verification success
- [ ] mandatory mobile verification success
- [ ] OTP abuse/replay negative controls
- [ ] learner-only role
- [ ] professional profile persisted
- [ ] learning journey created
- [ ] Kafka not default
- [ ] interrupted onboarding resumes
- [ ] duplicate/race tests pass
- [ ] security log scan clean
- [ ] performance results
- [ ] existing deterministic learning regression green
- [ ] CI green
- [ ] real production SMS provider qualification, or explicitly `NOT VERIFIED` and production blocked

## PR discipline

Keep implementation PR(s) reviewable. It is acceptable and preferred to split implementation into sequential PRs (schema/backend foundation, mobile verification, onboarding UI/journey, hardening/qualification) when a single PR becomes too large. Each PR must preserve a buildable/testable repository and must not weaken security temporarily on main.

Do not merge automatically. Final review verdict should be MERGE / FIX / BLOCKED / WAIT FOR CI based on evidence.