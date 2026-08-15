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
  local path="$1" expected_backend="$2" expected_frontend="$3" expected_docs_only="$4"
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
  grep -qx "docs_only=$expected_docs_only" "$output"
}

run_case learning-platform/src/main/java/Example.java true false false
run_case web-ui/src/Example.tsx false true false
run_case docs/architecture/example.md false false true

echo 'Backend-only, frontend-only, and docs-only change detection passed.'
