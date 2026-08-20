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
  # Matched on the value, not merely on the key being present. The plane returns 503 alongside
  # OUT_OF_SERVICE today, so ``curl -f`` would already catch a genuinely unready one -- but a gate
  # that passes on any body containing the word "status" is relying on that coupling holding
  # forever, and the backend probes above do not rely on it either.
  probe "ai plane liveness"  "${AI_URL}/health/live"  '"status":"UP"'
  probe "ai plane readiness" "${AI_URL}/health/ready" '"status":"UP"'

  # The AI plane refuses an unauthenticated agent call. Proves the workload boundary is live in the
  # deployed image rather than only in tests.
  ai_status="$(curl -s -o /dev/null -w '%{http_code}' --max-time "${TIMEOUT}"     -X POST "${AI_URL}/internal/v1/tutor/respond" || echo 000)"
  [ "${ai_status}" = "401" ] || fail "smoke: expected 401 from unauthenticated AI agent call, got ${ai_status}"
  pass "smoke: AI agent endpoint requires workload identity"

  # The rollback smoke gate (M1-ADR-008: "a rollback is a deployment").
  #
  # A prompt or model rollback is a pointer change applied from configuration, so nothing about the
  # deployment artifacts changes when one happens. That makes a rollback that silently failed to
  # apply indistinguishable from one that worked -- from outside the process, and for exactly as
  # long as it takes somebody to read the outputs they were trying to stop producing.
  #
  # So the running service is asked what it is serving, rather than the manifest being trusted to
  # describe it. The manifest records an intention; this records a fact.
  ai_caps="$(curl -s --max-time "${TIMEOUT}" "${AI_URL}/internal/v1/capabilities" || echo '')"
  reported_table="$(printf '%s' "${ai_caps}" |
    sed -n 's/.*"routeTableVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"

  [ -n "${reported_table}" ] ||
    fail "smoke: AI plane reported no routeTableVersion, so no rollback can be verified"
  pass "smoke: AI plane reports its route table (${reported_table})"

  # Asserted only when the deployment says what it expects. An unset expectation is a deployment
  # that is not rolling anything back, and inventing a default here would either fail every
  # ordinary release or pass every rollback.
  if [ -n "${AI_EXPECTED_ROUTE_TABLE:-}" ]; then
    [ "${reported_table}" = "${AI_EXPECTED_ROUTE_TABLE}" ] ||
      fail "smoke: expected route table '${AI_EXPECTED_ROUTE_TABLE}', AI plane is serving '${reported_table}'"
    pass "smoke: the rollback took effect in the running service"
  fi
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
