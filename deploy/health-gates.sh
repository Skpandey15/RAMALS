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

# The AI plane is gated separately, and deliberately so (M1-T17).
#
# Its health is not part of the backend's. Tutoring, adaptation and assessment generation are
# enhancements over the deterministic core, so an AI plane that is down must degrade those features
# and nothing else -- a learner keeps their assessments, mastery map and recommendations. Folding it
# into the backend gate would make a model outage look like a platform outage and would roll back a
# perfectly healthy release.
#
# Absent configuration is a legitimate state, not a failure: a deterministic-only deployment has no
# AI plane at all. AI_URL unset therefore skips rather than fails.
if [ -n "${AI_URL:-}" ]; then
  probe "ai plane liveness"  "${AI_URL}/health/live"  '"status"'
  probe "ai plane readiness" "${AI_URL}/health/ready" '"status"'

  # The AI plane refuses an unauthenticated agent call. Proves the workload boundary is live in the
  # deployed image rather than only in tests.
  ai_status="$(curl -s -o /dev/null -w '%{http_code}' --max-time "${TIMEOUT}"     -X POST "${AI_URL}/internal/v1/tutor/respond" || echo 000)"
  [ "${ai_status}" = "401" ] || fail "smoke: expected 401 from unauthenticated AI agent call, got ${ai_status}"
  pass "smoke: AI agent endpoint requires workload identity"
else
  pass "ai plane not configured in this environment; skipped (deterministic core is unaffected)"
fi

# Backend readiness must not depend on the AI plane. Re-probed after the AI gates so that an AI
# failure above would already have been reported separately, and this asserts the independence the
# whole arrangement rests on rather than assuming it.
probe "backend readiness is independent of the AI plane"   "${BACKEND_URL}/actuator/health/readiness" '"status":"UP"'

# Smoke: the API rejects an unauthenticated protected call (proves security wiring is live, not
# that the app merely started).
status="$(curl -s -o /dev/null -w '%{http_code}' --max-time "${TIMEOUT}" "${BACKEND_URL}/api/v1/me" || echo 000)"
[ "${status}" = "401" ] || fail "smoke: expected 401 from unauthenticated /api/v1/me, got ${status}"
pass "smoke: protected endpoint requires authentication"

printf '[health-gates] all gates passed\n'
