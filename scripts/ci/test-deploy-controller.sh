#!/usr/bin/env bash
# Verifies the deployment state machine without a container runtime: a bad deployment rolls back
# and holds the release, and a held version is never automatically redeployed.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "${HERE}/../.." && pwd)"
CONTROLLER="${REPO}/deploy/deploy-controller.sh"
WORK="$(mktemp -d)"
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
  RAMALS_UP_CMD="true" \
  RAMALS_HEALTH_CMD="${HEALTH}" \
    bash "${CONTROLLER}" >/dev/null 2>&1
  echo $?
}

state_field() { "${PY}" -c "
import json,sys
print(json.load(open(sys.argv[1]))[sys.argv[2]])
" "${WORK}/state.json" "$1"; }

aaaa=$(printf 'a%.0s' {1..64})
bbbb=$(printf 'b%.0s' {1..64})

# 1. Healthy deployment of a good version.
HEALTH="true"
manifest good-commit "${aaaa}"
check "good deploy exits 0" "$(run)" "0"
check "state is HEALTHY" "$(state_field state)" "HEALTHY"
check "known-good recorded" "$(state_field known_good_commit)" "good-commit"

# 2. Reconciling the same healthy version is a no-op.
check "reconcile healthy exits 0" "$(run)" "0"
check "state still HEALTHY" "$(state_field state)" "HEALTHY"

# 3. A bad version fails health gates: roll back and hold.
HEALTH="false"
manifest bad-commit "${bbbb}"
check "bad deploy exits 3" "$(run)" "3"
check "state is RELEASE_HELD" "$(state_field state)" "RELEASE_HELD"
check "rolled back to known-good" "$(state_field current_commit)" "good-commit"

# 4. Anti-flapping: the held version must not be redeployed automatically, even if it would now
#    pass health gates. Only a human correcting/re-approving the manifest releases the hold.
HEALTH="true"
check "held version refuses redeploy (exit 2)" "$(run)" "2"
check "state remains RELEASE_HELD" "$(state_field state)" "RELEASE_HELD"

# 5. A corrected version deploys normally and clears the path forward.
manifest fixed-commit "${aaaa}"
check "corrected version deploys (exit 0)" "$(run)" "0"
check "state is HEALTHY again" "$(state_field state)" "HEALTHY"
check "held version still recorded" \
  "$("${PY}" -c "
import json,sys
print('bad-commit' in json.load(open(sys.argv[1]))['held_versions'])
" "${WORK}/state.json")" "True"

# 6. A mutable tag is never deployable.
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

if [ "${failures}" -gt 0 ]; then
  printf '\n%d deployment state-machine check(s) failed\n' "${failures}"
  exit 1
fi
printf '\nAll deployment state-machine checks passed.\n'
