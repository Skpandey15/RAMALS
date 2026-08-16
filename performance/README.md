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

Prerequisites: [k6](https://k6.io), `jq`, and a running platform + Keycloak with a seeded
`load-learner`. All configuration is environment-driven (`performance/lib/config.js`).

```bash
export RAMALS_BASE_URL=http://localhost:8080
export RAMALS_TOKEN_URL=http://localhost:8081/realms/ramals/protocol/openid-connect/token
export RAMALS_LOAD_USER=load-learner RAMALS_LOAD_PASSWORD=***
export RAMALS_PERF_ENV=perf-standard-01   # authoritative env id; leave default for informational runs

./run-baseline.sh diagnostic          # ADL baseline
./run-baseline.sh mixed-learning      # authoritative whole-system SLO
k6 run scenarios/auth.js              # JWT/JWKS overhead
k6 run scenarios/concurrency-idempotency.js
RAMALS_DB_URL=postgresql://ramals_core_runtime@localhost:5432/ramals ./db/run-db-benchmarks.sh
```

## Reproducibility and metadata

Every baseline stamps `commit`, `environment`, `dataset_version`, `script_version`, DB version, JVM,
and the steady-state window (`baselines/baseline.schema.json`). Regressions are judged against a
stored baseline with an agreed tolerance (e.g. >10–15% degradation triggers investigation), not
against arbitrary one-off numbers.

## What this is not

- Not a production availability SLA — MVP-0 Docker Compose is single-host and non-redundant.
- Not a pass/fail gate on shared GitHub-hosted runners until variance is characterized on the
  authoritative environment. Correctness always takes priority over raw throughput.
