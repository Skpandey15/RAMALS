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

# -- the environment label has to be earned ---------------------------------------------------------
#
# R1's failure mode is not a bad number, it is a good-looking number nobody can place. RAMALS_PERF_ENV
# used to be copied straight into the baseline, so setting it to a qualified id on a laptop produced
# a file that claimed to be calibrated. Provisioning the right machine would not have fixed that --
# the claim was never checked against anything.
#
# So the run attests the host first. Anything other than 'local-unqualified' has to conform to the
# spec of that name, and a run that does not conform keeps the honest label whatever the operator
# asked for. Downgrading rather than failing is deliberate: an informational run is useful and should
# stay easy, it just must not be able to describe itself as something it was not.
ATTESTATION="${RESULTS}/${SCENARIO}-${STAMP}.attestation.json"
SPEC="${HERE}/environment/${RAMALS_PERF_ENV}.json"

if [ "${RAMALS_PERF_ENV}" = "local-unqualified" ]; then
  echo "Environment: local-unqualified (informational run; no attestation required)"
  ATTESTED=0
elif [ ! -f "${SPEC}" ]; then
  echo "FATAL: RAMALS_PERF_ENV='${RAMALS_PERF_ENV}' names no spec at ${SPEC}." >&2
  echo "       An environment id with no spec behind it is a label, not a claim anybody can check." >&2
  exit 1
elif [ -n "${RAMALS_PERF_ATTESTATION:-}" ]; then
  # The attestation has to describe the system under test, and this script runs wherever k6 runs --
  # which, once the load generator is on its own machine, is not the system under test. Attesting
  # locally here would certify the load generator and say nothing about the thing being measured.
  #
  # So the SUT attests itself and the file is carried over. It is re-validated rather than trusted:
  # a file is easy to edit and easy to keep after the host it describes has changed.
  if python3 "${HERE}/environment/verify-attestation.py" "${RAMALS_PERF_ATTESTATION}" \
       --expect-spec "${RAMALS_PERF_ENV}" \
       --max-age-hours "${RAMALS_PERF_ATTESTATION_MAX_AGE_HOURS:-24}"; then
    cp "${RAMALS_PERF_ATTESTATION}" "${ATTESTATION}"
    ATTESTED=1
  else
    echo
    echo "Recording this run as 'local-unqualified' instead of '${RAMALS_PERF_ENV}'." >&2
    RAMALS_PERF_ENV="local-unqualified"
    export RAMALS_PERF_ENV
    ATTESTED=0
  fi
else
  # No attestation was supplied, so this run must be measuring the machine it is on. That is the
  # single-host arrangement, which the spec refuses anyway -- the load-generator check below fails
  # and the run is downgraded, which is the correct outcome rather than an inconvenience.
  off_host_flag=""
  [ "${RAMALS_PERF_LOAD_GENERATOR_OFF_HOST:-false}" = "true" ] && \
    off_host_flag="--load-generator-off-host"

  if python3 "${HERE}/environment/attest.py" --spec "${SPEC}" --out "${ATTESTATION}" \
       --require ${off_host_flag}; then
    ATTESTED=1
  else
    echo
    echo "Recording this run as 'local-unqualified' instead of '${RAMALS_PERF_ENV}'." >&2
    RAMALS_PERF_ENV="local-unqualified"
    export RAMALS_PERF_ENV
    ATTESTED=0
  fi
fi
export ATTESTATION ATTESTED

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

# A failed threshold must still produce a baseline.
#
# k6 exits non-zero when a threshold is breached, and this script runs under `set -e`. So the run
# that most deserves an immutable artefact -- the one that failed -- was the only run that never
# got one. R1 Run A drove 12,417 requests, sustained the canonical rate for its full duration, and
# produced a summary and an attestation but no baseline, because the error-rate threshold was
# crossed and the distillation below never executed. Its evidence survives only because it was
# captured by hand afterwards.
#
# The exit status is preserved and re-raised at the end, so callers and CI still see the failure.
# What changes is that the measurement is written down first.
K6_STATUS=0
${RAMALS_K6_CMD:-k6} run --summary-export "${SUMMARY}" "${SCRIPT}" || K6_STATUS=$?
export K6_STATUS

if [ "${K6_STATUS}" -ne 0 ]; then
  echo
  echo "k6 exited ${K6_STATUS} (a threshold was breached, or the run failed). Distilling the"
  echo "baseline anyway: a FAIL is a result, and discarding it loses the measurement."
fi

# Whether the performance rate-limit override was in force. A run made with
# compose.perf-override.yml measures application capacity above the infrastructure protection
# ceiling, which is a different question from what the platform does under its own policy -- and
# the two are indistinguishable in a baseline file that does not say which was which.
export RAMALS_PERF_RATE_LIMIT_OVERRIDE="${RAMALS_PERF_RATE_LIMIT_OVERRIDE:-false}"

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
    # Present only when the host was attested against a spec. Its absence is itself the
    # answer to "was this calibrated": there is no way to record a qualified id without it.
    **(
        {"environment_attestation": json.load(open(os.environ["ATTESTATION"]))}
        if os.environ.get("ATTESTED") == "1"
        else {}
    ),
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
    # The verdict, recorded rather than implied by the file's existence. Before this, a baseline
    # existed only when every threshold passed, so "there is a baseline" and "the run passed" were
    # the same statement -- and a failing run left nothing behind to disagree with.
    "thresholds_passed": os.environ.get("K6_STATUS") == "0",
    "k6_exit_status": int(os.environ.get("K6_STATUS", "0")),
    # What policy was in force. A capacity-characterisation run and a production-policy run produce
    # very different numbers from an identical workload; without this they are indistinguishable
    # once the console output is gone.
    "performance_rate_limit_override": os.environ.get("RAMALS_PERF_RATE_LIMIT_OVERRIDE") == "true",
}

if baseline["latency_ms"]["p95"] is None:
    sys.exit("FATAL: no p95 in the k6 summary — refusing to write a baseline of nulls")

with open(baseline_path, "w") as handle:
    json.dump(baseline, handle, indent=2)
PYEOF

echo "Wrote baseline: ${BASELINE}"

# Re-raise k6's verdict now that the measurement is safely on disk. Callers and CI see exactly the
# status they saw before; the difference is that there is now a file to look at when they do.
if [ "${K6_STATUS}" -ne 0 ]; then
  echo "Run recorded, and it FAILED: k6 exited ${K6_STATUS}. See ${BASELINE}"
  exit "${K6_STATUS}"
fi
