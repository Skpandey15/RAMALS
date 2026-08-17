#!/usr/bin/env bash
# M1-T04 required test: the interactionId failure drill.
#
# Answers one question, the way an on-call engineer would have to answer it: given only the support
# code from an error screen, can the execution be located -- in both runtimes, as one trace?
#
# The procedure under test is the one written down in docs/architecture/observability-correlation.md:
#
#   1. Obtain interactionId from the UI error or response.
#   2. Search structured logs for every associated traceId.
#
# This asserts every step of that, against running processes. Two of the three defects it found were
# invisible to unit tests because they lived in deployment configuration and in the interaction
# between a try-with-resources and a finally block -- neither of which any assertion on a response
# body can observe.
#
#   BACKEND_URL=http://backend:8080 AI_URL=http://ai:8000 bash interaction-id-drill.sh
#
# Both services must already be running and reachable from wherever this executes.
set -uo pipefail

BACKEND_URL="${BACKEND_URL:-http://backend:8080}"
AI_URL="${AI_URL:-http://ai:8000}"
BACKEND_CONTAINER="${BACKEND_CONTAINER:-}"
AI_CONTAINER="${AI_CONTAINER:-}"

failures=0
step() { printf '%-58s' "  $1"; }
ok()   { printf 'ok\n'; }
bad()  { printf 'FAILED\n'; [ -n "${1:-}" ] && printf '    %s\n' "$1"; failures=$((failures + 1)); }

# --------------------------------------------------------------------------------------------
echo "Interaction-id failure drill"
echo

# 1. Provoke a failure and take the support code from the response, exactly as a learner's browser
#    would surface it. A 401 is used because it is a real failure a learner can hit, and because a
#    failure that authentication rejects still has to be reportable.
echo "Step 1: obtain the support code from a failing request"

RESPONSE_HEADERS="$(curl -sS -D - -o /dev/null "${BACKEND_URL}/api/v1/learners/me" 2>&1)"
INTERACTION_ID="$(printf '%s' "${RESPONSE_HEADERS}" \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="x-interaction-id"{print $2}' | tail -1)"
STATUS="$(printf '%s' "${RESPONSE_HEADERS}" | head -1 | awk '{print $2}')"

step "failing request returns a support code"
if [ -n "${INTERACTION_ID}" ]; then ok; else bad "no X-Interaction-ID on a ${STATUS} response"; fi

step "support code is a canonical lowercase UUIDv7"
if printf '%s' "${INTERACTION_ID}" \
   | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'; then
  ok
else
  bad "not a canonical UUIDv7: '${INTERACTION_ID}'"
fi

echo "    interactionId = ${INTERACTION_ID}"
echo

# 2. Follow the documented procedure. This is the step that failed twice: once because the deployed
#    profile emitted a console pattern that renders no MDC, and once because the per-request summary
#    line was written after the MDC entries had been closed.
echo "Step 2: locate that request in the backend's structured logs"

BACKEND_LINE=""
if [ -n "${BACKEND_CONTAINER}" ]; then
  BACKEND_LINE="$(docker logs "${BACKEND_CONTAINER}" 2>&1 | grep -F "${INTERACTION_ID}" | tail -1)"
fi

step "backend log carries the support code"
if [ -n "${BACKEND_LINE}" ]; then ok; else
  bad "no backend log line contains ${INTERACTION_ID}"
fi

step "backend log line is structured JSON"
if printf '%s' "${BACKEND_LINE}" | grep -q '^{.*}$'; then ok; else
  bad "not JSON -- the active profile is not emitting structured logs"
fi

BACKEND_TRACE_ID="$(printf '%s' "${BACKEND_LINE}" \
  | sed -n 's/.*"traceId":"\([0-9a-f]*\)".*/\1/p')"

step "backend log line carries a traceId to follow"
if [ -n "${BACKEND_TRACE_ID}" ]; then ok; else
  bad "the line has no traceId, so the trail stops at this service"
fi

step "backend log line leaks no credential"
if printf '%s' "${BACKEND_LINE}" | grep -qiE 'bearer |password|secret|api[_-]?key'; then
  bad "a credential-shaped value appears in the log line"
else
  ok
fi

echo "    traceId = ${BACKEND_TRACE_ID:-<none>}"
echo

# 3. Carry that context to the AI service and confirm the second runtime joins the same trace.
#
#    The hop is performed here rather than by the backend's own code: the backend does not call the
#    AI service until M1-T05. What is under test is the propagation contract both sides implement,
#    which is what T05 will rely on.
echo "Step 3: carry the context across the service boundary"

AI_HEADERS="$(curl -sS -D - -o /dev/null \
  -H "X-Interaction-ID: ${INTERACTION_ID}" \
  -H "traceparent: 00-${BACKEND_TRACE_ID}-$(printf '%016x' $((RANDOM * RANDOM + 1)))-01" \
  "${AI_URL}/internal/v1/capabilities" 2>&1)"

AI_INTERACTION_ID="$(printf '%s' "${AI_HEADERS}" \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="x-interaction-id"{print $2}' | tail -1)"
AI_TRACE_ID="$(printf '%s' "${AI_HEADERS}" \
  | tr -d '\r' | awk -F': ' 'tolower($1)=="x-trace-id"{print $2}' | tail -1)"

step "AI service preserves the support code"
if [ "${AI_INTERACTION_ID}" = "${INTERACTION_ID}" ]; then ok; else
  bad "sent ${INTERACTION_ID}, got back '${AI_INTERACTION_ID}'"
fi

step "AI service continues the backend's trace"
if [ -n "${BACKEND_TRACE_ID}" ] && [ "${AI_TRACE_ID}" = "${BACKEND_TRACE_ID}" ]; then ok; else
  bad "expected trace ${BACKEND_TRACE_ID}, got '${AI_TRACE_ID}' -- two unrelated halves"
fi

echo

# 4. The same support code must find the second runtime's side of the story.
echo "Step 4: locate the same action in the AI service's logs"

AI_LINE=""
if [ -n "${AI_CONTAINER}" ]; then
  AI_LINE="$(docker logs "${AI_CONTAINER}" 2>&1 | grep -F "${INTERACTION_ID}" | tail -1)"
fi

step "AI log carries the same support code"
if [ -n "${AI_LINE}" ]; then ok; else bad "no AI log line contains ${INTERACTION_ID}"; fi

step "AI log line carries the same traceId"
if [ -n "${BACKEND_TRACE_ID}" ] && printf '%s' "${AI_LINE}" | grep -qF "\"traceId\": \"${BACKEND_TRACE_ID}\"" \
   || printf '%s' "${AI_LINE}" | grep -qF "\"traceId\":\"${BACKEND_TRACE_ID}\""; then
  ok
else
  bad "the AI log line does not carry ${BACKEND_TRACE_ID}"
fi

step "AI log line leaks no credential"
if printf '%s' "${AI_LINE}" | grep -qiE 'bearer |password|secret|api[_-]?key'; then
  bad "a credential-shaped value appears in the log line"
else
  ok
fi

echo

# 5. A malformed support code must be refused loudly, and the refusal must itself be findable.
echo "Step 5: a malformed support code is refused, and the refusal is correlated"

REJECTION="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "X-Interaction-ID: not-a-uuid" "${AI_URL}/internal/v1/capabilities" 2>&1)"

step "AI service rejects a malformed interactionId"
if [ "${REJECTION}" = "400" ]; then ok; else bad "expected 400, got ${REJECTION}"; fi

BACKEND_REJECTION="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "X-Interaction-ID: not-a-uuid" "${BACKEND_URL}/api/v1/learners/me" 2>&1)"

step "backend rejects a malformed interactionId identically"
if [ "${BACKEND_REJECTION}" = "400" ]; then ok; else
  bad "expected 400, got ${BACKEND_REJECTION} -- the runtimes disagree about the same header"
fi

echo
if [ "${failures}" -eq 0 ]; then
  echo "Drill passed: a support code locates the action in both runtimes, as one trace."
  exit 0
fi
echo "Drill FAILED: ${failures} check(s) did not hold."
exit 1
