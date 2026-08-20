#!/usr/bin/env bash
# Verifies the deployment state machine without a container runtime: a bad deployment rolls back
# and holds the release, and a held version is never automatically redeployed.
#
# The stubs deliberately RECORD the image references they are handed, and health is a function of
# the artefact being deployed rather than a fixed answer. An earlier version of this harness
# stubbed pull/up as `true` and health as a constant, which made the stubs blind to the image env
# entirely — so the suite passed 14/14 while the real rollback re-deployed the FAILED digest and
# the state file recorded a rollback that never happened. Asserting on `state` alone is not enough:
# the assertions below check which digest was actually deployed last.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "${HERE}/../.." && pwd)"
CONTROLLER="${REPO}/deploy/deploy-controller.sh"
WORK="$(mktemp -d)"
export WORK_DIR="${WORK}"
trap 'rm -rf "${WORK}"' EXIT

if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
else echo "python3 or python is required" >&2; exit 1
fi

failures=0
check() { # check <description> <actual> <expected>
  if [ "$2" = "$3" ]; then
    printf 'ok   %s\n' "$1"
  else
    printf 'FAIL %s (expected "%s", got "%s")\n' "$1" "$3" "$2"
    failures=$((failures + 1))
  fi
}

# Stub deploy: record the image reference it was asked to run, so tests can assert on it.
cat > "${WORK}/up.sh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "${RAMALS_BACKEND_IMAGE:-none}" >> "${WORK_DIR}/deployed.log"
EOF

# Stub health: unhealthy exactly when the deployed artefact carries the digest marked bad. This
# mirrors reality — health is a property of what is running, not of the calendar.
cat > "${WORK}/health.sh" <<'EOF'
#!/usr/bin/env bash
bad="$(cat "${WORK_DIR}/bad-digest" 2>/dev/null)"
[ -n "${bad}" ] || exit 0
case "${RAMALS_BACKEND_IMAGE:-}${RAMALS_AI_IMAGE:-}" in
  *"${bad}"*) exit 1 ;;
esac
exit 0
EOF
chmod +x "${WORK}/up.sh" "${WORK}/health.sh"
: > "${WORK}/bad-digest"

ai_manifest() { # ai_manifest <commit> <digest-suffix> -- a three-component release (M1-T17)
  cat > "${WORK}/desired.json" <<EOF
{
  "manifest_version": 1,
  "environment": "test",
  "release": { "commit": "$1", "version": "v0.0.0-test" },
  "components": {
    "learning-platform": { "image": "ghcr.io/test/lp", "digest": "sha256:$2" },
    "web-ui": { "image": "ghcr.io/test/ui", "digest": "sha256:$2" },
    "ramals-ai": { "image": "ghcr.io/test/ai", "digest": "sha256:$2" }
  }
}
EOF
}

manifest() { # manifest <commit> <digest-suffix>
  cat > "${WORK}/desired.json" <<EOF
{
  "manifest_version": 1,
  "environment": "test",
  "release": { "commit": "$1", "version": "v0.0.0-test" },
  "components": {
    "learning-platform": { "image": "ghcr.io/test/lp", "digest": "sha256:$2" },
    "web-ui": { "image": "ghcr.io/test/ui", "digest": "sha256:$2" }
  }
}
EOF
}

run() { # run  -> echoes exit code
  RAMALS_DESIRED_MANIFEST="${WORK}/desired.json" \
  RAMALS_DEPLOY_STATE="${WORK}/state.json" \
  RAMALS_PULL_CMD="true" \
  RAMALS_UP_CMD="${WORK}/up.sh" \
  RAMALS_HEALTH_CMD="${WORK}/health.sh" \
    bash "${CONTROLLER}" >/dev/null 2>&1
  echo $?
}

state_field() { "${PY}" -c "
import json,sys
print(json.load(open(sys.argv[1])).get(sys.argv[2], '<absent>'))
" "${WORK}/state.json" "$1"; }

last_deployed() { tail -n 1 "${WORK}/deployed.log" 2>/dev/null || echo "<none>"; }

aaaa=$(printf 'a%.0s' {1..64})
bbbb=$(printf 'b%.0s' {1..64})

# 1. Healthy deployment of a good version.
manifest good-commit "${aaaa}"
check "good deploy exits 0" "$(run)" "0"
check "state is HEALTHY" "$(state_field state)" "HEALTHY"
check "known-good recorded" "$(state_field known_good_commit)" "good-commit"
# The commit alone cannot drive a rollback; the digests must be recorded too.
check "known-good backend digest recorded" \
  "$(state_field known_good_backend_image)" "ghcr.io/test/lp@sha256:${aaaa}"
check "known-good web-ui digest recorded" \
  "$(state_field known_good_webui_image)" "ghcr.io/test/ui@sha256:${aaaa}"

# 2. Reconciling the same healthy version is a no-op.
check "reconcile healthy exits 0" "$(run)" "0"
check "state still HEALTHY" "$(state_field state)" "HEALTHY"

# 3. A bad version fails health gates: roll back and hold.
printf '%s' "${bbbb}" > "${WORK}/bad-digest"
manifest bad-commit "${bbbb}"
check "bad deploy exits 3" "$(run)" "3"
check "state is RELEASE_HELD" "$(state_field state)" "RELEASE_HELD"
check "rolled back to known-good" "$(state_field current_commit)" "good-commit"
# The assertion that matters: the environment was actually returned to the known-good ARTEFACT.
check "known-good digest was actually redeployed" \
  "$(last_deployed)" "ghcr.io/test/lp@sha256:${aaaa}"

# 4. Anti-flapping: the held version must not be redeployed automatically, even if it would now
#    pass health gates. Only a human correcting/re-approving the manifest releases the hold.
: > "${WORK}/bad-digest"
check "held version refuses redeploy (exit 2)" "$(run)" "2"
check "state remains RELEASE_HELD" "$(state_field state)" "RELEASE_HELD"
check "held version was not deployed" "$(last_deployed)" "ghcr.io/test/lp@sha256:${aaaa}"

# 5. A corrected version deploys normally and clears the path forward.
manifest fixed-commit "${aaaa}"
check "corrected version deploys (exit 0)" "$(run)" "0"
check "state is HEALTHY again" "$(state_field state)" "HEALTHY"
check "held version still recorded" \
  "$("${PY}" -c "
import json,sys
print('bad-commit' in json.load(open(sys.argv[1]))['held_versions'])
" "${WORK}/state.json")" "True"

# 6. A failure with no known-good digests on record must NOT claim a rollback.
rm -f "${WORK}/state.json" "${WORK}/deployed.log"
printf '%s' "${bbbb}" > "${WORK}/bad-digest"
manifest first-ever-commit "${bbbb}"
check "first-ever bad deploy exits 3" "$(run)" "3"
check "no false rollback claim" "$(state_field current_commit)" "first-ever-commit"

# 7. A mutable tag is never deployable.
cat > "${WORK}/desired.json" <<'EOF'
{
  "manifest_version": 1, "environment": "test",
  "release": { "commit": "mutable-commit", "version": "v0" },
  "components": {
    "learning-platform": { "image": "ghcr.io/test/lp", "digest": "latest" },
    "web-ui": { "image": "ghcr.io/test/ui", "digest": "latest" }
  }
}
EOF
check "mutable tag rejected (exit 1)" "$(run)" "1"

# --- the AI plane is a released component (M1-T17) -----------------------------------------------
#
# Deploying a component you cannot roll back is worse than not deploying it: the first bad release
# strands it, and the only way back is by hand. So both halves are checked.

rm -f "${WORK}/state.json"
: > "${WORK}/bad-digest"
ai_manifest ai-good "${aaaa}"
check "three-component deploy exits 0" "$(run)" "0"
check "three-component state is HEALTHY" "$(state_field state)" "HEALTHY"
check "known-good AI digest recorded"   "$(state_field known_good_ai_image)" "ghcr.io/test/ai@sha256:${aaaa}"

# A bad AI digest must roll the whole release back rather than strand the AI plane on a broken
# image. The health stub judges the AI image too, so this can actually fail.
echo "${bbbb}" > "${WORK}/bad-digest"
ai_manifest ai-bad "${bbbb}"
check "bad AI release rolls back (exit 3)" "$(run)" "3"
check "held after bad AI release" "$(state_field state)" "RELEASE_HELD"
check "AI known-good survives the rollback"   "$(state_field known_good_ai_image)" "ghcr.io/test/ai@sha256:${aaaa}"

# A mutable AI tag is refused exactly as a mutable backend tag is. Found by perturbation: removing
# the AI digest from the immutability loop passed every other check in this file, because nothing
# had ever supplied one.
rm -f "${WORK}/state.json"
cat > "${WORK}/desired.json" <<EOF
{
  "manifest_version": 1,
  "environment": "test",
  "release": { "commit": "ai-mutable", "version": "v0.0.0-test" },
  "components": {
    "learning-platform": { "image": "ghcr.io/test/lp", "digest": "sha256:${aaaa}" },
    "web-ui": { "image": "ghcr.io/test/ui", "digest": "sha256:${aaaa}" },
    "ramals-ai": { "image": "ghcr.io/test/ai", "digest": "latest" }
  }
}
EOF
check "mutable AI tag rejected (exit 1)" "$(run)" "1"

# A manifest without the AI plane stays valid: a deterministic-only deployment is a supported
# configuration, not an incomplete manifest.
rm -f "${WORK}/state.json"
: > "${WORK}/bad-digest"
manifest no-ai "${aaaa}"
check "two-component manifest still deploys" "$(run)" "0"
check "AI known-good is 'none' when unconfigured" "$(state_field known_good_ai_image)" "none"

if [ "${failures}" -gt 0 ]; then
  printf '\n%d deployment state-machine check(s) failed\n' "${failures}"
  exit 1
fi
printf '\nAll deployment state-machine checks passed.\n'
