#!/usr/bin/env bash
# Thin wrapper around provision-load-fixtures.py.
#
# Where the provisioning runs depends on how Keycloak is reachable:
#   * RAMALS_FIXTURE_NETWORK set -> run inside that Docker network, using the in-network service
#     name (e.g. RAMALS_KEYCLOAK_URL=http://keycloak:8080). Needed on hosts where published ports
#     are not reachable, and required anyway when the realm issuer is an in-network hostname.
#   * otherwise -> run with the local interpreter against RAMALS_KEYCLOAK_URL.
#
# The container form deliberately uses no bind mounts: the script is piped in on stdin and the
# fixture record comes back on stdout. Bind-mounting a repo path is not portable across the hosts
# this runs on (a Windows path that does not resolve is silently created as a directory).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ACTION="${1:?usage: fixtures.sh provision|restore}"

STATE_DIR="${RAMALS_LOAD_FIXTURE_DIR:-${HERE}/.fixtures}"
mkdir -p "${STATE_DIR}"
STATE_FILE="${STATE_DIR}/state.json"

if [ -n "${RAMALS_FIXTURE_NETWORK:-}" ]; then
  run_in_container() { # run_in_container <action> [extra-env...]
    docker run --rm -i --network "${RAMALS_FIXTURE_NETWORK}" \
      -e RAMALS_KEYCLOAK_URL="${RAMALS_KEYCLOAK_URL:-http://keycloak:8080}" \
      -e RAMALS_REALM -e RAMALS_CLIENT_ID \
      -e RAMALS_KEYCLOAK_ADMIN -e RAMALS_KEYCLOAK_ADMIN_PASSWORD \
      -e RAMALS_LOAD_PASSWORD -e RAMALS_LOAD_USER_PREFIX -e RAMALS_LOAD_LEARNERS \
      -e RAMALS_LOAD_FIXTURE_STATE_JSON="${RAMALS_LOAD_FIXTURE_STATE_JSON:-}" \
      python:3.13-alpine sh -c "cat > /tmp/p.py && exec python /tmp/p.py $1 --state -" \
      < "${HERE}/provision-load-fixtures.py"
  }

  case "${ACTION}" in
    provision)
      output="$(run_in_container provision)"
      printf '%s\n' "${output}" | grep -v '^FIXTURE_STATE=' || true
      printf '%s' "${output}" | sed -n 's/^FIXTURE_STATE=//p' > "${STATE_FILE}"
      [ -s "${STATE_FILE}" ] || { echo "fixtures.sh: provisioning returned no state record" >&2; exit 1; }
      ;;
    restore)
      [ -s "${STATE_FILE}" ] || { echo "No fixture state file; nothing to restore."; exit 0; }
      RAMALS_LOAD_FIXTURE_STATE_JSON="$(cat "${STATE_FILE}")" run_in_container restore
      rm -f "${STATE_FILE}"
      ;;
    *) echo "usage: fixtures.sh provision|restore" >&2; exit 1 ;;
  esac
else
  if command -v python3 >/dev/null 2>&1; then PY=python3; else PY=python; fi
  RAMALS_LOAD_FIXTURE_STATE="${STATE_FILE}" \
    "${PY}" "${HERE}/provision-load-fixtures.py" "${ACTION}"
fi
