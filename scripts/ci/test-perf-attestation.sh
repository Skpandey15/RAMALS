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

cat > "${WORK}/compose_resolution.py" <<'PYEOF'
import importlib.util
import json
import subprocess
import sys
from pathlib import Path
from unittest.mock import patch

perf = Path(sys.argv[1])
loader = importlib.util.spec_from_file_location("attest", perf / "environment" / "attest.py")
attest = importlib.util.module_from_spec(loader)
loader.loader.exec_module(attest)

host_config = json.dumps({"NanoCpus": 4_000_000_000, "Memory": 4 * 1024**3})
responses = [
    subprocess.CalledProcessError(1, ["docker", "inspect", "backend"]),
    subprocess.CompletedProcess([], 0, stdout="abc123\n", stderr=""),
    subprocess.CompletedProcess([], 0, stdout=host_config, stderr=""),
]
with patch.object(attest.subprocess, "run", side_effect=responses) as run:
    measured = attest.container_limits(["backend"])
    label_call = run.call_args_list[1].args[0]

valid = measured == {"backend": {"running": True, "cpus": 4.0, "memory_gib": 4.0}}
valid = valid and "label=com.docker.compose.service=backend" in label_call
sys.exit(0 if valid else 1)
PYEOF

status="$("${PYTHON}" "${WORK}/compose_resolution.py" "${PERF}"; echo $?)"
check "Compose-prefixed service containers are resolved by label" "0" "${status}"

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

runner_mode="$(git -C "${REPO_ROOT}" ls-files --stage -- performance/run-baseline.sh | awk '{print $1}')"
check "the documented benchmark runner is executable" "100755" "${runner_mode}"

# -- the two-host fixture path -------------------------------------------------------------------------
#
# The load generator reaches Keycloak twice by two different names: k6 POSTs to RAMALS_TOKEN_URL,
# and the fixture script drives the admin API at RAMALS_KEYCLOAK_URL. Only the first was ever
# supplied, so the second fell back to the Compose service name -- which resolves on the SUT and
# nowhere else. The run died provisioning fixtures, on a host the operator had already given a
# perfectly good address for.
#
# These run in CI because the transform is the whole fix, and it needs no network to check.

# shellcheck source=../../performance/lib/keycloak-url.sh
. "${PERF}/lib/keycloak-url.sh"

CANONICAL_TOKEN_URL="http://172.31.12.172:8081/realms/ramals/protocol/openid-connect/token"

check "the Keycloak base URL is derived from the token endpoint" \
  "http://172.31.12.172:8081" \
  "$(keycloak_base_url_from_token_url "${CANONICAL_TOKEN_URL}")"

# A realm called 'realms' must not truncate the result at the wrong slash.
check "a realm named 'realms' does not confuse the derivation" \
  "https://idp.example:8443" \
  "$(keycloak_base_url_from_token_url 'https://idp.example:8443/realms/realms/protocol/openid-connect/token')"

# Guessing a prefix for something that is not a realm-scoped endpoint would produce a plausible URL
# pointing at nothing, which is worse than refusing.
keycloak_base_url_from_token_url 'http://idp.example/oauth2/token' >/dev/null 2>&1
check "a non-realm URL is refused rather than guessed at" "1" "$?"

derived="$(RAMALS_TOKEN_URL="${CANONICAL_TOKEN_URL}" RAMALS_KEYCLOAK_URL="" \
  "${SHELL_BIN:-bash}" -c ". '${PERF}/lib/keycloak-url.sh'; export_keycloak_base_url; printf '%s' \"\${RAMALS_KEYCLOAK_URL:-}\"")"
check "export sets RAMALS_KEYCLOAK_URL when only the token URL is given" \
  "http://172.31.12.172:8081" "${derived}"

explicit="$(RAMALS_TOKEN_URL="${CANONICAL_TOKEN_URL}" RAMALS_KEYCLOAK_URL="http://elsewhere:9090" \
  "${SHELL_BIN:-bash}" -c ". '${PERF}/lib/keycloak-url.sh'; export_keycloak_base_url; printf '%s' \"\${RAMALS_KEYCLOAK_URL}\"")"
check "an explicitly set RAMALS_KEYCLOAK_URL is not overruled" \
  "http://elsewhere:9090" "${explicit}"

# The perturbation that matters: with neither variable set and no fixture network, off-host
# provisioning must refuse rather than fall through to a hostname only the SUT can resolve.
#
# Asserted on the message rather than the exit status. Deleting the guard leaves fixtures.sh running
# on to provision-load-fixtures.py, which exits non-zero anyway on the first missing admin
# credential -- so an exit-code assertion passes whether the guard is there or not, which is how
# this check read before it was perturbed. The status is checked too, but it is the weaker half.
refusal="$( unset RAMALS_KEYCLOAK_URL RAMALS_TOKEN_URL RAMALS_FIXTURE_NETWORK
            "${PERF}/fixtures.sh" provision 2>&1 >/dev/null )"
refusal_status=$?
case "${refusal}" in
  *"needs a reachable Keycloak address"*) refused="named" ;;
  *)                                      refused="not-named" ;;
esac
check "fixtures name the missing Keycloak address as the reason" "named" "${refused}"
check "fixtures refuse to provision with no reachable Keycloak address" "1" "${refusal_status}"

# -- the credential chain, end to end ----------------------------------------------------------------
#
# Keycloak derives the `iss` claim from the address a token was requested through unless the
# hostname is pinned. On one host nobody notices; on two, k6 mints tokens via the published address
# and the backend validates against the in-network name, so every token is well-formed, correctly
# signed and refused. The completed R1 attempt returned 401 to 9,599 of 9,619 requests and passed
# every latency threshold doing it, because rejecting a token takes a millisecond.
#
# So the two values that have to agree are checked for agreeing, textually, here -- where it costs
# nothing -- rather than on a paid environment.

AGREEMENT="$("${PYTHON}" - "${PERF}/compose.perf-two-host.yml" <<'PYEOF'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()

def value_of(key):
    match = re.search(rf'^\s*{re.escape(key)}:\s*"([^"]+)"', text, re.MULTILINE)
    return match.group(1) if match else None

hostname = value_of("KC_HOSTNAME")
issuer = value_of("RAMALS_OIDC_ISSUER_URI")

if hostname is None or issuer is None:
    print("missing")
elif not issuer.startswith(hostname + "/realms/"):
    # Compared before interpolation on purpose: both sides must be built from the same variables,
    # so they cannot diverge for any value the operator supplies.
    print("disagree")
else:
    print("agree")
PYEOF
)"
check "the pinned Keycloak hostname and the backend's expected issuer agree" "agree" "${AGREEMENT%$'\r'}"

# The smoke has to spend a token, not merely obtain one. Acquisition succeeded throughout the run
# that measured nothing: Keycloak was never the component that disagreed.
grep -q "url('/api/v1/me')" "${PERF}/auth-setup-smoke.js"
check "the smoke presents a token to the backend, not just acquires one" "0" "$?"

grep -q 'RAMALS_BASE_URL' "${PERF}/preflight-r1.sh"
check "the preflight requires a backend address to present it to" "0" "$?"

# -- a failing run still has to leave a baseline behind -----------------------------------------------
#
# k6 exits non-zero on a breached threshold and run-baseline.sh runs under `set -e`, so the run most
# worth recording was the only one that recorded nothing. R1 Run A sustained the canonical rate for
# its full duration, produced 12,417 requests of evidence, and wrote no baseline -- the error-rate
# threshold was crossed and distillation never ran.
#
# Driven with a stub k6 rather than asserted by reading the source: the behaviour under test is what
# the script does when its subprocess fails, and only running it can show that.

STUB_DIR="${WORK}/stub"
mkdir -p "${STUB_DIR}"
cat > "${STUB_DIR}/k6" <<'STUBEOF'
#!/usr/bin/env bash
# Writes a plausible summary, then fails the way a breached threshold fails.
summary=""
while [ $# -gt 0 ]; do
  [ "$1" = "--summary-export" ] && summary="$2"
  shift
done
# setup_data is included deliberately. k6 puts setup()'s return value in the summary export, and
# for these scenarios that is the learner access-token pool -- so an unscrubbed summary is a file
# full of live bearer credentials. R1 Run A's summary reached a commit with 20 of them in it,
# because scrubbing happens during distillation and distillation never ran.
cat > "${summary}" <<'JSON'
{"metrics":{"http_req_duration":{"med":10.0,"p(95)":20.0,"p(99)":30.0},
            "http_req_failed":{"value":0.1733},
            "http_reqs":{"rate":59.1}},
 "setup_data":{"tokens":["eyJstub.stub.stub"]}}
JSON
exit 99
STUBEOF
chmod +x "${STUB_DIR}/k6"

BEFORE="$(ls -1 "${PERF}/results/" 2>/dev/null | wc -l | tr -d ' ')"
( cd "${REPO_ROOT}" && \
  RAMALS_K6_CMD="${STUB_DIR}/k6" \
  RAMALS_SKIP_FIXTURES=1 \
  RAMALS_PERF_ENV=local-unqualified \
  RAMALS_PERF_RATE_LIMIT_OVERRIDE=true \
  PY_BIN="${PYTHON}" \
  bash "${PERF}/run-baseline.sh" mixed-learning >/dev/null 2>&1 )
stub_status=$?

check "a breached threshold still propagates k6's exit status" "99" "${stub_status}"

# The security half of the same defect, and the more serious one: a failing run used to leave the
# access-token pool sitting in the summary export, because the scrub lives in the step that was
# skipped. Losing a measurement is bad; publishing credentials is worse.
NEW_SUMMARY="$(ls -1t "${PERF}/results/"*.summary.json 2>/dev/null | head -1)"
if [ -n "${NEW_SUMMARY}" ] && grep -q 'setup_data' "${NEW_SUMMARY}" 2>/dev/null; then
  scrubbed="no"
else
  scrubbed="yes"
fi
check "a FAILING run still scrubs the access tokens from its summary" "yes" "${scrubbed}"

NEW_BASELINE="$(ls -1t "${PERF}/results/"*.baseline.json 2>/dev/null | head -1)"
if [ -n "${NEW_BASELINE}" ] && [ "$(ls -1 "${PERF}/results/" | wc -l | tr -d ' ')" -gt "${BEFORE}" ]; then
  wrote="yes"
else
  wrote="no"
fi
check "a FAILING run still writes a baseline artifact" "yes" "${wrote}"

if [ "${wrote}" = "yes" ]; then
  verdict="$("${PYTHON}" -c "
import json,sys
b=json.load(open(sys.argv[1]))
print(f\"{b.get('thresholds_passed')}|{b.get('k6_exit_status')}|{b.get('performance_rate_limit_override')}\")
" "${NEW_BASELINE}")"
  check "the baseline records the failure and the policy in force" \
    "False|99|True" "${verdict%$'\r'}"
  # Written by this suite, not by a run anybody should keep.
  rm -f "${NEW_BASELINE}" "${NEW_BASELINE%.baseline.json}".*
fi

# -- things that must not drift back ---------------------------------------------------------------

RUNBOOK="${PERF}/environment/RUNBOOK-aws.md"

# The runbook used to print 'RAMALS_KEYCLOAK_ADMIN=admin' as though it were the value rather than an
# example. The real one is whatever the SUT's deploy/.env holds, and a mismatch is only discovered
# after the environment has been built and attested.
grep -qE '^\s*export RAMALS_KEYCLOAK_ADMIN=admin\b' "${RUNBOOK}"
check "the runbook does not present a hardcoded Keycloak admin username as fact" "1" "$?"

# auth-setup-smoke.js shipped with the two-host support and nothing called it for two releases. A
# smoke test nothing invokes cannot be distinguished from one that passes.
grep -q 'auth-setup-smoke.js' "${PERF}/preflight-r1.sh"
check "the authentication smoke test is actually invoked" "0" "$?"

grep -q 'preflight-r1.sh' "${RUNBOOK}"
check "the runbook tells the operator to run the preflight" "0" "$?"

# Every script the harness runs directly has to carry the executable bit -- derived from where the
# invocations actually are, not from a list somebody remembers to extend.
#
# A hardcoded list is what existed, and it named run-baseline.sh alone. fixtures.sh is executed the
# same way from run-baseline.sh:95, was committed 100644, and would have failed with exit 126 on the
# load generator -- the identical failure that invalidated the first authorised R1 attempt, one step
# further down the same path. It survived local testing because Windows does not enforce the bit.
#
# Scripts invoked as `bash x.sh` are excluded on purpose: the mode is irrelevant there, which is why
# every scripts/ci entry is fine at 100644.
DIRECT_TARGETS="$("${PYTHON}" - "${REPO_ROOT}" <<'PYEOF'
import re, sys
from pathlib import Path

root = Path(sys.argv[1])
# "${HERE}/name.sh" -- resolved against the referencing script's own directory.
here = re.compile(r'"\$\{HERE\}/([a-z0-9-]+\.sh)"')
# ./performance/name.sh -- resolved against the repository root.
rooted = re.compile(r'\./(performance/[a-z0-9/-]+\.sh)')

targets = set()
for source in sorted(root.glob("performance/**/*.sh")):
    for line in source.read_text(encoding="utf-8").splitlines():
        stripped = line.lstrip()
        if stripped.startswith("#"):
            continue
        for name in here.findall(line):
            targets.add((source.parent / name).relative_to(root).as_posix())
        for path in rooted.findall(line):
            targets.add(path)

print("\n".join(sorted(targets)))
PYEOF
)"

[ -n "${DIRECT_TARGETS}" ] || { echo "FAIL could not find any directly-invoked scripts to check"; FAILURES=$((FAILURES + 1)); }

while IFS= read -r target; do
  # Python on Windows writes CRLF, and a path with a trailing carriage return matches no file --
  # git would report an empty mode and the check would fail for a reason that has nothing to do
  # with the permission it is testing. This suite has to give the same answer on both platforms.
  target="${target%$'\r'}"
  [ -n "${target}" ] || continue
  mode="$(git -C "${REPO_ROOT}" ls-files --stage -- "${target}" | awk '{print $1}')"
  check "${target} is executable (it is invoked directly)" "100755" "${mode}"
done <<< "${DIRECT_TARGETS}"

for runnable in performance/environment/check-two-host-network.sh; do
  mode="$(git -C "${REPO_ROOT}" ls-files --stage -- "${runnable}" | awk '{print $1}')"
  check "${runnable} is executable" "100755" "${mode}"
done

# -- result ------------------------------------------------------------------------------------------

echo
if [ "${FAILURES}" -eq 0 ]; then
  echo "All performance-attestation checks passed."
else
  echo "${FAILURES} performance-attestation check(s) failed."
  exit 1
fi
