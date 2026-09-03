#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
temp_repo="$(mktemp -d)"
trap 'rm -rf "$temp_repo"' EXIT

git -C "$temp_repo" init --quiet
git -C "$temp_repo" config user.email ci-test@ramals.invalid
git -C "$temp_repo" config user.name RAMALS-CI-Test
echo baseline > "$temp_repo/README.md"
git -C "$temp_repo" add README.md
git -C "$temp_repo" commit --quiet -m baseline
base="$(git -C "$temp_repo" rev-parse HEAD)"

run_case() {
  local path="$1" expected_backend="$2" expected_frontend="$3" expected_infrastructure="$4" expected_docs_only="$5"
  git -C "$temp_repo" reset --hard --quiet "$base"
  git -C "$temp_repo" clean -fdq
  mkdir -p "$(dirname "$temp_repo/$path")"
  echo change > "$temp_repo/$path"
  git -C "$temp_repo" add "$path"
  git -C "$temp_repo" commit --quiet -m "test $path"
  local output="$temp_repo/output"
  (cd "$temp_repo" && GITHUB_OUTPUT="$output" "$repo_root/scripts/ci/detect-changes.sh" "$base" HEAD)
  grep -qx "backend=$expected_backend" "$output"
  grep -qx "frontend=$expected_frontend" "$output"
  grep -qx "infrastructure=$expected_infrastructure" "$output"
  grep -qx "docs_only=$expected_docs_only" "$output"
}

run_case learning-platform/src/main/java/Example.java true false false false
run_case web-ui/src/Example.tsx false true false false
run_case docs/architecture/example.md false false false true
run_case deploy/jenkins/install-local.ps1 false false true false

# The Jenkinsfile is what validate-jenkins-cd.ps1 asserts the invariants of, and it sits at the
# repository root rather than under any of the directories beside it -- so a change to it used to
# match nothing and skip the only job that guards it. Editing the pipeline was the single change
# that could not trigger the pipeline's own gate.
run_case Jenkinsfile false false true false

# The release board is an executable document. Mvp1ReleaseBoardTests reads it and is what stops R1
# being closed by editing a word in a table -- and that test lives in the backend module, so a board
# change classified as docs-only skips Backend CI and the guard never runs on the one kind of change
# it exists to police. It has to pull backend in.
run_case docs/release/mvp1-release-board.md true false false false
run_case docs/release/evidence/example.md true false false false

# Documentation that no test reads stays docs-only. Widening the trigger to all of docs/ would run
# the backend suite on every typo, and a suite that runs when it cannot fail is one people learn to
# ignore.
run_case docs/architecture/nested/example.md false false false true

echo 'Backend, frontend, Jenkins infrastructure, docs, and release-board change detection passed.'
