# RAMALS MVP-0 performance harness

A reproducible performance **baseline** for MVP-0 — not an unqualified SLA. Targets are engineering
objectives to be calibrated on the authoritative fixed-spec environment; a single run on a noisy
shared runner is informational only.

## Layout

```
performance/
├── lib/            config.js (env-driven), auth.js (OIDC token, idempotency keys)
├── scenarios/      k6 scripts (see below)
├── thresholds/     mvp0.json — request-class p95 budgets, ADL targets, error/correctness gates
├── baselines/      baseline.schema.json + baseline.example.json (machine-readable results)
├── db/             explain-analyze.sql + run-db-benchmarks.sh (hot-path plans)
├── run-baseline.sh orchestrates a scenario -> results/<scenario>.baseline.json with env metadata
└── results/        run outputs (git-ignored; archive published baselines)
```

## Scenarios and executor semantics

The matrix mandates explicit executor models. **Open** (arrival-rate) is authoritative for SLO
latency because it decouples request start from completion and avoids coordinated-omission bias.
**Closed** (fixed-VU) models are valid for concurrency/saturation/soak and are labeled as such.

| Scenario | Executor | Model | Measures |
| --- | --- | --- | --- |
| `diagnostic.js` | `constant-arrival-rate` | open | **Adaptive Decision Latency** — submission → mastery snapshot + recommendation (computed synchronously in the submit request) |
| `mixed-learning.js` | `ramping-arrival-rate` | open | authoritative whole-system SLO under the documented request-class mix (35% skill-map, 20% content, 15% submit, 10% diagnostic, 10% recommendation, 5% mastery, 5% auth) |
| `auth.js` | `constant-arrival-rate` | open | **JWT/JWKS** per-request validation overhead (JWT-validated endpoint vs unauthenticated health; token issuance measured once in `setup()`) |
| `concurrency-idempotency.js` | `per-vu-iterations` | closed | correctness under load: retried attempt creation and duplicate submit never corrupt state |

Every scenario records executor model, arrival rate, observed concurrency, warm-up discard, and the
steady-state window (see thresholds and the baseline schema).

## Running

Prerequisites: [k6](https://k6.io), `python3`, and a running platform + Keycloak. Load learners do
**not** need to be seeded in advance — `run-baseline.sh` provisions them at runtime via
`fixtures.sh` and restores the realm afterwards, including on failure or interrupt. All
configuration is environment-driven (`performance/lib/config.js`).

The shipped realm has direct access grants disabled on `ramals-web-ui` and defines no users, so
this provisioning step is what makes the scenarios runnable at all. The committed realm is never
modified.

```bash
export RAMALS_BASE_URL=http://localhost:8080
export RAMALS_TOKEN_URL=http://localhost:8081/realms/ramals/protocol/openid-connect/token
export RAMALS_KEYCLOAK_ADMIN=admin RAMALS_KEYCLOAK_ADMIN_PASSWORD=***
export RAMALS_LOAD_PASSWORD=***          # load-only credential, never a real one
export RAMALS_LOAD_LEARNERS=20           # VUs are spread across this pool of distinct learners
export RAMALS_PERF_ENV=perf-standard-01   # authoritative env id; leave default for informational runs

# If published ports are not reachable from the host, run provisioning inside the compose network:
export RAMALS_FIXTURE_NETWORK=ramals-deploy_edge

./run-baseline.sh diagnostic          # ADL baseline
./run-baseline.sh mixed-learning      # authoritative whole-system SLO
k6 run scenarios/auth.js              # JWT/JWKS overhead
k6 run scenarios/concurrency-idempotency.js
RAMALS_DB_URL=postgresql://ramals_core_runtime@localhost:5432/ramals ./db/run-db-benchmarks.sh
```

## The rate limiter will throttle a single-source load generator

`RateLimitFilter` keys its token bucket on the **client IP**, not the authenticated subject. A load
generator is one IP, so every simulated learner shares one bucket no matter how many identities the
harness authenticates as. At the shipped defaults (`capacity 120`, `refill 60/s`) a 60 rps run sits
exactly on the limit and roughly 16% of requests come back **429**.

Those 429s are the limiter working correctly — the giveaway is that p95 stays flat while a sixth of
traffic is rejected, which is nothing like saturation. To measure the application rather than the
limiter, bring the stack up with the override:

```bash
docker compose -f deploy/compose.deploy.yml -f performance/compose.perf-override.yml up -d
```

That override materially weakens a security control and is strictly a load-generation aid — never
apply it to a real environment, and label any run made with it in the baseline metadata.

## Exported summaries are scrubbed

k6 embeds the return value of `setup()` in `--summary-export`, and `setup()` returns access tokens.
Raw exports therefore contain live bearer credentials. `run-baseline.sh` strips `setup_data` before
anything else touches the file, and `results/` is gitignored. If you export a summary by calling k6
directly, scrub it yourself before archiving or sharing it.

## Reproducibility and metadata

Every baseline stamps `commit`, `environment`, `dataset_version`, `script_version`, DB version, JVM,
and the steady-state window (`baselines/baseline.schema.json`). Regressions are judged against a
stored baseline with an agreed tolerance (e.g. >10–15% degradation triggers investigation), not
against arbitrary one-off numbers.

## What this is not

- Not a production availability SLA — MVP-0 Docker Compose is single-host and non-redundant.
- Not a pass/fail gate on shared GitHub-hosted runners until variance is characterized on the
  authoritative environment. Correctness always takes priority over raw throughput.
