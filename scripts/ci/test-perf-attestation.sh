#!/usr/bin/env bash
# Tests the R1 performance-environment attestation.
#
# The point of the attestation is that a qualified environment id cannot be asserted, only earned.
# So the cases that matter most are the ones where somebody tries to assert it anyway: a host that
# falls short, a container with no limit, an environment id with no spec behind it. A check that
# only ever sees a conforming host proves nothing about the situation it exists for.
#
# The host measurement is injected rather than taken from a real machine. Every interesting case --
# too little memory, an unpinned container, a service that is not running -- is one no developer
# workstation can be put into on demand, and a suite that could only run on conforming hardware
# could never run in CI, which is where this has to hold.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PERF="${REPO_ROOT}/performance"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

PYTHON="${PYTHON:-python}"
command -v "${PYTHON}" >/dev/null || PYTHON=python3

FAILURES=0
check() { # check <name> <expected> <actual>
  if [ "$2" = "$3" ]; then
    printf 'ok   %s\n' "$1"
  else
    printf 'FAIL %s (expected "%s", got "%s")\n' "$1" "$2" "$3"
    FAILURES=$((FAILURES + 1))
  fi
}

# -- the verdict, against injected measurements -----------------------------------------------------

cat > "${WORK}/evaluate_cases.py" <<'PYEOF'
"""Exercises evaluate() directly, so every non-conforming shape can be tested."""
import importlib.util
import json
import sys
from pathlib import Path

perf = Path(sys.argv[1])
spec = json.loads((perf / "environment" / "perf-standard-01.json").read_text(encoding="utf-8"))

loader = importlib.util.spec_from_file_location("attest", perf / "environment" / "attest.py")
attest = importlib.util.module_from_spec(loader)
loader.loader.exec_module(attest)


def conforming():
    return {
        "host": {
            "cpus": 8,
            "memory_gib": 16.0,
            "os_type": "linux",
            "architecture": "x86_64",
            "operating_system": "Ubuntu",
            "kernel_version": "6.6",
            "docker_version": "27",
            "runtime_host_name": "perf-01",
        },
        "containers": {
            "backend": {"running": True, "cpus": 4.0, "memory_gib": 4.0},
            "postgres": {"running": True, "cpus": 2.0, "memory_gib": 2.0},
        },
        "load_generator_off_host": True,
    }


results = {}


def case(name, mutate):
    measured = conforming()
    mutate(measured)
    results[name] = attest.evaluate(spec, measured)


case("conforming", lambda m: None)
case("too little memory", lambda m: m["host"].update(memory_gib=7.6))
case("too few cpus", lambda m: m["host"].update(cpus=4))
case("wrong architecture", lambda m: m["host"].update(architecture="arm64"))
case("backend unpinned cpu", lambda m: m["containers"]["backend"].update(cpus=None))
case("backend unpinned memory", lambda m: m["containers"]["backend"].update(memory_gib=None))
case("backend wrong cpu limit", lambda m: m["containers"]["backend"].update(cpus=2.0))
case("postgres not running", lambda m: m["containers"].update(postgres={"running": False}))
case("load generator on host", lambda m: m.update(load_generator_off_host=False))

json.dump({name: failures for name, failures in results.items()}, sys.stdout)
PYEOF

VERDICTS="$("${PYTHON}" "${WORK}/evaluate_cases.py" "${PERF}")"

verdict_count() { # verdict_count <case>
  printf '%s' "${VERDICTS}" | "${PYTHON}" -c \
    "import json,sys; print(len(json.load(sys.stdin)[sys.argv[1]]))" "$1"
}

check "a conforming host produces no failures"          "0" "$(verdict_count 'conforming')"
check "too little memory is refused"                    "1" "$(verdict_count 'too little memory')"
check "too few CPUs is refused"                         "1" "$(verdict_count 'too few cpus')"
check "the wrong architecture is refused"               "1" "$(verdict_count 'wrong architecture')"
check "an unpinned CPU limit is refused"                "1" "$(verdict_count 'backend unpinned cpu')"
check "an unpinned memory limit is refused"             "1" "$(verdict_count 'backend unpinned memory')"
check "a limit that differs from the spec is refused"   "1" "$(verdict_count 'backend wrong cpu limit')"
check "a service that is not running is refused"        "1" "$(verdict_count 'postgres not running')"
check "a load generator on the host is refused"         "1" "$(verdict_count 'load generator on host')"

# -- the spec itself --------------------------------------------------------------------------------

status="$("${PYTHON}" -c "
import json,sys
spec=json.load(open(sys.argv[1]))
required = {'id','status','specVersion','host','containers','isolation'}
sys.exit(0 if required <= set(spec) else 1)
" "${PERF}/environment/perf-standard-01.json"; echo $?)"
check "the spec declares everything the attestation reads" "0" "${status}"

status="$("${PYTHON}" -c "
import json,sys
spec=json.load(open(sys.argv[1]))
# 'reference' would claim the values were confirmed by a calibrated run. Until one exists on a
# registered host they are a reasoned starting point, and the spec has to say so.
sys.exit(0 if spec['status'] == 'proposed' else 1)
" "${PERF}/environment/perf-standard-01.json"; echo $?)"
check "the spec does not claim to be confirmed before a run confirms it" "0" "${status}"

# -- the baseline schema ----------------------------------------------------------------------------
#
# The rule R1 exists for, expressed where a reader of a baseline file will meet it: a qualified
# environment id is invalid without the attestation that earned it.

cat > "${WORK}/schema_cases.py" <<'PYEOF'
import json
import sys
from pathlib import Path

import jsonschema

schema = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
example = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))


def valid(document):
    try:
        jsonschema.validate(document, schema)
        return True
    except jsonschema.ValidationError:
        return False


unqualified = dict(example, environment="local-unqualified")
unqualified.pop("environment_attestation", None)

claimed = dict(example, environment="perf-standard-01")
claimed.pop("environment_attestation", None)

attested = dict(
    example,
    environment="perf-standard-01",
    environment_attestation={
        "specId": "perf-standard-01",
        "conforms": True,
        "attestedAt": "2026-08-21T00:00:00+00:00",
        "measured": {"host": {"cpus": 8}},
    },
)

lying = dict(attested)
lying["environment_attestation"] = dict(attested["environment_attestation"], conforms=False)

print(json.dumps({
    "unqualified_without_attestation": valid(unqualified),
    "qualified_without_attestation": valid(claimed),
    "qualified_with_attestation": valid(attested),
    "qualified_with_failed_attestation": valid(lying),
}))
PYEOF

SCHEMA_VERDICTS="$("${PYTHON}" "${WORK}/schema_cases.py" \
  "${PERF}/baselines/baseline.schema.json" "${PERF}/baselines/baseline.example.json")"

schema_says() { printf '%s' "${SCHEMA_VERDICTS}" | "${PYTHON}" -c \
  "import json,sys; print(json.load(sys.stdin)[sys.argv[1]])" "$1"; }

check "an informational baseline needs no attestation"        "True"  "$(schema_says unqualified_without_attestation)"
check "a qualified id without attestation is invalid"         "False" "$(schema_says qualified_without_attestation)"
check "a qualified id with its attestation is valid"          "True"  "$(schema_says qualified_with_attestation)"
check "a qualified id with a FAILED attestation is invalid"   "False" "$(schema_says qualified_with_failed_attestation)"

# -- the runner refuses an id with no spec behind it -------------------------------------------------

grep -q 'names no spec at' "${PERF}/run-baseline.sh"
check "the runner refuses an environment id that names no spec" "0" "$?"

grep -q "RAMALS_PERF_ENV=\"local-unqualified\"" "${PERF}/run-baseline.sh"
check "a non-conforming run is downgraded rather than mislabelled" "0" "$?"

# -- an attestation that travels is re-checked, not believed -----------------------------------------
#
# Once the load generator is on its own machine, run-baseline.sh is not running on the host it
# measures, so the SUT attests itself and the file is carried. A file that travels can be stale, be
# the wrong file, or describe a host that has since been resized -- and every one of those is an
# honest mistake rather than an attack, which is exactly why they have to be caught.

cat > "${WORK}/verify_cases.py" <<'PYEOF'
import json, subprocess, sys, tempfile
from datetime import UTC, datetime, timedelta
from pathlib import Path

verifier, results = sys.argv[1], {}
now = datetime.now(UTC).isoformat(timespec="seconds")
good = {"specId": "perf-standard-01", "conforms": True, "attestedAt": now,
        "measured": {"host": {"cpus": 8, "memory_gib": 16.0, "runtime_host_name": "perf-01"}}}
stale = dict(good, attestedAt=(datetime.now(UTC) - timedelta(hours=48)).isoformat(timespec="seconds"))

def run(doc, spec):
    with tempfile.TemporaryDirectory() as work:
        path = Path(work) / "a.json"
        path.write_text(json.dumps(doc), encoding="utf-8")
        done = subprocess.run(
            [sys.executable, verifier, str(path), "--expect-spec", spec, "--max-age-hours", "24"],
            capture_output=True, text=True)
        return done.returncode

results["fresh_conforming"] = run(good, "perf-standard-01")
results["says_it_failed"] = run(dict(good, conforms=False, failures=["too little memory"]), "perf-standard-01")
results["different_spec"] = run(good, "perf-standard-02")
results["stale"] = run(stale, "perf-standard-01")
results["no_measurements"] = run({k: v for k, v in good.items() if k != "measured"}, "perf-standard-01")
json.dump(results, sys.stdout)
PYEOF

VERIFY="$("${PYTHON}" "${WORK}/verify_cases.py" "${PERF}/environment/verify-attestation.py")"
verify_says() { printf '%s' "${VERIFY}" | "${PYTHON}" -c   "import json,sys; print(json.load(sys.stdin)[sys.argv[1]])" "$1"; }

check "a fresh conforming attestation is accepted"      "0" "$(verify_says fresh_conforming)"
check "one that records a failure is refused"           "1" "$(verify_says says_it_failed)"
check "one attesting a different spec is refused"       "1" "$(verify_says different_spec)"
check "a stale attestation is refused"                  "1" "$(verify_says stale)"
check "one with no measurements behind it is refused"   "1" "$(verify_says no_measurements)"

grep -q 'RAMALS_PERF_ATTESTATION' "${PERF}/run-baseline.sh"
check "the runner accepts an attestation from the host under test" "0" "$?"

# -- result ------------------------------------------------------------------------------------------

echo
if [ "${FAILURES}" -eq 0 ]; then
  echo "All performance-attestation checks passed."
else
  echo "${FAILURES} performance-attestation check(s) failed."
  exit 1
fi
