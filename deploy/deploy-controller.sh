#!/usr/bin/env bash
# RAMALS controlled pull-based deployment controller (MVP-0 shared dev).
#
# The dev host runs this on a timer. It READS the approved desired-version manifest and pulls exact
# immutable digests; CI never pushes to the environment and holds no deployment credential.
#
# State machine (per the CI/CD design):
#
#   APPROVED -> DEPLOYING -> HEALTHY
#                        \-> FAILED -> ROLLBACK -> ROLLED_BACK -> RELEASE_HELD
#
# Anti-flapping: a version that failed health gates and was rolled back is recorded in
# `held_versions`. Reconciliation MUST NOT redeploy a held version automatically — a human must
# correct or explicitly re-approve the manifest. Bounded retry applies only to explicitly retryable
# transient failures (registry/network), never to a health-gate failure.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Resolve an interpreter for JSON handling; hosts vary between python3 and python.
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
else echo "[deploy-controller] ERROR: python3 or python is required" >&2; exit 1
fi

MANIFEST="${RAMALS_DESIRED_MANIFEST:-${HERE}/desired-version.json}"
STATE_FILE="${RAMALS_DEPLOY_STATE:-${HERE}/.deploy-state.json}"
MAX_TRANSIENT_RETRIES="${RAMALS_MAX_TRANSIENT_RETRIES:-3}"

# Injectable so the state machine is testable without a container runtime.
PULL_CMD="${RAMALS_PULL_CMD:-docker compose pull}"
UP_CMD="${RAMALS_UP_CMD:-docker compose up -d}"
HEALTH_CMD="${RAMALS_HEALTH_CMD:-${HERE}/health-gates.sh}"

log() { printf '[deploy-controller] %s\n' "$*"; }

json_get() { "${PY}" -c "
import json,sys
data=json.load(open(sys.argv[1]))
for key in sys.argv[2].split('.'):
    data=data[key]
print(data)
" "$1" "$2"; }

state_get() {
  [ -f "${STATE_FILE}" ] || { echo "$2"; return; }
  "${PY}" -c "
import json,sys
try: data=json.load(open(sys.argv[1]))
except Exception: print(sys.argv[3]); raise SystemExit
value=data
for key in sys.argv[2].split('.'):
    if not isinstance(value, dict) or key not in value:
        print(sys.argv[3]); raise SystemExit
    value=value[key]
print(value if not isinstance(value,(dict,list)) else json.dumps(value))
" "${STATE_FILE}" "$1" "$2"
}

write_state() { # write_state <state> <commit> <known_good_commit> <held_json> <failures>
  "${PY}" -c "
import json,sys
state, commit, known_good, held, failures = sys.argv[1:6]
json.dump({
  'state': state,
  'current_commit': commit,
  'known_good_commit': known_good,
  'held_versions': json.loads(held),
  'failure_count': int(failures),
}, open(sys.argv[6],'w'), indent=2)
" "$1" "$2" "$3" "$4" "$5" "${STATE_FILE}"
}

is_held() {
  "${PY}" -c "
import json,sys
try: held=json.load(open(sys.argv[1]))['held_versions']
except Exception: held=[]
raise SystemExit(0 if sys.argv[2] in held else 1)
" "${STATE_FILE}" "$1"
}

# --- read desired state -------------------------------------------------------------------------
[ -f "${MANIFEST}" ] || { log "ERROR: manifest not found: ${MANIFEST}"; exit 1; }

DESIRED_COMMIT="$(json_get "${MANIFEST}" release.commit)"
BACKEND_DIGEST="$(json_get "${MANIFEST}" components.learning-platform.digest)"
WEBUI_DIGEST="$(json_get "${MANIFEST}" components.web-ui.digest)"

# The desired version must be explicit and immutable; a mutable tag is never deployable.
for digest in "${BACKEND_DIGEST}" "${WEBUI_DIGEST}"; do
  case "${digest}" in
    sha256:*) ;;
    *) log "ERROR: desired version must be an immutable sha256 digest, got '${digest}'"; exit 1 ;;
  esac
done

CURRENT_STATE="$(state_get state APPROVED)"
CURRENT_COMMIT="$(state_get current_commit none)"
KNOWN_GOOD="$(state_get known_good_commit none)"
HELD="$(state_get held_versions '[]')"
FAILURES="$(state_get failure_count 0)"

log "desired=${DESIRED_COMMIT} current=${CURRENT_COMMIT} state=${CURRENT_STATE}"

# --- anti-flapping guard ------------------------------------------------------------------------
if is_held "${DESIRED_COMMIT}"; then
  log "RELEASE_HELD: ${DESIRED_COMMIT} previously failed health gates and was rolled back."
  log "Refusing automatic redeploy. Correct or explicitly re-approve the manifest."
  write_state RELEASE_HELD "${CURRENT_COMMIT}" "${KNOWN_GOOD}" "${HELD}" "${FAILURES}"
  exit 2
fi

if [ "${DESIRED_COMMIT}" = "${CURRENT_COMMIT}" ] && [ "${CURRENT_STATE}" = "HEALTHY" ]; then
  log "Already reconciled and healthy; nothing to do."
  exit 0
fi

# --- deploy -------------------------------------------------------------------------------------
export RAMALS_BACKEND_IMAGE="$(json_get "${MANIFEST}" components.learning-platform.image)@${BACKEND_DIGEST}"
export RAMALS_WEBUI_IMAGE="$(json_get "${MANIFEST}" components.web-ui.image)@${WEBUI_DIGEST}"

write_state DEPLOYING "${DESIRED_COMMIT}" "${KNOWN_GOOD}" "${HELD}" "${FAILURES}"
log "DEPLOYING ${DESIRED_COMMIT}"

attempt=0
until ${PULL_CMD}; do
  attempt=$((attempt + 1))
  # Bounded retry for transient registry/network failures only.
  if [ "${attempt}" -ge "${MAX_TRANSIENT_RETRIES}" ]; then
    log "Transient pull failures exhausted after ${attempt} attempts."
    write_state FAILED "${DESIRED_COMMIT}" "${KNOWN_GOOD}" "${HELD}" "$((FAILURES + 1))"
    exit 1
  fi
  log "Transient pull failure; retry ${attempt}/${MAX_TRANSIENT_RETRIES} after backoff."
  sleep "$((attempt * 2))"
done

${UP_CMD}

# --- health gates -------------------------------------------------------------------------------
if ${HEALTH_CMD}; then
  log "HEALTHY ${DESIRED_COMMIT}"
  write_state HEALTHY "${DESIRED_COMMIT}" "${DESIRED_COMMIT}" "${HELD}" 0
  exit 0
fi

# A health-gate failure is NOT retryable: roll back and hold.
log "FAILED health gates for ${DESIRED_COMMIT}; rolling back."
if [ "${KNOWN_GOOD}" != "none" ]; then
  log "ROLLBACK to last known-good ${KNOWN_GOOD}"
  ${PULL_CMD} || log "WARN: rollback pull reported an error"
  ${UP_CMD} || log "WARN: rollback up reported an error"
  log "ROLLED_BACK to ${KNOWN_GOOD}"
else
  log "WARN: no known-good version recorded; environment left stopped for investigation."
fi

HELD="$("${PY}" -c "
import json,sys
held=json.loads(sys.argv[1])
if sys.argv[2] not in held: held.append(sys.argv[2])
print(json.dumps(held))
" "${HELD}" "${DESIRED_COMMIT}")"

write_state RELEASE_HELD "${KNOWN_GOOD}" "${KNOWN_GOOD}" "${HELD}" "$((FAILURES + 1))"
log "RELEASE_HELD: ${DESIRED_COMMIT} will not be redeployed automatically."
exit 3
