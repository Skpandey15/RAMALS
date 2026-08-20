# API contracts

Two API surfaces, with different authorities and different owners.

## The AI internal contract

`contracts/ai-internal.openapi.yaml` is the single source of truth for the Java ↔ Python boundary
(M1-ADR-002). Python models are generated from it and Java records are hand-written and validated
against it by golden round-trip fixtures, so neither side can drift silently. `contracts/baseline/`
holds the released v1 for backward-compatibility checking, and `contracts/golden/` the fixtures.

CI enforces all of it: OpenAPI validation, generated-model drift, and contract compatibility run on
every change under `contracts/`.

Every agent endpoint requires the workload identity from M1-ADR-003. A caller without a bearer token
receives `401 WORKLOAD_AUTHENTICATION_REQUIRED`, pinned on the Python side by
`test_every_agent_route_requires_workload_identity` and on the Java side by
`AiWorkloadAuthenticationContractTests`.

## The learner-facing platform API

Served by the learning platform under `/api/v1`, and specified by the MVP-0 design package rather
than by an OpenAPI document. The route groups are `/api/v1/curricula`, `/api/v1/diagnostics`,
`/api/v1/me` (with `/mastery`, `/progression` and `/learning-sessions`),
`/api/v1/admin/curricula` and `/api/v1/system`.

All of it is authenticated, ownership-scoped through `/me` so cross-learner access is unaddressable,
and returns `application/problem+json` carrying `interactionId` and `traceId` on every handled
failure. Answer keys are server-only and are selected by no learner-facing read path.

### Curriculum graph

- `GET /api/v1/curricula/{domainCode}/versions/{versionCode}/skills`
- `GET /api/v1/curricula/{domainCode}/versions/{versionCode}/skills/{skillCode}/prerequisites`

Both require an authenticated learner, instructor or content-author role and return only published
or retired versioned curriculum data. Unknown versions and skill codes return the safe
`CURRICULUM_NOT_FOUND` problem code.
