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

# -- every rule, in both formattings ---------------------------------------------------------------
#
# The suite used to assert each rule with the whole statement on one physical line, and the checker
# evaluated one physical line at a time -- so the tests agreed with the implementation's blind spot
# rather than testing the rule. Ordinary SQL formatting walked straight through eight of nine rules.
#
# Each rule is now proved three ways: the breaking form is refused, the safe expand is allowed, and
# the same breaking statement wrapped across lines is still refused. The third assertion is the one
# that would have caught it.

matrix() { # matrix <label> <breaking-one-line> <breaking-multiline> <safe-expand>
  local label="$1" breaking="$2" wrapped="$3" safe="$4"
  check "${label}: breaking form is refused"            "1" "$(run_on "${breaking}")"
  check "${label}: safe expand is allowed"              "0" "$(run_on "${safe}")"
  check "${label}: still refused when wrapped in lines" "1" "$(run_on "${wrapped}")"
}

matrix "ADD COLUMN NOT NULL" \
  'ALTER TABLE core.foo ADD COLUMN value VARCHAR(64) NOT NULL;' \
  'ALTER TABLE core.foo
  ADD COLUMN value VARCHAR(64)
  NOT NULL;' \
  'ALTER TABLE core.foo ADD COLUMN value VARCHAR(64);'

matrix "ALTER COLUMN SET NOT NULL" \
  'ALTER TABLE core.foo ALTER COLUMN value SET NOT NULL;' \
  'ALTER TABLE core.foo
  ALTER COLUMN value
  SET NOT NULL;' \
  'ALTER TABLE core.foo ADD COLUMN value VARCHAR(64);'

matrix "ALTER COLUMN TYPE" \
  'ALTER TABLE core.foo ALTER COLUMN value TYPE VARCHAR(8);' \
  'ALTER TABLE core.foo
  ALTER COLUMN value
  TYPE VARCHAR(8);' \
  'ALTER TABLE core.foo ADD COLUMN value VARCHAR(64);'

matrix "ADD CONSTRAINT CHECK" \
  'ALTER TABLE core.foo ADD CONSTRAINT ck_foo CHECK (value IS NOT NULL);' \
  'ALTER TABLE core.foo
  ADD CONSTRAINT ck_foo
  CHECK (value IS NOT NULL);' \
  'ALTER TABLE core.foo ADD COLUMN value VARCHAR(64);'

matrix "ADD CONSTRAINT UNIQUE" \
  'ALTER TABLE core.foo ADD CONSTRAINT uq_foo UNIQUE (value);' \
  'ALTER TABLE core.foo
  ADD CONSTRAINT uq_foo
  UNIQUE (value);' \
  'ALTER TABLE core.foo ADD COLUMN value VARCHAR(64);'

matrix "ADD CONSTRAINT FOREIGN KEY" \
  'ALTER TABLE core.foo ADD CONSTRAINT fk_foo FOREIGN KEY (bar_id) REFERENCES core.bar(id);' \
  'ALTER TABLE core.foo
  ADD CONSTRAINT fk_foo
  FOREIGN KEY (bar_id)
  REFERENCES core.bar(id);' \
  'ALTER TABLE core.foo ADD COLUMN bar_id UUID;'

matrix "DROP COLUMN" \
  'ALTER TABLE core.foo DROP COLUMN value;' \
  'ALTER TABLE core.foo
  DROP
  COLUMN value;' \
  'ALTER TABLE core.foo ADD COLUMN value VARCHAR(64);'

matrix "RENAME COLUMN" \
  'ALTER TABLE core.foo RENAME COLUMN value TO remark;' \
  'ALTER TABLE core.foo
  RENAME
  COLUMN value TO remark;' \
  'ALTER TABLE core.foo ADD COLUMN remark VARCHAR(64);'

matrix "REVOKE from a role" \
  'REVOKE UPDATE ON TABLE core.foo FROM ramals_core_runtime;' \
  'REVOKE UPDATE
  ON TABLE core.foo
  FROM ramals_core_runtime;' \
  'GRANT SELECT ON TABLE core.foo TO ramals_core_runtime;'

# -- rules added by this hardening slice ------------------------------------------------------------
#
# Each one can make image N-1 fail against schema N, and each is exempt when the thing it constrains
# was created by the same migration -- no previously released image writes to a table that did not
# exist, or to a column it has never heard of.

matrix "CREATE UNIQUE INDEX on an existing table" \
  'CREATE UNIQUE INDEX uq_foo ON core.foo(value);' \
  'CREATE UNIQUE INDEX uq_foo
  ON core.foo(value);' \
  'CREATE INDEX ix_foo ON core.foo(value);'

matrix "ADD PRIMARY KEY" \
  'ALTER TABLE core.foo ADD CONSTRAINT pk_foo PRIMARY KEY (id);' \
  'ALTER TABLE core.foo
  ADD CONSTRAINT pk_foo
  PRIMARY KEY (id);' \
  'ALTER TABLE core.foo ADD COLUMN id UUID;'

matrix "ADD CONSTRAINT EXCLUDE" \
  'ALTER TABLE core.foo ADD CONSTRAINT ex_foo EXCLUDE USING gist (value WITH =);' \
  'ALTER TABLE core.foo
  ADD CONSTRAINT ex_foo
  EXCLUDE USING gist (value WITH =);' \
  'ALTER TABLE core.foo ADD COLUMN value VARCHAR(64);'

matrix "DROP DEFAULT on a pre-existing column" \
  'ALTER TABLE core.foo ALTER COLUMN value DROP DEFAULT;' \
  'ALTER TABLE core.foo
  ALTER COLUMN value
  DROP DEFAULT;' \
  'ALTER TABLE core.foo ALTER COLUMN value SET DEFAULT 1;'

# A unique index over a table this migration creates constrains nothing any deployed image writes.
status="$(run_on 'CREATE TABLE core.fresh (id UUID PRIMARY KEY, value VARCHAR(64));
CREATE UNIQUE INDEX uq_fresh ON core.fresh(value);')"
check "a unique index on a table created here is allowed" "0" "${status}"

# The V017 idiom: add a column with a default to classify existing rows, then drop the default so
# everything written afterwards is explicit. The column did not exist for image N-1 to rely on.
status="$(run_on 'ALTER TABLE core.foo ADD COLUMN value VARCHAR(64) DEFAULT '"'"'x'"'"';
ALTER TABLE core.foo ALTER COLUMN value DROP DEFAULT;')"
check "dropping the default of a column added here is allowed" "0" "${status}"

# -- statement scope must not become file scope -----------------------------------------------------
#
# One clause's DEFAULT must not excuse another clause's NOT NULL. Matching the whole statement in one
# piece would let it: the "no DEFAULT" lookahead would find the second clause's default and clear the
# first clause's violation.
status="$(run_on 'ALTER TABLE core.foo
  ADD COLUMN needs_default VARCHAR(64) NOT NULL,
  ADD COLUMN has_default VARCHAR(64) DEFAULT '"'"'x'"'"';')"
check "one clause's default does not excuse another's NOT NULL" "1" "${status}"

# The mirror of the bypass: line-scoped matching also cried wolf. REVOKE ... FROM PUBLIC is
# hardening, and wrapping it must not turn it into a rollback break.
status="$(run_on 'REVOKE ALL ON SCHEMA core
  FROM PUBLIC;')"
check "a wrapped REVOKE FROM PUBLIC is still allowed" "0" "${status}"

# A declaration attaches to its statement, not to the line the rule happened to match on. With
# statements spanning lines, the declaration sits above the statement and must still be found.
status="$(run_on '-- expand-contract: CONTRACT of V018, nothing deployable reads note any more
ALTER TABLE core.thing
  DROP COLUMN note;')"
check "a declaration above a multiline statement is honoured" "0" "${status}"

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
