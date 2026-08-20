#!/usr/bin/env bash
# Tests deploy/health-gates.sh against a stubbed HTTP surface.
#
# The gates had no test. That mattered more than it looks: deploy-controller.sh is exercised with a
# *stub* health script, so nothing anywhere executed the real gate logic, and a gate that cannot
# fail is indistinguishable from one that always passes. M1-T17 slice 1 already found one of these
# -- a health stub that judged only the backend image, which made "a bad AI digest rolls back"
# impossible to fail -- so the rollback smoke gate is not being added on the same terms.
#
# curl is stubbed on PATH rather than a real server being started: the gate's contract is what it
# does with a response, and a stub can produce the malformed and mismatched responses that matter
# here and that a healthy server never would.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GATES="${REPO_ROOT}/deploy/health-gates.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

FAILURES=0
check() { # check <name> <expected> <actual>
  if [ "$2" = "$3" ]; then
    printf 'ok   %s\n' "$1"
  else
    printf 'FAIL %s (expected "%s", got "%s")\n' "$1" "$2" "$3"
    FAILURES=$((FAILURES + 1))
  fi
}

# -- the curl stub ---------------------------------------------------------------------------------
#
# Dispatches on the URL and serves whatever the current case wrote into ${WORK}/responses. An
# unmatched URL is a hard 404 rather than an empty success, so a gate probing a URL the test did not
# anticipate fails loudly instead of passing on an empty body.
mkdir -p "${WORK}/bin"
cat > "${WORK}/bin/curl" <<'STUB'
#!/usr/bin/env bash
url=""
want_status=0
for arg in "$@"; do
  case "${arg}" in
    http*) url="${arg}" ;;
    '%{http_code}') want_status=1 ;;
  esac
done

serve() { # serve <file>
  # A companion ".httperror" file models a non-2xx response: curl -f writes nothing and exits 22,
  # which is what the gates actually see from a 503, rather than a body they can grep.
  if [ -f "${RESPONSES}/$1.httperror" ]; then
    return 22
  fi
  if [ -f "${RESPONSES}/$1" ]; then
    cat "${RESPONSES}/$1"
    return 0
  fi
  return 22   # curl's exit code for an HTTP error under -f
}

key=""
case "${url}" in
  */actuator/health/liveness)   key="backend-liveness" ;;
  */actuator/health/readiness)  key="backend-readiness" ;;
  */actuator/health)            key="backend-health" ;;
  */.well-known/openid-configuration) key="oidc" ;;
  */internal/v1/capabilities)   key="ai-capabilities" ;;
  */health/live)                key="ai-live" ;;
  */health/ready)               key="ai-ready" ;;
  */internal/v1/tutor/respond)  key="ai-tutor-status" ;;
  */api/v1/me)                  key="backend-me-status" ;;
  *)                            key="webui" ;;
esac

if [ "${want_status}" = "1" ]; then
  # -w '%{http_code}' callers want the status code on stdout and nothing else.
  if [ -f "${RESPONSES}/${key}" ]; then cat "${RESPONSES}/${key}"; else echo "404"; fi
  exit 0
fi
serve "${key}"
STUB
chmod +x "${WORK}/bin/curl"

reset_responses() {
  RESPONSES="${WORK}/responses"
  rm -rf "${RESPONSES}"
  mkdir -p "${RESPONSES}"
  printf '{"status":"UP"}'    > "${RESPONSES}/backend-liveness"
  printf '{"status":"UP"}'    > "${RESPONSES}/backend-readiness"
  printf '{"status":"UP"}'    > "${RESPONSES}/backend-health"
  printf '{"jwks_uri":"x"}'   > "${RESPONSES}/oidc"
  printf '<html></html>'      > "${RESPONSES}/webui"
  printf '{"status":"UP"}'    > "${RESPONSES}/ai-live"
  printf '{"status":"UP"}'    > "${RESPONSES}/ai-ready"
  printf '401'                > "${RESPONSES}/ai-tutor-status"
  printf '401'                > "${RESPONSES}/backend-me-status"
  printf '{"contractVersion":"1.0","modelRoute":"ci-fake","routeTableVersion":"ROUTE_TABLE_V1"}' \
    > "${RESPONSES}/ai-capabilities"
  export RESPONSES
}

run_gates() { # run_gates [extra env assignments...]
  PATH="${WORK}/bin:${PATH}" \
  RESPONSES="${RESPONSES}" \
  RAMALS_BACKEND_URL="http://backend" \
  RAMALS_WEBUI_URL="http://webui" \
  RAMALS_OIDC_ISSUER_URI="http://issuer/realms/ramals" \
  RAMALS_HEALTH_RETRIES=1 \
  RAMALS_HEALTH_TIMEOUT=1 \
  "$@" bash "${GATES}" > "${WORK}/out" 2>&1
  echo "$?"
}

# -- a deployment with no AI plane -----------------------------------------------------------------

reset_responses
status="$(run_gates env)"
check "deterministic-only deployment passes" "0" "${status}"
grep -q "ai plane not configured" "${WORK}/out" &&
  check "and says the AI plane was skipped" "0" "0" ||
  check "and says the AI plane was skipped" "0" "1"

# -- a healthy AI plane ----------------------------------------------------------------------------

reset_responses
status="$(run_gates env AI_URL=http://ai)"
check "healthy AI plane passes" "0" "${status}"
grep -q "AI plane reports its route table (ROUTE_TABLE_V1)" "${WORK}/out" &&
  check "and the served route table is reported" "0" "0" ||
  check "and the served route table is reported" "0" "1"

# -- a plane that cannot say what it is serving ----------------------------------------------------
#
# An older image, or one whose startup silently fell back. Either way no rollback can be verified
# against it, so the gate must refuse rather than assume the pointer took.

reset_responses
printf '{"contractVersion":"1.0","modelRoute":"ci-fake"}' > "${RESPONSES}/ai-capabilities"
status="$(run_gates env AI_URL=http://ai)"
check "a plane reporting no route table fails" "1" "${status}"

# -- the rollback smoke gate -----------------------------------------------------------------------

reset_responses
printf '{"routeTableVersion":"ROUTE_TABLE_V1+tutor-default:TUTOR_EXPLAIN=TUTOR_PROMPT_V2"}' \
  > "${RESPONSES}/ai-capabilities"
status="$(run_gates env AI_URL=http://ai \
  AI_EXPECTED_ROUTE_TABLE=ROUTE_TABLE_V1+tutor-default:TUTOR_EXPLAIN=TUTOR_PROMPT_V2)"
check "a rollback that took effect passes" "0" "${status}"
grep -q "the rollback took effect in the running service" "${WORK}/out" &&
  check "and says so" "0" "0" ||
  check "and says so" "0" "1"

# The case the gate exists for: the manifest asked for a rollback and the running service is still
# serving the configuration somebody was trying to withdraw. Without this the deploy is marked
# healthy and the bad prompt keeps serving learners.
reset_responses
status="$(run_gates env AI_URL=http://ai \
  AI_EXPECTED_ROUTE_TABLE=ROUTE_TABLE_V1+tutor-default:TUTOR_EXPLAIN=TUTOR_PROMPT_V2)"
check "a rollback that did not take effect fails" "1" "${status}"
grep -q "AI plane is serving 'ROUTE_TABLE_V1'" "${WORK}/out" &&
  check "and names what is actually being served" "0" "0" ||
  check "and names what is actually being served" "0" "1"

# -- the AI plane must not decide the backend's fate -----------------------------------------------
#
# Asserted here as well as in the controller tests, because this is the gate that would express the
# mistake: an AI outage that failed the whole release would roll back a healthy deterministic core.

# The realistic shape: readiness answers 503, so curl -f fails and the gate never sees a body.
reset_responses
touch "${RESPONSES}/ai-ready.httperror"
status="$(run_gates env AI_URL=http://ai)"
check "an AI plane answering 503 fails its own gate" "1" "${status}"
grep -q "^\[health-gates\] ok   backend readiness" "${WORK}/out" &&
  check "after the backend gates already passed" "0" "0" ||
  check "after the backend gates already passed" "0" "1"

# The shape the loose pattern used to admit: a 200 carrying a status that is not UP.
reset_responses
printf '{"status":"OUT_OF_SERVICE","reason":"starting"}' > "${RESPONSES}/ai-ready"
status="$(run_gates env AI_URL=http://ai)"
check "an AI plane reporting OUT_OF_SERVICE with 200 fails" "1" "${status}"
grep -q "^\[health-gates\] ok   backend readiness" "${WORK}/out" &&
  check "after the backend gates already passed" "0" "0" ||
  check "after the backend gates already passed" "0" "1"

# -- result ----------------------------------------------------------------------------------------

echo
if [ "${FAILURES}" -eq 0 ]; then
  echo "All health-gate checks passed."
else
  echo "${FAILURES} health-gate check(s) failed."
  exit 1
fi
