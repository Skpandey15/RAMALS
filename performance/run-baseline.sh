#!/usr/bin/env bash
# Run one k6 scenario and emit a machine-readable baseline JSON with stable environment metadata,
# conforming to baselines/baseline.schema.json. This produces a reproducible baseline; it does not
# assert a production SLA. Requires: k6, jq.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCENARIO="${1:-mixed-learning}"
SCRIPT="${HERE}/scenarios/${SCENARIO}.js"
[ -f "${SCRIPT}" ] || { echo "unknown scenario: ${SCENARIO}"; exit 1; }

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RESULTS="${HERE}/results"
mkdir -p "${RESULTS}"
SUMMARY="${RESULTS}/${SCENARIO}-${STAMP}.summary.json"
BASELINE="${RESULTS}/${SCENARIO}-${STAMP}.baseline.json"

export RAMALS_COMMIT="${RAMALS_COMMIT:-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)}"
export RAMALS_PERF_ENV="${RAMALS_PERF_ENV:-local-unqualified}"
export RAMALS_DATASET_VERSION="${RAMALS_DATASET_VERSION:-mvp0-baseline-v1}"

DB_VERSION="$(psql "${RAMALS_DB_URL:-}" -tAc 'SHOW server_version' 2>/dev/null || echo unknown)"
JVM_VERSION="$(java -version 2>&1 | head -1 | tr -d '"' || echo unknown)"

echo "Running k6 scenario '${SCENARIO}' (env=${RAMALS_PERF_ENV}, commit=${RAMALS_COMMIT})"
k6 run --summary-export "${SUMMARY}" "${SCRIPT}"

# Distil the k6 summary into the baseline schema. http_req_duration percentiles are the whole-run
# latency; per-class and ADL breakdowns live in the full summary.
jq -n \
  --arg scenario "${SCENARIO}" \
  --arg env "${RAMALS_PERF_ENV}" \
  --arg commit "${RAMALS_COMMIT}" \
  --arg dataset "${RAMALS_DATASET_VERSION}" \
  --arg measured "${STAMP}" \
  --arg db "${DB_VERSION}" \
  --arg jvm "${JVM_VERSION}" \
  --slurpfile s "${SUMMARY}" '
  {
    scenario: $scenario,
    executor_model: (if $scenario == "concurrency-idempotency" then "closed" else "open" end),
    environment: $env,
    commit: $commit,
    dataset_version: $dataset,
    script_version: "mvp0-perf-harness-v1",
    measured_at: ($measured | sub("(?<y>\\d{4})(?<m>\\d{2})(?<d>\\d{2})T(?<H>\\d{2})(?<M>\\d{2})(?<S>\\d{2})Z"; "\(.y)-\(.m)-\(.d)T\(.H):\(.M):\(.S)Z")),
    warmup_discarded: true,
    steady_state: { window: "documented per scenario" },
    host: { jvm: $jvm, db_version: $db },
    latency_ms: {
      p50: ($s[0].metrics.http_req_duration.values["p(50)"] // null),
      p95: ($s[0].metrics.http_req_duration.values["p(95)"] // null),
      p99: ($s[0].metrics.http_req_duration.values["p(99)"] // null)
    },
    adaptive_decision_latency_ms: (
      if $s[0].metrics.adaptive_decision_latency then {
        p50: $s[0].metrics.adaptive_decision_latency.values["p(50)"],
        p95: $s[0].metrics.adaptive_decision_latency.values["p(95)"],
        p99: $s[0].metrics.adaptive_decision_latency.values["p(99)"]
      } else null end
    ),
    error_rate: ($s[0].metrics.http_req_failed.values.rate // 0),
    throughput_rps: ($s[0].metrics.http_reqs.values.rate // null)
  }' > "${BASELINE}"

echo "Wrote baseline: ${BASELINE}"
