#!/usr/bin/env bash
set -euo pipefail

base_sha="${1:?base SHA is required}"
head_sha="${2:?head SHA is required}"
output_file="${GITHUB_OUTPUT:-/dev/stdout}"

mapfile -t changed < <(git diff --name-only "$base_sha" "$head_sha")

backend=false
frontend=false
python=false
contract=false
database=false
infrastructure=false
docs_only=true

for path in "${changed[@]}"; do
  case "$path" in
    learning-platform/*|build.gradle|settings.gradle|gradle.properties|gradlew|gradlew.bat|gradle/*)
      backend=true
      docs_only=false
      ;;
    web-ui/*)
      frontend=true
      docs_only=false
      ;;
    contracts/*)
      contract=true
      python=true
      backend=true
      docs_only=false
      ;;
    ramals-ai/README.md)
      ;;
    ramals-ai/*)
      python=true
      docs_only=false
      ;;
    infrastructure/*|.github/*|scripts/ci/*)
      infrastructure=true
      docs_only=false
      ;;
    docs/database/*)
      database=true
      ;;
    # The release board and its evidence are executable documents: Mvp1ReleaseBoardTests reads them,
    # and it is the gate that stops R1 being closed by editing a word in a table. That test lives in
    # the backend module, so classifying these as docs-only skipped Backend CI and the guard never
    # ran on the one kind of change it exists to police -- including the change that closed R1.
    # A gate that cannot run on the thing it guards is not a gate.
    docs/release/*)
      backend=true
      docs_only=false
      ;;
    docs/*|README.md|.editorconfig|.gitignore|.env.example|knowledge/*)
      ;;
    *)
      docs_only=false
      ;;
  esac

  case "$path" in
    learning-platform/src/main/resources/db/migration/*)
      database=true
      ;;
  esac
done

{
  echo "backend=$backend"
  echo "frontend=$frontend"
  echo "python=$python"
  echo "contract=$contract"
  echo "database=$database"
  echo "infrastructure=$infrastructure"
  echo "docs_only=$docs_only"
} >> "$output_file"

