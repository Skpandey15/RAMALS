#!/usr/bin/env bash
set -euo pipefail

base_sha="${1:?base SHA is required}"
head_sha="${2:?head SHA is required}"
output_file="${GITHUB_OUTPUT:-/dev/stdout}"

mapfile -t changed < <(git diff --name-only "$base_sha" "$head_sha")

backend=false
frontend=false
python=false
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
  echo "database=$database"
  echo "infrastructure=$infrastructure"
  echo "docs_only=$docs_only"
} >> "$output_file"

