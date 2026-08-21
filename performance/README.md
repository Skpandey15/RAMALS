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
export RAMALS_PERF_ENV=perf-standard-01   # qualified env id; the run must earn it (see below)
export RAMALS_PERF_LOAD_GENERATOR_OFF_HOST=true   # assert k6 runs on a different machine

# If published ports are not reachable from the host, run provisioning inside the compose network:
export RAMALS_FIXTURE_NETWORK=ramals-deploy_edge

## The environment id has to be earned

`RAMALS_PERF_ENV` used to be copied straight into the baseline, so setting it to a qualified id on a
laptop produced a file that claimed to be calibrated. That is R1's failure mode, and provisioning
the right machine would not have fixed it -- the claim was never checked against anything.

Anything other than `local-unqualified` now names a spec in `environment/`, and the run attests the
host against it first:

```bash
python3 performance/environment/attest.py            # what this host is, and what it lacks
python3 performance/environment/attest.py --require  # non-zero unless it conforms
```

A run on a non-conforming host is still useful and still easy -- it is simply recorded as
`local-unqualified`, whatever the operator asked for, and the baseline carries no attestation. A
qualified id is only written alongside the attestation that earned it, and `baseline.schema.json`
rejects a baseline where those two disagree.

### Two hosts, so the attestation has to travel

Once the load generator is on its own machine, `run-baseline.sh` is no longer running on the host it
measures — so it cannot attest that host itself. The system under test attests itself and the file is
carried:

```bash
ssh <sut> 'python3 performance/environment/attest.py --require --load-generator-off-host --out /tmp/a.json'
scp <sut>:/tmp/a.json ./attestation.json
export RAMALS_PERF_ATTESTATION=./attestation.json
```

The runner re-checks it rather than believing it — right spec, records conformance, recent enough —
and downgrades the run to `local-unqualified` if any of that fails. `RAMALS_PERF_ATTESTATION_MAX_AGE_HOURS`
sets the staleness bound, 24 by default: a host that conformed last month may have been resized since,
and a file cannot notice that on its own.

Provisioning both machines — [`RUNBOOK-aws.md`](environment/RUNBOOK-aws.md) covers creating them on
AWS, including the burstable-instance trap the attestation cannot catch:

```bash
bash performance/environment/provision-sut.sh       # on the system under test
bash performance/environment/provision-loadgen.sh   # on the load generator
```

One requirement cannot be measured from inside the run: whether the load generator is on the host it
is measuring. It is asserted with `RAMALS_PERF_LOAD_GENERATOR_OFF_HOST` and recorded as an
assertion, so a reader can see which it was.

Pin the system under test to the spec's resources when making a qualified run:

```bash
docker compose -f deploy/compose.deploy.yml -f performance/compose.perf-fixed.yml up -d
```

./run-baseline.sh diagnostic          # ADL baseline
./run-baseline.sh mixed-learning      # authoritative whole-system SLO
k6 run scenarios/auth.js              # JWT/JWKS overhead
k6 run scenarios/concurrency-idempotency.js
RAMALS_DB_URL=postgresql://ramals_core_runtime@localhost:5432/ramals ./db/run-db-benchmarks.sh
```

## Rate limiting and load generation

Rate limiting has two tiers. A pre-authentication ceiling keyed on **client IP** sheds floods before
any JWT is validated, and a post-authentication tier keyed on the **verified token subject** enforces
per-learner fair use.

Provision enough learners (`RAMALS_LOAD_LEARNERS`) that each stays under the fair-use limit, and a
single-source load generator runs clean: the IP tier is sized for floods, not for a cohort.

Before R9 the bucket was keyed on IP alone, so every simulated learner drew on one allowance and a
60 rps run returned roughly **16% 429s** regardless of how many identities the harness used. If you
ever see that shape again, the giveaway is that p95 stays flat while a large fraction of traffic is
rejected — nothing like saturation.

To probe above the IP ceiling itself, bring the stack up with the override:

```bash
docker compose -f deploy/compose.deploy.yml -f performance/compose.perf-override.yml up -d
```

It raises **both** tiers — raising only the IP tier leaves the subject tier throttling each simulated
learner. It materially weakens a security control and is strictly a load-generation aid: never apply
it to a real environment, and label any run made with it in the baseline metadata.

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
