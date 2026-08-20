#!/usr/bin/env bash
# Tests scripts/ci/check-migration-compatibility.py against fixture migrations.
#
# The fixtures matter as much as the rules. A guard over SQL is mostly an exercise in not crying
# wolf: a comment describing a DROP COLUMN is not one, a NOT NULL column inside CREATE TABLE is not
# a compatibility problem, and a trigger body that raises 'cannot drop column' is prose. A checker
# that flagged those would be turned off within a week, and then the real cases go through too.
#
# So the negative cases below are not padding -- they are the reason the positive ones stay enabled.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CHECK="${REPO_ROOT}/scripts/ci/check-migration-compatibility.py"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

PYTHON="${PYTHON:-python}"
command -v "${PYTHON}" >/dev/null || PYTHON=python3

FAILURES=0
check() { # check <name> <expected-exit> <actual-exit>
  if [ "$2" = "$3" ]; then
    printf 'ok   %s\n' "$1"
  else
    printf 'FAIL %s (expected exit %s, got %s)\n' "$1" "$2" "$3"
    printf '     --- checker output ---\n'
    sed 's/^/     /' "${WORK}/out"
    FAILURES=$((FAILURES + 1))
  fi
}

run_on() { # run_on <sql-body>
  rm -rf "${WORK}/migrations"
  mkdir -p "${WORK}/migrations"
  printf '%s\n' "$1" > "${WORK}/migrations/V900__fixture.sql"
  "${PYTHON}" "${CHECK}" "${WORK}/migrations" > "${WORK}/out" 2>&1
  echo "$?"
}

# -- what a compatible migration looks like --------------------------------------------------------

status="$(run_on 'ALTER TABLE core.thing ADD COLUMN note VARCHAR(64);')"
check "a nullable added column is compatible" "0" "${status}"

status="$(run_on 'ALTER TABLE core.thing ADD COLUMN note VARCHAR(64) NOT NULL DEFAULT '"'"'x'"'"';')"
check "NOT NULL with a default is compatible" "0" "${status}"

status="$(run_on 'CREATE TABLE core.fresh (id UUID PRIMARY KEY, name VARCHAR(64) NOT NULL);')"
check "NOT NULL inside CREATE TABLE is not an alter" "0" "${status}"

status="$(run_on 'REVOKE ALL ON SCHEMA core FROM PUBLIC;')"
check "revoking from PUBLIC is hardening, not a break" "0" "${status}"

# -- what it refuses -------------------------------------------------------------------------------

status="$(run_on 'ALTER TABLE core.thing DROP COLUMN note;')"
check "dropping a column is refused" "1" "${status}"

status="$(run_on 'ALTER TABLE core.thing ADD COLUMN note VARCHAR(64) NOT NULL;')"
check "NOT NULL without a default is refused" "1" "${status}"

status="$(run_on 'ALTER TABLE core.thing ALTER COLUMN note SET NOT NULL;')"
check "tightening a column to NOT NULL is refused" "1" "${status}"

status="$(run_on 'ALTER TABLE core.thing ALTER COLUMN note TYPE VARCHAR(8);')"
check "narrowing a type is refused" "1" "${status}"

status="$(run_on 'ALTER TABLE core.thing RENAME COLUMN note TO remark;')"
check "renaming a column is refused" "1" "${status}"

status="$(run_on 'ALTER TABLE core.thing ADD CONSTRAINT ck_note CHECK (note <> '"'"''"'"');')"
check "adding a CHECK to an existing table is refused" "1" "${status}"

status="$(run_on 'REVOKE UPDATE ON TABLE core.thing FROM ramals_core_runtime;')"
check "revoking from an application role is refused" "1" "${status}"

status="$(run_on 'DROP TABLE core.thing;')"
check "dropping a table is refused" "1" "${status}"

# -- what it must not cry wolf about ---------------------------------------------------------------

status="$(run_on '-- This migration deliberately does not DROP COLUMN note; see V018.
ALTER TABLE core.thing ADD COLUMN note VARCHAR(64);')"
check "a comment describing a drop is not a drop" "0" "${status}"

status="$(run_on 'CREATE OR REPLACE FUNCTION core.guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '"'"'you cannot drop column note on this table'"'"';
END;
$$;')"
check "a trigger body mentioning a drop is prose" "0" "${status}"

status="$(run_on 'INSERT INTO core.thing (note) VALUES ('"'"'ALTER TABLE x DROP COLUMN y'"'"');')"
check "a string literal containing DDL is not DDL" "0" "${status}"

# -- the declaration -------------------------------------------------------------------------------

status="$(run_on '-- expand-contract: CONTRACT of V018, nothing deployable reads note any more
ALTER TABLE core.thing DROP COLUMN note;')"
check "a declared contract naming its expand is allowed" "0" "${status}"

status="$(run_on '-- expand-contract: it is fine, trust me
ALTER TABLE core.thing DROP COLUMN note;')"
check "a declaration naming no version is refused" "1" "${status}"

# A declaration is per-statement on purpose. One at the top of a file would licence everything below
# it, including statements added later by somebody who never read it.
status="$(run_on '-- expand-contract: CONTRACT of V018, this file is the contract phase
ALTER TABLE core.thing ADD COLUMN other VARCHAR(8);
ALTER TABLE core.thing DROP COLUMN note;')"
check "a declaration does not licence the whole file" "1" "${status}"

# -- the checker must not pass by finding nothing ---------------------------------------------------

rm -rf "${WORK}/empty"
mkdir -p "${WORK}/empty"
"${PYTHON}" "${CHECK}" "${WORK}/empty" > "${WORK}/out" 2>&1
check "an empty directory refuses to report success" "2" "$?"

"${PYTHON}" "${CHECK}" "${WORK}/does-not-exist" > "${WORK}/out" 2>&1
check "a missing directory refuses to report success" "2" "$?"

# -- the real migrations --------------------------------------------------------------------------

"${PYTHON}" "${CHECK}" > "${WORK}/out" 2>&1
check "the repository's own migrations pass" "0" "$?"

# -- result ----------------------------------------------------------------------------------------

echo
if [ "${FAILURES}" -eq 0 ]; then
  echo "All migration-compatibility checks passed."
else
  echo "${FAILURES} migration-compatibility check(s) failed."
  exit 1
fi
