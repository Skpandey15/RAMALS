#!/usr/bin/env bash
# Post-deploy health gates. A deployment is marked successful only after every gate passes;
# any failure makes the deploy controller roll back and hold the release.
set -uo pipefail

BACKEND_URL="${RAMALS_BACKEND_URL:-http://localhost:8080}"
WEBUI_URL="${RAMALS_WEBUI_URL:-http://localhost:5173}"
ISSUER_URL="${RAMALS_OIDC_ISSUER_URI:-http://localhost:8081/realms/ramals}"
TIMEOUT="${RAMALS_HEALTH_TIMEOUT:-5}"
RETRIES="${RAMALS_HEALTH_RETRIES:-12}"

fail() { printf '[health-gates] FAIL %s\n' "$*"; exit 1; }
pass() { printf '[health-gates] ok   %s\n' "$*"; }

probe() { # probe <name> <url> [jq-free grep pattern]
  local name="$1" url="$2" pattern="${3:-}"
  local attempt=0 body
  while [ "${attempt}" -lt "${RETRIES}" ]; do
    if body="$(curl -fsS --max-time "${TIMEOUT}" "${url}" 2>/dev/null)"; then
      if [ -z "${pattern}" ] || printf '%s' "${body}" | grep -q "${pattern}"; then
        pass "${name}"
        return 0
      fi
    fi
    attempt=$((attempt + 1))
    sleep 5
  done
  fail "${name} (${url})"
}

# Container started and Spring Boot liveness/readiness are healthy.
probe "backend liveness"  "${BACKEND_URL}/actuator/health/liveness"  '"status":"UP"'
probe "backend readiness" "${BACKEND_URL}/actuator/health/readiness" '"status":"UP"'

# Database connectivity is part of the aggregate health report.
probe "backend health (db)" "${BACKEND_URL}/actuator/health" '"status":"UP"'

# Keycloak issuer/JWKS dependency is reachable.
probe "oidc issuer" "${ISSUER_URL}/.well-known/openid-configuration" 'jwks_uri'

# Web UI responds.
probe "web ui" "${WEBUI_URL}/"

# Smoke: the API rejects an unauthenticated protected call (proves security wiring is live, not
# that the app merely started).
status="$(curl -s -o /dev/null -w '%{http_code}' --max-time "${TIMEOUT}" "${BACKEND_URL}/api/v1/me" || echo 000)"
[ "${status}" = "401" ] || fail "smoke: expected 401 from unauthenticated /api/v1/me, got ${status}"
pass "smoke: protected endpoint requires authentication"

printf '[health-gates] all gates passed\n'
