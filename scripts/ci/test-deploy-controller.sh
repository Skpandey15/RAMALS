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
printf '%s\n' "${RAMALS_AI_IMAGE:-none}" >> "${WORK_DIR}/deployed-ai.log"
printf '%s
' "${COMPOSE_PROFILES:-none}" >> "${WORK_DIR}/deployed-profiles.log"
printf '%s
' "${AI_URL:-none}" >> "${WORK_DIR}/deployed-aiurl.log"
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

mixed_manifest() { # mixed_manifest <commit> <core-digest> <ai-digest>
  cat > "${WORK}/desired.json" <<EOF
{
  "manifest_version": 1,
  "environment": "test",
  "release": { "commit": "$1", "version": "v0.0.0-test" },
  "components": {
    "learning-platform": { "image": "ghcr.io/test/lp", "digest": "sha256:$2" },
    "web-ui": { "image": "ghcr.io/test/ui", "digest": "sha256:$2" },
    "ramals-ai": { "image": "ghcr.io/test/ai", "digest": "sha256:$3" }
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
last_deployed_ai() { tail -n 1 "${WORK}/deployed-ai.log" 2>/dev/null || echo "<none>"; }
last_profiles() { tail -n 1 "${WORK}/deployed-profiles.log" 2>/dev/null || echo "<none>"; }
last_ai_url() { tail -n 1 "${WORK}/deployed-aiurl.log" 2>/dev/null || echo "<none>"; }

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
rm -f "${WORK}/state.json" "${WORK}/deployed.log" "${WORK}/deployed-ai.log" "${WORK}/deployed-profiles.log" "${WORK}/deployed-aiurl.log"
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

# The state field surviving is not the same as the plane being restored, and the difference
# is a mixed-version environment. The rollback exported the backend and web-ui digests and
# not the AI one, so the plane kept running the FAILED image while the state file said
# ROLLED_BACK. Observed live during RC8 requalification, and invisible here because the up
# stub recorded only the backend image -- exactly the blindness this header warns about.
check "AI plane was actually returned to the known-good image" \
  "$(last_deployed_ai)" "ghcr.io/test/ai@sha256:${aaaa}"
check "backend came back with it, so the environment is not mixed-version" \
  "$(last_deployed)" "ghcr.io/test/lp@sha256:${aaaa}"

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

# A rollback that cannot restore the AI plane must not claim to have rolled back.
#
# The known-good ran no AI plane and the failed release introduced one, so there is no digest to
# return it to, and swapping a digest cannot express removing a component.
#
# Only the CORE digest is marked bad here. That is deliberate: if the AI digest were bad too, the
# rollback health probe would fail on its own and this would pass without the flag ever being
# read -- which is exactly how the first version of this test passed under perturbation.
rm -f "${WORK}/state.json" "${WORK}/deployed.log" "${WORK}/deployed-ai.log" "${WORK}/deployed-profiles.log" "${WORK}/deployed-aiurl.log"
: > "${WORK}/bad-digest"
manifest core-only-good "${aaaa}"
check "core-only baseline deploys" "$(run)" "0"
check "baseline records no AI plane" "$(state_field known_good_ai_image)" "none"

printf '%s' "${bbbb}" > "${WORK}/bad-digest"
mixed_manifest ai-introduced-bad "${bbbb}" "${aaaa}"
check "bad release that adds the AI plane exits 3" "$(run)" "3"
check "held after a bad AI-introducing release" "$(state_field state)" "RELEASE_HELD"
# The core rollback itself is healthy -- the AI image is good and the core is back on aaaa -- so
# the only thing that can refuse the clean-rollback claim is the unrestorable AI plane.
check "core did return to the known-good image" "$(last_deployed)" "ghcr.io/test/lp@sha256:${aaaa}"
check "no clean-rollback claim when the AI plane cannot be restored" "$(state_field current_commit)" "ai-introduced-bad"

# The AI gates must actually be able to run.
#
# health-gates.sh keys its AI section on AI_URL and skips when it is unset -- correct for a
# deterministic-only deployment. The controller never set it, so on every controller-driven
# deployment that DID have a plane the AI gates silently skipped. A gate that cannot fail is not
# a gate. AI_URL is the published address, not RAMALS_AI_BASE_URL, because the gate runs on the
# host while the base URL is the in-network name the backend container uses.
rm -f "${WORK}/state.json" "${WORK}/deployed.log" "${WORK}/deployed-ai.log" "${WORK}/deployed-profiles.log" "${WORK}/deployed-aiurl.log"
: > "${WORK}/bad-digest"
ai_manifest ai-gates "${aaaa}"
check "AI deploy exits 0" "$(run)" "0"
check "the ai-plane profile is active" "$(last_profiles)" "ai-plane"
check "the gate is told how to reach the AI plane" "$(last_ai_url)" "http://localhost:8000"

# A release that DROPS the AI plane must still roll back to a known-good that ran one.
#
# The rollback exported the known-good AI digest but not the profile, so Compose was handed a
# digest for a service it had never been told to start and the plane was not restored.
rm -f "${WORK}/state.json" "${WORK}/deployed.log" "${WORK}/deployed-ai.log" "${WORK}/deployed-profiles.log" "${WORK}/deployed-aiurl.log"
: > "${WORK}/bad-digest"
ai_manifest with-ai-good "${aaaa}"
check "baseline with an AI plane deploys" "$(run)" "0"
check "baseline records the AI digest" "$(state_field known_good_ai_image)" "ghcr.io/test/ai@sha256:${aaaa}"

printf '%s' "${bbbb}" > "${WORK}/bad-digest"
manifest ai-dropped-bad "${bbbb}"
check "release dropping the AI plane fails and rolls back" "$(run)" "3"
check "rollback restored the known-good AI image" "$(last_deployed_ai)" "ghcr.io/test/ai@sha256:${aaaa}"
check "rollback reactivated the ai-plane profile" "$(last_profiles)" "ai-plane"
check "rollback is claimed clean" "$(state_field current_commit)" "with-ai-good"

if [ "${failures}" -gt 0 ]; then
  printf '\n%d deployment state-machine check(s) failed\n' "${failures}"
  exit 1
fi
printf '\nAll deployment state-machine checks passed.\n'
