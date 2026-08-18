# ramals-ai — MVP-1 AI execution plane

Non-authoritative agent runtime for the RAMALS deterministic core. **This service proposes; it never
decides.**

Spring Boot remains the authoritative system of record for learner state, mastery, confidence,
progression and evidence. Nothing here can change any of them.

## What exists today (M1-T01)

The service foundation only — no agents, no model calls, no orchestration.

| Endpoint | Purpose |
| --- | --- |
| `GET /health/live` | Process is alive. Checks nothing else |
| `GET /health/ready` | Safe to route traffic. `503 OUT_OF_SERVICE` until startup completes |
| `GET /capabilities` | Build, environment, enabled route, and `authority: NON_AUTHORITATIVE` |

S0-06 activates the authenticated Diagnostic, Tutor, and Assessment proposal routes. Adaptation
remains unavailable until M1-T11. `/capabilities` reports only the routes actually served, and all
responses are non-authoritative proposals that Spring must validate before any authoritative write.

## Boundaries this service is built inside

- **No database access.** There is no PostgreSQL driver and no ORM, asserted by
  `tests/unit/test_no_database_access.py`. Learner context arrives through the platform API under
  the service's own authorisation, never through SQL.
- **No migrations.** Flyway under `ramals_core_migration` is the sole DDL authority for the shared
  database. A second migration chain from Python would fork schema ownership.
- **No credential required to run.** AI execution is disabled by default on the deterministic
  `ci-fake` route, so a fresh checkout and CI both run with no secrets.
- **Independent health.** `ramals-ai` being unavailable must never make the deterministic core
  report unready (Doc 06 §4).

At the database layer the boundary is already enforced and proven: the `ramals_ai_runtime` role
holds no privilege on `core` or `ledger`, verified by `AiRuntimeBoundaryIntegrationTests` (`42501`).

## Running it

```bash
cd ramals-ai
python -m venv .venv && ./.venv/Scripts/python -m pip install -e ".[dev]"
./.venv/Scripts/python -m pytest -q
./.venv/Scripts/python -m uvicorn ramals_ai.main:create_app --factory --port 8000
```

Checks, exactly as CI runs them:

```bash
python -m ruff check . && python -m ruff format --check . && python -m mypy && python -m pytest -q --cov
```

## Configuration

Environment-driven, prefix `RAMALS_AI_`. Unknown variables are rejected — a typo is a
misconfiguration, not something to ignore.

| Variable | Default | Notes |
| --- | --- | --- |
| `RAMALS_AI_ENVIRONMENT` | `local` | `local`, `dev`, `test` |
| `RAMALS_AI_AI_ENABLED` | `false` | Model execution off until M1-T05 |
| `RAMALS_AI_MODEL_ROUTE` | `ci-fake` | Route names are governed by Doc 04 |
| `RAMALS_AI_PROVIDER_API_KEY` | *unset* | Required only for a live route; never logged |
| `RAMALS_AI_REQUEST_TIMEOUT_SECONDS` | `12.0` | Within the Doc 01 INTERACTIVE_AI deadline |
| `RAMALS_AI_LOG_LEVEL` | `INFO` | |

Startup fails with an explicit `ConfigurationError` rather than degrading — a live model route with
no credential would otherwise surface as an opaque provider error long after deployment.

## Container

```bash
docker build -f ramals-ai/Dockerfile -t ramals-ai:dev .   # from the repository root
```

Runs as uid `10001`, non-root, with the base image pinned by digest. The `HEALTHCHECK` probes
liveness only; readiness is the orchestrator's decision.
