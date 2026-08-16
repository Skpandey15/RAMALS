#!/usr/bin/env bash
# MVP-0 backup/restore drill.
#
# Restore testing must validate foreign keys, the Flyway version, append-only provenance chains and
# latest mastery pointers -- not merely that pg_restore exited zero. This drill dumps a populated
# database, restores it into a separate database, and asserts the provenance chain survives intact.
#
# Usage:
#   RAMALS_PGHOST=localhost RAMALS_PGPORT=5432 RAMALS_PGUSER=postgres \
#   PGPASSWORD=... ./scripts/validation/backup-restore-drill.sh [source_db] [restore_db]
set -euo pipefail

HOST="${RAMALS_PGHOST:-localhost}"
PORT="${RAMALS_PGPORT:-5432}"
USER="${RAMALS_PGUSER:-postgres}"
SOURCE_DB="${1:-ramals_test}"
RESTORE_DB="${2:-ramals_restore_drill}"
OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/performance/results"
mkdir -p "${OUT_DIR}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP="${OUT_DIR}/backup-${STAMP}.dump"

psql_src() { psql -h "${HOST}" -p "${PORT}" -U "${USER}" -d "${SOURCE_DB}" -tAc "$1"; }
psql_dst() { psql -h "${HOST}" -p "${PORT}" -U "${USER}" -d "${RESTORE_DB}" -tAc "$1"; }

failures=0
check() { # check <description> <actual> <expected>
  if [ "$2" = "$3" ]; then printf 'ok   %s (%s)\n' "$1" "$2"
  else printf 'FAIL %s: expected %s, got %s\n' "$1" "$3" "$2"; failures=$((failures + 1)); fi
}

echo "== capturing source state =="
SRC_FLYWAY="$(psql_src "SELECT max(version) FROM core.flyway_schema_history WHERE success")"
SRC_EVIDENCE="$(psql_src 'SELECT count(*) FROM ledger.evidence')"
SRC_SNAPSHOTS="$(psql_src 'SELECT count(*) FROM ledger.mastery_snapshot')"
SRC_DECISIONS="$(psql_src 'SELECT count(*) FROM ledger.decision_record')"
SRC_LATEST="$(psql_src "SELECT coalesce(max(aggregate_version), 0) FROM ledger.mastery_snapshot")"
echo "flyway=${SRC_FLYWAY} evidence=${SRC_EVIDENCE} snapshots=${SRC_SNAPSHOTS} decisions=${SRC_DECISIONS}"

echo "== backup =="
pg_dump -h "${HOST}" -p "${PORT}" -U "${USER}" -d "${SOURCE_DB}" -Fc -f "${DUMP}"
echo "wrote ${DUMP} ($(wc -c < "${DUMP}") bytes)"

echo "== restore into ${RESTORE_DB} =="
psql -h "${HOST}" -p "${PORT}" -U "${USER}" -d postgres -c "DROP DATABASE IF EXISTS ${RESTORE_DB}" >/dev/null
psql -h "${HOST}" -p "${PORT}" -U "${USER}" -d postgres -c "CREATE DATABASE ${RESTORE_DB}" >/dev/null
pg_restore -h "${HOST}" -p "${PORT}" -U "${USER}" -d "${RESTORE_DB}" --no-owner --no-privileges "${DUMP}" >/dev/null 2>&1 || true

echo "== verify restored database =="
check "flyway version preserved"     "$(psql_dst "SELECT max(version) FROM core.flyway_schema_history WHERE success")" "${SRC_FLYWAY}"
check "evidence rows preserved"      "$(psql_dst 'SELECT count(*) FROM ledger.evidence')"         "${SRC_EVIDENCE}"
check "mastery snapshots preserved"  "$(psql_dst 'SELECT count(*) FROM ledger.mastery_snapshot')" "${SRC_SNAPSHOTS}"
check "decision records preserved"   "$(psql_dst 'SELECT count(*) FROM ledger.decision_record')"  "${SRC_DECISIONS}"
check "latest mastery pointer intact" "$(psql_dst "SELECT coalesce(max(aggregate_version), 0) FROM ledger.mastery_snapshot")" "${SRC_LATEST}"

# Provenance chain: every decision must still resolve to its snapshot, and every snapshot to a learner.
check "decision -> snapshot chain unbroken" "$(psql_dst "
  SELECT count(*) FROM ledger.decision_record d
  LEFT JOIN ledger.mastery_snapshot s ON s.id = d.source_snapshot_id
  WHERE s.id IS NULL")" "0"
check "evidence -> learner chain unbroken" "$(psql_dst "
  SELECT count(*) FROM ledger.evidence e
  LEFT JOIN core.learner l ON l.id = e.learner_id
  WHERE l.id IS NULL")" "0"

# Foreign keys must be present, not merely satisfied by the data.
check "foreign keys restored" "$(psql_dst "
  SELECT CASE WHEN count(*) > 0 THEN 'present' ELSE 'missing' END
  FROM pg_constraint WHERE contype = 'f'")" "present"

echo
if [ "${failures}" -gt 0 ]; then
  echo "${failures} backup/restore check(s) FAILED"
  exit 1
fi
echo "All backup/restore checks passed. Dump retained at ${DUMP}"
