# ramals-ai — MVP-1 AI execution plane

Non-authoritative agent runtime for the RAMALS deterministic core. **This service proposes; it never
decides.**

Spring Boot remains the authoritative system of record for learner state, mastery, confidence,
progression and evidence. Nothing here can change any of them.

## What exists today (through M1-T12)

The service includes bounded proposal agents, authenticated internal routes, workload identity,
correlation and tracing, governed model routing, and limited-durable approval support in the
platform core. AI output remains non-authoritative.

| Endpoint | Purpose |
| --- | --- |
| `GET /health/live` | Process is alive. Checks nothing else |
| `GET /health/ready` | Safe to route traffic. `503 OUT_OF_SERVICE` until startup completes |
| `GET /capabilities` | Build, environment, enabled route, and `authority: NON_AUTHORITATIVE` |

S0-06 activated the authenticated Diagnostic, Tutor, and Assessment proposal routes. M1-T11 adds
the Adaptation proposal route. `/capabilities` reports only the routes actually served, and all
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
| `RAMALS_AI_AI_ENABLED` | `false` | Safe default; set `true` only with a governed live route and provider credential |
| `RAMALS_AI_MODEL_ROUTE` | `ci-fake` | Route names are governed by Doc 04 |
| `RAMALS_AI_PROVIDER_API_KEY` | *unset* | Required only for a live route; never logged |
| `RAMALS_AI_REQUEST_TIMEOUT_SECONDS` | `12.0` | Within the Doc 01 INTERACTIVE_AI deadline |
| `RAMALS_AI_LOG_LEVEL` | `INFO` | |
| `RAMALS_AI_LANGFUSE_TRACING_ENABLED` | `false` | Turns on LLM call tracing to Langfuse (below) |

Startup fails with an explicit `ConfigurationError` rather than degrading — a live model route with
no credential would otherwise surface as an opaque provider error long after deployment.

## LLM call tracing (Langfuse)

Every live-route call goes through LiteLLM (`gateway/providers/litellm_adapter.py`), which ships its
own `langfuse_otel` callback — a purpose-built OTLP exporter to
[Langfuse](https://github.com/langfuse/langfuse) (MIT-licensed; only its `ee/` directory is separate
commercial code, and this integration never touches it). Enabling it needs no code change, only
configuration:

| Variable | Default | Notes |
| --- | --- | --- |
| `RAMALS_AI_LANGFUSE_TRACING_ENABLED` | `false` | Off by default; requires both keys below when `true` |
| `LANGFUSE_PUBLIC_KEY` | *unset* | Project public key from the Langfuse UI. **Not** `RAMALS_AI_`-prefixed — LiteLLM's own integration and every Langfuse SDK read this exact name |
| `LANGFUSE_SECRET_KEY` | *unset* | Project secret key; never logged |
| `LANGFUSE_HOST` | Langfuse's own default (US cloud) | Point this at a self-hosted instance, e.g. `http://localhost:3000` |

Startup fails the same way a live route without a provider key does: `langfuse_tracing_enabled=true`
without both keys raises `ConfigurationError` rather than silently tracing nothing.

Each traced call carries `metadata.trace_id` set to `ProviderRequest.request_id` — the same stable
request identity `BusinessEventLogger`'s structured logs already carry (M1-T04) — so a Langfuse trace
and a RAMALS log line for the same request can be correlated by that id.

**Data captured is a deliberate policy decision, not a default:** this integration sends full,
unredacted prompts and completions to Langfuse. That is the opposite of `BusinessEventLogger`, which
redacts prompt/answer/content fields in its own structured logs. The divergence is intentional —
Langfuse is a debugging tool and its value depends on seeing what the model actually saw and said —
but it means enabling tracing sends raw learner-facing content to wherever `LANGFUSE_HOST` points.
Treat that host (and its credentials) with the same care as a system that stores learner content,
because it is one.

### Running Langfuse locally

Langfuse is not part of `ramals-ai`'s own container or the main `infrastructure/docker/compose.yml`
(which does not include `ramals-ai` either — see that file). It ships as its own standalone compose
stack, adapted from
[Langfuse's published compose file](https://github.com/langfuse/langfuse/blob/main/docker-compose.yml)
to this repo's required-secret conventions:

```bash
docker compose -f infrastructure/docker/compose.langfuse.yml --env-file .env up -d
```

It needs `LANGFUSE_DB_PASSWORD`, `LANGFUSE_SALT`, `LANGFUSE_ENCRYPTION_KEY`,
`LANGFUSE_CLICKHOUSE_PASSWORD`, `LANGFUSE_REDIS_AUTH`, `LANGFUSE_MINIO_ROOT_PASSWORD`, and
`LANGFUSE_NEXTAUTH_SECRET` set in `.env` — startup refuses to come up otherwise. Once it's running,
open `http://localhost:3000`, create a project, and use its public/secret key pair as
`LANGFUSE_PUBLIC_KEY`/`LANGFUSE_SECRET_KEY` above (or pre-seed that same project via the compose
file's `LANGFUSE_INIT_*` variables to skip the manual signup step).

## Container

```bash
docker build -f ramals-ai/Dockerfile -t ramals-ai:dev .   # from the repository root
```

Runs as uid `10001`, non-root, with the base image pinned by digest. The `HEALTHCHECK` probes
liveness only; readiness is the orchestrator's decision.
