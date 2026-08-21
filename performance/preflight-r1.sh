#!/usr/bin/env bash
# Proves a two-host environment can authenticate, before anybody spends a measured run finding out.
#
#   ./performance/preflight-r1.sh
#
# Everything an R1 attempt needs before k6 sends its first workload request happens here: fixtures
# are provisioned against Keycloak, the full learner token pool is acquired exactly as the scenarios
# acquire it, and the realm is restored. No workload request is sent and no baseline is written --
# this measures nothing and is not a run.
#
# It exists because the expensive failures have all been pre-workload ones. The first authorised R1
# attempt died at exit 126 on a file mode, after both instances were provisioned, the stack was
# deployed and the environment was attested. The next defect in line was a Keycloak address the
# fixtures were never given. Neither was a performance problem and neither needed a paid
# environment to find -- they needed something to run the setup path end to end and stop.
#
# auth-setup-smoke.js has existed since the two-host support landed and nothing invoked it. A smoke
# test nothing calls is indistinguishable from one that passes.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

say() { printf '\n=== %s\n' "$*"; }
fail() { printf '\nPREFLIGHT FAILED: %s\n' "$*" >&2; exit 1; }

# -- what must be present before anything is changed -------------------------------------------------
#
# Checked up front, because provisioning fixtures mutates the realm. Discovering a missing variable
# afterwards means unwinding a change that did not need to be made.

say "Checking the environment this needs"

missing=0
for required in RAMALS_TOKEN_URL RAMALS_KEYCLOAK_ADMIN RAMALS_KEYCLOAK_ADMIN_PASSWORD RAMALS_LOAD_PASSWORD; do
  if [ -z "${!required:-}" ]; then
    echo "  missing: ${required}" >&2
    missing=$((missing + 1))
  else
    # Names only. RAMALS_KEYCLOAK_ADMIN_PASSWORD and RAMALS_LOAD_PASSWORD are credentials; printing
    # them into a terminal is how they end up in a scrollback buffer somebody pastes into an issue.
    echo "  set: ${required}"
  fi
done
[ "${missing}" -eq 0 ] || fail "${missing} required variable(s) unset; see performance/environment/RUNBOOK-aws.md step 5"

command -v "${RAMALS_K6_CMD:-k6}" >/dev/null || fail "k6 is not on PATH; run provision-loadgen.sh"

# lib/keycloak-url.sh derives the admin-API address from the token endpoint. Resolving it here as
# well means this reports the address it is about to use rather than leaving the operator to infer
# it from a failure inside the fixture script.
# shellcheck source=lib/keycloak-url.sh
. "${HERE}/lib/keycloak-url.sh"
export_keycloak_base_url
echo "  Keycloak base URL: ${RAMALS_KEYCLOAK_URL:-<unset>}"
[ -n "${RAMALS_KEYCLOAK_URL:-}" ] || fail "could not derive a Keycloak base URL from RAMALS_TOKEN_URL"

# -- is the server even there? -----------------------------------------------------------------------
#
# Distinguishes 'cannot reach Keycloak' from 'reached it and it refused', which are different
# problems with different fixes and identical symptoms once they are buried in a stack trace.

say "Reaching Keycloak at ${RAMALS_KEYCLOAK_URL}"
realm="${RAMALS_REALM:-ramals}"
if ! curl -fsS --max-time 15 "${RAMALS_KEYCLOAK_URL}/realms/${realm}/.well-known/openid-configuration" >/dev/null 2>&1; then
  fail "no OIDC discovery document at ${RAMALS_KEYCLOAK_URL}/realms/${realm}. On a two-host run this
  usually means the SUT published its ports on loopback only: bring the stack up with
  performance/compose.perf-two-host.yml and RAMALS_PERF_SUT_BIND_ADDRESS set to its private IP."
fi
echo "  realm '${realm}' is reachable"

# -- the setup path, end to end -----------------------------------------------------------------------

say "Provisioning load fixtures"
"${HERE}/fixtures.sh" provision
# Restores on success, failure and interrupt alike. The committed realm has direct access grants
# disabled and no users; leaving it provisioned would leave known credentials enabled on a host
# whose whole purpose is to be reachable from another machine.
trap '"${HERE}/fixtures.sh" restore || true' EXIT INT TERM
echo "  fixtures provisioned"

say "Acquiring the learner token pool (no workload requests)"
"${RAMALS_K6_CMD:-k6}" run --quiet "${HERE}/auth-setup-smoke.js" \
  || fail "the scenarios cannot authenticate; a measured run would fail in setup()"

printf '\nPreflight passed: fixtures provision and every load learner can authenticate.\n'
printf 'The realm has been restored. Nothing was measured.\n'
