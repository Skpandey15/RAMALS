#!/usr/bin/env bash
# Capture EXPLAIN (ANALYZE, BUFFERS) plans for the hot-path queries as the least-privileged runtime
# role. Output is timestamped under performance/results/ and forms part of the archived baseline.
set -euo pipefail

DB_URL="${RAMALS_DB_URL:-postgresql://ramals_core_runtime@localhost:5432/ramals}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${HERE}/../results"
mkdir -p "${OUT_DIR}"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${OUT_DIR}/db-explain-${STAMP}.txt"

{
  echo "# RAMALS DB hot-path plans"
  echo "# captured_at: ${STAMP}"
  echo "# db_version: $(psql "${DB_URL}" -tAc 'SHOW server_version' 2>/dev/null || echo unknown)"
  echo
  psql "${DB_URL}" -v ON_ERROR_STOP=1 -f "${HERE}/explain-analyze.sql"
} | tee "${OUT}"

echo "Wrote ${OUT}"
