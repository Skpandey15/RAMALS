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

write_state() { # write_state <state> <commit> <known_good_commit> <held_json> <failures> <kg_backend> <kg_webui> <kg_ai>
  "${PY}" -c "
import json,sys
state, commit, known_good, held, failures, kg_backend, kg_webui, kg_ai = sys.argv[1:9]
json.dump({
  'state': state,
  'current_commit': commit,
  'known_good_commit': known_good,
  # The digests that were actually healthy. The commit alone is not enough to roll back: by the
  # time a rollback is needed the manifest already describes the BAD version, so the controller
  # must carry the known-good image references itself or it has nothing to return to.
  'known_good_backend_image': kg_backend,
  'known_good_webui_image': kg_webui,
  # The AI plane rolls back with the others. A component that can be deployed but not returned to a
  # known-good digest is worse than one that is not deployed at all: the first bad release strands
  # it, and the only way back is by hand.
  'known_good_ai_image': kg_ai,
  'held_versions': json.loads(held),
  'failure_count': int(failures),
}, open(sys.argv[9],'w'), indent=2)
" "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$8" "${STATE_FILE}"
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
# Optional by design: a deterministic-only deployment runs no AI plane, and that is a supported
# configuration rather than an incomplete manifest.
AI_DIGEST="$(json_get "${MANIFEST}" components.ramals-ai.digest 2>/dev/null || echo '')"

# The desired version must be explicit and immutable; a mutable tag is never deployable.
for digest in "${BACKEND_DIGEST}" "${WEBUI_DIGEST}" ${AI_DIGEST:+"${AI_DIGEST}"}; do
  case "${digest}" in
    sha256:*) ;;
    *) log "ERROR: desired version must be an immutable sha256 digest, got '${digest}'"; exit 1 ;;
  esac
done

CURRENT_STATE="$(state_get state APPROVED)"
CURRENT_COMMIT="$(state_get current_commit none)"
KNOWN_GOOD="$(state_get known_good_commit none)"
KNOWN_GOOD_BACKEND="$(state_get known_good_backend_image none)"
KNOWN_GOOD_WEBUI="$(state_get known_good_webui_image none)"
KNOWN_GOOD_AI="$(state_get known_good_ai_image none)"
HELD="$(state_get held_versions '[]')"
FAILURES="$(state_get failure_count 0)"

log "desired=${DESIRED_COMMIT} current=${CURRENT_COMMIT} state=${CURRENT_STATE}"

# --- anti-flapping guard ------------------------------------------------------------------------
if is_held "${DESIRED_COMMIT}"; then
  log "RELEASE_HELD: ${DESIRED_COMMIT} previously failed health gates and was rolled back."
  log "Refusing automatic redeploy. Correct or explicitly re-approve the manifest."
  write_state RELEASE_HELD "${CURRENT_COMMIT}" "${KNOWN_GOOD}" "${HELD}" "${FAILURES}" \
    "${KNOWN_GOOD_BACKEND}" "${KNOWN_GOOD_WEBUI}" "${KNOWN_GOOD_AI}"
  exit 2
fi

if [ "${DESIRED_COMMIT}" = "${CURRENT_COMMIT}" ] && [ "${CURRENT_STATE}" = "HEALTHY" ]; then
  log "Already reconciled and healthy; nothing to do."
  exit 0
fi

# --- deploy -------------------------------------------------------------------------------------
export RAMALS_BACKEND_IMAGE="$(json_get "${MANIFEST}" components.learning-platform.image)@${BACKEND_DIGEST}"
export RAMALS_WEBUI_IMAGE="$(json_get "${MANIFEST}" components.web-ui.image)@${WEBUI_DIGEST}"
if [ -n "${AI_DIGEST}" ]; then
  export RAMALS_AI_IMAGE="$(json_get "${MANIFEST}" components.ramals-ai.image)@${AI_DIGEST}"

  # The manifest decides the topology. Pinning a ramals-ai digest is the statement that this release
  # includes the AI plane, so pinning it is what deploys it -- rather than a separate flag somebody
  # has to remember to set, which is how the plane came to be published, scanned and digest-pinned
  # for a release that had nowhere to run it.
  #
  # Existing profiles are preserved: an operator composing this with their own selection keeps it.
  case ":${COMPOSE_PROFILES:-}:" in
    *:ai-plane:*) ;;
    *) export COMPOSE_PROFILES="${COMPOSE_PROFILES:+${COMPOSE_PROFILES},}ai-plane" ;;
  esac

  # And the core is told where the plane is. Without this the backend starts with an empty base URL,
  # installs UnconfiguredTutorPort and logs that tutoring is unavailable -- a deployment that is
  # healthy, passes its gates, and quietly has no agent plane. An explicit override still wins, for
  # an environment that runs the plane elsewhere.
  export RAMALS_AI_BASE_URL="${RAMALS_AI_BASE_URL:-http://ramals-ai:8000}"
fi

write_state DEPLOYING "${DESIRED_COMMIT}" "${KNOWN_GOOD}" "${HELD}" "${FAILURES}" \
  "${KNOWN_GOOD_BACKEND}" "${KNOWN_GOOD_WEBUI}" "${KNOWN_GOOD_AI}"
log "DEPLOYING ${DESIRED_COMMIT}"

attempt=0
until ${PULL_CMD}; do
  attempt=$((attempt + 1))
  # Bounded retry for transient registry/network failures only.
  if [ "${attempt}" -ge "${MAX_TRANSIENT_RETRIES}" ]; then
    log "Transient pull failures exhausted after ${attempt} attempts."
    write_state FAILED "${DESIRED_COMMIT}" "${KNOWN_GOOD}" "${HELD}" "$((FAILURES + 1))" \
      "${KNOWN_GOOD_BACKEND}" "${KNOWN_GOOD_WEBUI}" "${KNOWN_GOOD_AI}"
    exit 1
  fi
  log "Transient pull failure; retry ${attempt}/${MAX_TRANSIENT_RETRIES} after backoff."
  sleep "$((attempt * 2))"
done

${UP_CMD}

# --- health gates -------------------------------------------------------------------------------
if ${HEALTH_CMD}; then
  log "HEALTHY ${DESIRED_COMMIT}"
  # Record the digests that passed, not just the commit — this is what a future rollback restores.
  write_state HEALTHY "${DESIRED_COMMIT}" "${DESIRED_COMMIT}" "${HELD}" 0 \
    "${RAMALS_BACKEND_IMAGE}" "${RAMALS_WEBUI_IMAGE}" "${RAMALS_AI_IMAGE:-none}"
  exit 0
fi

# A health-gate failure is NOT retryable: roll back and hold.
log "FAILED health gates for ${DESIRED_COMMIT}; rolling back."
ROLLBACK_OK=0
if [ "${KNOWN_GOOD}" != "none" ] \
   && [ "${KNOWN_GOOD_BACKEND}" != "none" ] && [ "${KNOWN_GOOD_WEBUI}" != "none" ]; then
  log "ROLLBACK to last known-good ${KNOWN_GOOD}"
  # Point the deployment back at the known-good digests. Without this the rollback re-applies the
  # images exported for the FAILED version above and the environment silently keeps running the
  # bad artefact while the state file claims otherwise.
  export RAMALS_BACKEND_IMAGE="${KNOWN_GOOD_BACKEND}"
  export RAMALS_WEBUI_IMAGE="${KNOWN_GOOD_WEBUI}"

  # The AI plane is a released component and has to come back with the rest of the release.
  #
  # Omitting it is not a partial rollback, it is a mixed-version deployment: backend and web-ui
  # revert while the plane keeps running the FAILED image, and the state file says ROLLED_BACK. That
  # is the same failure the comment above describes, for the one component it did not cover, and it
  # was observed in a live environment rather than reasoned about.
  AI_ROLLBACK_UNRESOLVED=0
  if [ "${KNOWN_GOOD_AI}" != "none" ] && [ -n "${KNOWN_GOOD_AI}" ]; then
    export RAMALS_AI_IMAGE="${KNOWN_GOOD_AI}"
  elif [ -n "${AI_DIGEST}" ]; then
    # The failed release introduced the plane and the known-good never ran one, so there is no
    # digest to return it to -- and swapping a digest cannot express removing a component. Refuse to
    # call this a clean rollback rather than leave the failed plane running behind a state file that
    # claims otherwise.
    AI_ROLLBACK_UNRESOLVED=1
    log "ERROR: known-good ${KNOWN_GOOD} ran no AI plane, so the failed plane cannot be rolled back"
    log "       by digest. The AI plane needs manual removal; the core rollback continues."
  fi

  ${PULL_CMD} || log "WARN: rollback pull reported an error"
  ${UP_CMD} || log "WARN: rollback up reported an error"
  # Never claim a rollback we did not verify.
  if ! ${HEALTH_CMD}; then
    log "ERROR: rollback to ${KNOWN_GOOD} did not pass health gates; environment needs manual recovery."
  elif [ "${AI_ROLLBACK_UNRESOLVED}" = "1" ]; then
    log "ERROR: core rolled back to ${KNOWN_GOOD} but the AI plane did not; environment is mixed-version."
  else
    ROLLBACK_OK=1
    log "ROLLED_BACK to ${KNOWN_GOOD}"
  fi
elif [ "${KNOWN_GOOD}" != "none" ]; then
  log "ERROR: known-good commit ${KNOWN_GOOD} recorded without its image digests; cannot roll back."
  log "       This state predates digest tracking. Re-approve a known-good manifest manually."
else
  log "WARN: no known-good version recorded; environment left stopped for investigation."
fi

HELD="$("${PY}" -c "
import json,sys
held=json.loads(sys.argv[1])
if sys.argv[2] not in held: held.append(sys.argv[2])
print(json.dumps(held))
" "${HELD}" "${DESIRED_COMMIT}")"

# current_commit must describe what is RUNNING, not what we wish were running. If the rollback did
# not happen or did not verify, the failed version is still deployed and the state must say so.
if [ "${ROLLBACK_OK}" -eq 1 ]; then
  RUNNING_COMMIT="${KNOWN_GOOD}"
else
  RUNNING_COMMIT="${DESIRED_COMMIT}"
fi
write_state RELEASE_HELD "${RUNNING_COMMIT}" "${KNOWN_GOOD}" "${HELD}" "$((FAILURES + 1))" \
  "${KNOWN_GOOD_BACKEND}" "${KNOWN_GOOD_WEBUI}" "${KNOWN_GOOD_AI}"
log "RELEASE_HELD: ${DESIRED_COMMIT} will not be redeployed automatically."
exit 3
