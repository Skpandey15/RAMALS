#!/usr/bin/env bash
# Run one k6 scenario and emit a machine-readable baseline JSON with stable environment metadata,
# conforming to baselines/baseline.schema.json. This produces a reproducible baseline; it does not
# assert a production SLA. Requires: k6, python3.
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
export RAMALS_LOAD_LEARNERS="${RAMALS_LOAD_LEARNERS:-20}"

export SCENARIO STAMP
DB_VERSION="$(psql "${RAMALS_DB_URL:-}" -tAc 'SHOW server_version' 2>/dev/null || echo unknown)"
JVM_VERSION="$(java -version 2>&1 | head -1 | tr -d '"' || echo unknown)"
export DB_VERSION JVM_VERSION

# The shipped realm has direct access grants disabled and no users, so the scenarios cannot
# authenticate against a stock deployment. Provision fixtures for the run and always restore the
# original posture, including on failure or interrupt — the committed realm is never modified.
# Set RAMALS_SKIP_FIXTURES=1 in environments where the load learners are managed out of band.
if [ "${RAMALS_SKIP_FIXTURES:-0}" != "1" ]; then
  "${HERE}/fixtures.sh" provision
  trap '"${HERE}/fixtures.sh" restore || true' EXIT INT TERM
fi

echo "Running k6 scenario '${SCENARIO}' (env=${RAMALS_PERF_ENV}, commit=${RAMALS_COMMIT}, \
learners=${RAMALS_LOAD_LEARNERS})"
${RAMALS_K6_CMD:-k6} run --summary-export "${SUMMARY}" "${SCRIPT}"

# Scrub, then distil. k6 embeds setup() output in the summary export, and setup() returns access
# tokens — so the raw summary carries live bearer credentials into a file meant to be archived.
# Strip that before anything else touches the file.
#
# The percentile layout also differs across k6 versions: older exports nest values under `.values`,
# 0.5x writes them flat on the metric. Reading only one shape silently yields null latencies in
# every baseline, so both are accepted.
"${PY_BIN:-python3}" - "${SUMMARY}" "${BASELINE}" <<'PYEOF'
import json, os, sys

summary_path, baseline_path = sys.argv[1], sys.argv[2]
with open(summary_path) as handle:
    summary = json.load(handle)

if summary.pop("setup_data", None) is not None:
    with open(summary_path, "w") as handle:
        json.dump(summary, handle, indent=2)
    print("Scrubbed setup_data (contained access tokens) from the exported summary.")

metrics = summary.get("metrics", {})


def stat(metric, key):
    entry = metrics.get(metric)
    if not entry:
        return None
    if isinstance(entry.get("values"), dict):
        entry = entry["values"]
    return entry.get(key)


stamp = os.environ["STAMP"]
baseline = {
    "scenario": os.environ["SCENARIO"],
    "executor_model": "closed" if os.environ["SCENARIO"] == "concurrency-idempotency" else "open",
    "environment": os.environ["RAMALS_PERF_ENV"],
    "commit": os.environ["RAMALS_COMMIT"],
    "dataset_version": os.environ["RAMALS_DATASET_VERSION"],
    "script_version": "mvp0-perf-harness-v1",
    "measured_at": f"{stamp[0:4]}-{stamp[4:6]}-{stamp[6:11]}:{stamp[11:13]}:{stamp[13:15]}Z",
    "warmup_discarded": True,
    "steady_state": {"window": "documented per scenario"},
    "host": {"jvm": os.environ.get("JVM_VERSION", "unknown"),
             "db_version": os.environ.get("DB_VERSION", "unknown")},
    "latency_ms": {"p50": stat("http_req_duration", "med"),
                   "p95": stat("http_req_duration", "p(95)"),
                   "p99": stat("http_req_duration", "p(99)")},
    "adaptive_decision_latency_ms": (
        {"p50": stat("adaptive_decision_latency", "med"),
         "p95": stat("adaptive_decision_latency", "p(95)"),
         "p99": stat("adaptive_decision_latency", "p(99)")}
        if "adaptive_decision_latency" in metrics else None
    ),
    "error_rate": stat("http_req_failed", "value") or stat("http_req_failed", "rate") or 0,
    "throughput_rps": stat("http_reqs", "rate"),
}

if baseline["latency_ms"]["p95"] is None:
    sys.exit("FATAL: no p95 in the k6 summary — refusing to write a baseline of nulls")

with open(baseline_path, "w") as handle:
    json.dump(baseline, handle, indent=2)
PYEOF

echo "Wrote baseline: ${BASELINE}"
