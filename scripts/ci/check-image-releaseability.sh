#!/usr/bin/env bash
# Answers, before a pull request merges, the question the release pipeline asks after it: would this
# image actually publish?
#
# It exists because that question used to be answerable only in hindsight. release.yml runs on push
# to main, so M1-T17 slice 1 connected ramals-ai to the publish matrix without its image ever having
# been scanned, and eight consecutive merges went red while every pull request showed green. The
# scan was working perfectly. Nothing ran it at a point where anybody would look.
#
# Three things, in the order a release does them:
#
#   build   the image from the same Dockerfile and context the release uses
#   scan    with byte-identical Trivy flags -- a gate that is nearly the same is a gate that
#           disagrees with the real one exactly when it matters
#   smoke   start the container and check it does something
#
# The smoke is deliberately not uniform, because the images are not. ramals-ai and web-ui run
# standalone, so they are asked to serve. learning-platform needs a database with provisioned roles
# and an identity provider, which is a deployment rather than a smoke test -- it is asked only to
# prove the artifact is runnable: that the JVM starts and the application reaches its own
# configuration stage. That catches a missing jar, a broken entrypoint or an incompatible base,
# which are the image defects this script is for. It does not catch a broken query, and does not
# pretend to.
#
# Usage:
#   scripts/ci/check-image-releaseability.sh <learning-platform|web-ui|ai>
set -uo pipefail

COMPONENT="${1:-}"
case "${COMPONENT}" in
  learning-platform) DOCKERFILE="learning-platform/Dockerfile" ;;
  web-ui)            DOCKERFILE="web-ui/Dockerfile" ;;
  ai)                DOCKERFILE="ramals-ai/Dockerfile" ;;
  *) echo "usage: $0 <learning-platform|web-ui|ai>" >&2; exit 2 ;;
esac

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "${REPO_ROOT}"

TAG="ramals-${COMPONENT}:releaseability"
CONTAINER="releaseability-${COMPONENT}-$$"
WORK="$(mktemp -d)"
# Kept identical to release.yml. Drifting from it would produce a check that passes here and fails
# there, which is worse than not checking at all: it spends the reviewer's trust without earning it.
TRIVY_IMAGE="aquasec/trivy:0.64.1"

cleanup() {
  docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
  docker rm -f "${CONTAINER}-db" >/dev/null 2>&1 || true
  rm -rf "${WORK}"
}
trap cleanup EXIT INT TERM

fail() { printf '\nFAIL  %s\n' "$*"; exit 1; }
step() { printf '\n=== %s\n' "$*"; }

# -- build -------------------------------------------------------------------------------------------

step "Building ${COMPONENT} from ${DOCKERFILE}"
docker build --quiet --file "${DOCKERFILE}" --tag "${TAG}" . >/dev/null \
  || fail "${COMPONENT} does not build; the release would fail here too"
echo "  built ${TAG}"

# -- scan --------------------------------------------------------------------------------------------

step "Scanning with the release gate's flags"
# Scanned from a tarball rather than over the Docker socket. Mounting the socket needs it to exist at
# a predictable path, which is not true across the Linux runners and the Windows workstations this
# has to run on, and a check that only runs in one place is one nobody runs before pushing.
docker save "${TAG}" -o "${WORK}/image.tar" || fail "could not export ${TAG} for scanning"

# Both halves of the mount need opposite treatment on Git Bash, which is why this is not one flag.
#
# The container path must NOT be translated -- left alone, Git Bash turns /scan/image.tar into
# C:/Program Files/Git/scan/image.tar, and Trivy is handed a path that cannot exist. MSYS_NO_PATHCONV
# stops that.
#
# The host path must be, and in the other direction: Docker Desktop is a Windows process and cannot
# resolve an MSYS path like /tmp/tmp.AbC123, so cygpath converts it. Suppressing translation without
# doing this fixes the container side and breaks the host side, which is the state this was in a
# moment ago.
#
# cygpath does not exist on the Linux runners, where no translation happens and the fallback is the
# path itself.
mkdir -p "${HOME}/.cache/trivy"
HOST_WORK="$(cygpath -w "${WORK}" 2>/dev/null || printf '%s' "${WORK}")"
HOST_CACHE="$(cygpath -w "${HOME}/.cache/trivy" 2>/dev/null || printf '%s' "${HOME}/.cache/trivy")"
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "${HOST_WORK}:/scan" \
  -v "${HOST_CACHE}:/root/.cache/trivy" \
  "${TRIVY_IMAGE}" image \
    --severity CRITICAL,HIGH \
    --ignore-unfixed \
    --exit-code 1 \
    --format table \
    --skip-version-check \
    --quiet \
    --input /scan/image.tar > "${WORK}/scan.txt" 2>&1
scan_status=$?
cat "${WORK}/scan.txt"

# A tool that could not run is neither a clean bill of health nor a finding. Reporting one as the
# other sends whoever reads it hunting a CVE that does not exist, which is exactly what the first
# version of this script did when Git Bash rewrote the path it had been given.
if grep -q "FATAL" "${WORK}/scan.txt"; then
  fail "Trivy could not scan ${COMPONENT}: this is a broken check, not a vulnerable image"
fi
[ "${scan_status}" -eq 0 ] || fail "${COMPONENT} has fixable CRITICAL/HIGH findings; the release gate would refuse to publish it"
echo "  no fixable CRITICAL/HIGH findings"

# -- smoke -------------------------------------------------------------------------------------------

wait_for() { # wait_for <url> <seconds>
  local url="$1" limit="$2" waited=0
  until curl -fsS "${url}" >/dev/null 2>&1; do
    waited=$((waited + 1))
    [ "${waited}" -ge "${limit}" ] && return 1
    sleep 1
  done
  return 0
}

case "${COMPONENT}" in
  ai)
    step "Smoke: the AI plane serves and refuses unauthenticated agent calls"
    docker run -d --name "${CONTAINER}" -p 18099:8000 "${TAG}" >/dev/null \
      || fail "container did not start"
    wait_for "http://localhost:18099/health/live" 45 \
      || fail "no liveness response within 45s; $(docker logs --tail 20 "${CONTAINER}" 2>&1 | tail -5)"
    echo "  /health/live responded"

    status="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
      http://localhost:18099/internal/v1/tutor/respond 2>/dev/null || echo 000)"
    # The workload boundary, checked on the built artifact rather than only in the test suite. An
    # image that serves agent endpoints to anybody is the one defect here worth failing a merge for.
    [ "${status}" = "401" ] || fail "unauthenticated agent call returned ${status}, expected 401"
    echo "  unauthenticated agent call refused with 401"
    ;;

  web-ui)
    step "Smoke: the web UI serves its entrypoint document"
    # --add-host because nginx resolves the names in an upstream block at startup and refuses to
    # start when one is missing: "host not found in upstream 'backend'". Outside the compose network
    # there is no such host. Pointing it at loopback satisfies the resolution without pretending an
    # API is there -- nothing proxied is requested here, only the static document, which is what
    # this image is responsible for.
    docker run -d --name "${CONTAINER}" -p 18098:8080 \
      --add-host "backend:127.0.0.1" \
      "${TAG}" >/dev/null || fail "container did not start"
    wait_for "http://localhost:18098/" 45 \
      || fail "no response within 45s; $(docker logs --tail 20 "${CONTAINER}" 2>&1 | tail -5)"
    body="$(curl -fsS http://localhost:18098/ 2>/dev/null)"
    # A 200 that serves nginx's stock page means the build output never made it in, which is exactly
    # the sort of thing a status-code-only check waves through.
    printf '%s' "${body}" | grep -qi "<div id=\"root\"\|<script" \
      || fail "served a document with no application in it; the build output may be missing"
    echo "  served an application document"
    ;;

  learning-platform)
    step "Smoke: the artifact is runnable"
    # No database here on purpose. The backend needs one with provisioned roles plus an identity
    # provider, and standing that up is a deployment, not a smoke test -- reusable-backend-ci.yml
    # already runs the real thing against PostgreSQL. What is checked is narrower and still worth
    # checking: that the jar is present, executable, and gets as far as its own configuration.
    docker run -d --name "${CONTAINER}" \
      -e RAMALS_DB_URL="jdbc:postgresql://127.0.0.1:5432/absent" \
      -e RAMALS_DB_PASSWORD="unused" \
      -e RAMALS_DB_MIGRATION_PASSWORD="unused" \
      "${TAG}" >/dev/null || fail "container did not start"

    started=false
    for _ in $(seq 1 60); do
      if docker logs "${CONTAINER}" 2>&1 | grep -qiE "Starting .*Application|Spring Boot|o\.s\.b\.|Tomcat initialized"; then
        started=true
        break
      fi
      # A container that has already exited will never log anything more.
      if [ "$(docker inspect -f '{{.State.Running}}' "${CONTAINER}" 2>/dev/null)" != "true" ]; then
        break
      fi
      sleep 1
    done

    if [ "${started}" != "true" ]; then
      echo "--- last 25 log lines ---"
      docker logs --tail 25 "${CONTAINER}" 2>&1 | sed 's/^/    /'
      fail "the application never started; the image itself is broken rather than merely unconfigured"
    fi
    echo "  the JVM started the application and reached its configuration stage"
    ;;
esac

printf '\n%s would publish: builds, scans clean, and runs.\n' "${COMPONENT}"
