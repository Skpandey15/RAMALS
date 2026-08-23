#!/usr/bin/env python3
"""Refuses a migration the previously released application cannot run against.

The deploy controller rolls back the **image**, never the database. `deploy-controller.sh` returns
the previous known-good digest and re-deploys it; nothing reverts schema. So after a rollback, image
N-1 is running against schema N, and any migration that removed or narrowed what N-1 depends on has
turned a bad release into an outage -- at the exact moment somebody is trying to recover from one.

Expand/contract is the discipline that keeps rollback possible. A change that would break the
previous image is split across two releases:

* **expand** -- add the new column as nullable, backfill, write both. Old code ignores it.
* **contract** -- remove the old column, in a later release, once no image that reads it is
  deployable any more.

This script enforces the half that is easy to get wrong: it fails on statements that break the
previous image unless the line above them declares the contract, naming the release whose expand
made it safe. A declaration is not a bypass -- it is the sentence somebody has to write and a
reviewer has to disagree with.

The unit of judgement is a **statement**, not a line. The first version of this checker matched each
rule against one physical line, which meant ordinary SQL formatting walked through eight of its nine
rules -- ``ALTER TABLE core.foo`` on one line and ``DROP COLUMN value`` on the next was accepted, and
the identical statement on a single line was refused. It cried wolf in the same way: a
``REVOKE ... FROM PUBLIC`` split across two lines lost its exemption and was reported as a rollback
break. Both are the same defect, so both are fixed by the same change.

The pipeline, in order:

1. **neutralise** comments, string literals and dollar-quoted bodies, keeping line positions, so a
   comment describing a ``DROP COLUMN`` and a trigger body raising ``'cannot drop column'`` are not
   mistaken for one;
2. **segment** what remains into complete statements, each carrying the line it began on;
3. **split** an ``ALTER TABLE`` into its comma-separated actions, because a rule about one action
   must not be answered by a different action in the same statement -- a ``DEFAULT`` on the second
   ``ADD COLUMN`` would otherwise excuse a ``NOT NULL`` on the first;
4. **apply** the rules to whole actions, so a rule's tokens are matched wherever the author put the
   newlines;
5. **report** against the statement's starting line, which is also where its declaration is looked
   for.

Usage:
    python scripts/ci/check-migration-compatibility.py [migration-directory]
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

DEFAULT_DIRECTORY = Path("learning-platform/src/main/resources/db/migration")

DECLARATION = re.compile(r"--\s*expand-contract\s*:\s*(?P<reason>\S.*)$", re.IGNORECASE)

# A declaration must say which release shipped the expand. "contract phase" on its own is a label;
# a version is a claim somebody can check against the migration history.
DECLARATION_NAMES_A_VERSION = re.compile(r"\bV\d{3}\b")

CREATE_TABLE = re.compile(
    r"\bCREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w.]+)", re.IGNORECASE
)
ALTER_TABLE = re.compile(r"^\s*ALTER\s+TABLE\s+(?:ONLY\s+)?([\w.]+)", re.IGNORECASE)
ADD_COLUMN_NAME = re.compile(
    r"\bADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)", re.IGNORECASE
)
DROP_DEFAULT_COLUMN = re.compile(
    r"\bALTER\s+COLUMN\s+(\w+)\s+DROP\s+DEFAULT\b", re.IGNORECASE
)
INDEX_TARGET = re.compile(r"\bON\s+(?:ONLY\s+)?([\w.]+)", re.IGNORECASE)


@dataclass(frozen=True)
class Rule:
    """One way a migration can break the image that is still deployable."""

    name: str
    pattern: re.Pattern[str]
    breaks: str


RULES: tuple[Rule, ...] = (
    Rule(
        "drop-column",
        re.compile(r"\bDROP\s+COLUMN\b", re.IGNORECASE),
        "the previous image still selects it",
    ),
    Rule(
        "drop-table",
        re.compile(r"\bDROP\s+(TABLE|VIEW|MATERIALIZED\s+VIEW)\b", re.IGNORECASE),
        "the previous image still reads it",
    ),
    Rule(
        "rename",
        re.compile(r"\bRENAME\s+(COLUMN\b|TO\b)", re.IGNORECASE),
        "a rename is a drop and an add at once, so the old name is gone immediately",
    ),
    Rule(
        "set-not-null",
        re.compile(r"\bALTER\s+COLUMN\b.*\bSET\s+NOT\s+NULL\b", re.IGNORECASE),
        "the previous image may still insert rows without it",
    ),
    Rule(
        "alter-type",
        re.compile(r"\bALTER\s+COLUMN\b.*\b(TYPE|USING)\b", re.IGNORECASE),
        "a narrowed type rejects or truncates what the previous image writes",
    ),
    Rule(
        "add-not-null-without-default",
        # Only ALTER TABLE ... ADD COLUMN. A NOT NULL column inside CREATE TABLE is not a
        # compatibility problem: no previously released image inserts into a table that did not
        # exist. Anchored on ADD COLUMN so the two cases cannot be confused.
        re.compile(r"\bADD\s+COLUMN\b(?!.*\bDEFAULT\b).*\bNOT\s+NULL\b", re.IGNORECASE),
        "the previous image inserts without that column and the insert fails",
    ),
    Rule(
        "add-check-or-unique",
        re.compile(r"\bADD\s+CONSTRAINT\b.*\b(CHECK|UNIQUE|FOREIGN\s+KEY)\b", re.IGNORECASE),
        "the previous image can write rows the new constraint refuses",
    ),
    Rule(
        "add-primary-key",
        # A primary key is a uniqueness guarantee and a NOT NULL at once, so it can refuse the
        # previous image's writes on either count. Matches the bare form as well as the named one.
        re.compile(r"\bADD\s+(?:CONSTRAINT\s+\w+\s+)?PRIMARY\s+KEY\b", re.IGNORECASE),
        "the previous image can write a duplicate or a null the key refuses",
    ),
    Rule(
        "add-exclude",
        # Anchored on ADD CONSTRAINT ... EXCLUDE USING rather than the bare word: "EXCLUDED" is the
        # pseudo-table in ON CONFLICT DO UPDATE and appears in ordinary upserts, four times in V011
        # alone. A rule that flagged those would be switched off within a week.
        re.compile(r"\bADD\s+(?:CONSTRAINT\s+\w+\s+)?EXCLUDE\s+USING\b", re.IGNORECASE),
        "the previous image can write rows the exclusion constraint refuses",
    ),
    Rule(
        "create-unique-index",
        # The same guarantee as ADD CONSTRAINT ... UNIQUE, written a different way. Non-unique
        # indexes constrain nothing and are not matched.
        re.compile(r"\bCREATE\s+UNIQUE\s+INDEX\b", re.IGNORECASE),
        "the previous image can write a duplicate the index refuses",
    ),
    Rule(
        "drop-default",
        # Wider than "N-1 crashes", and deliberately so. If the column is NOT NULL the previous
        # image's insert fails outright; if it is nullable the insert succeeds and silently stores
        # NULL where the default used to be. Which of the two applies depends on a nullability that
        # may have been set several migrations ago, and this checker reads one file -- so it refuses
        # both and lets a declaration release it.
        re.compile(r"\bDROP\s+DEFAULT\b", re.IGNORECASE),
        "the previous image relies on that default, and now writes null instead",
    ),
    Rule(
        "revoke",
        # Not FROM PUBLIC. Revoking from PUBLIC is hardening: PUBLIC is not an identity any image
        # authenticates as, so no deployed application can lose a privilege it was using. Revoking
        # from a named role is the opposite -- that is precisely what an image connects as.
        re.compile(r"^\s*REVOKE\b(?!.*\bFROM\s+PUBLIC\b)", re.IGNORECASE),
        "the previous image connects as that role and loses a privilege it still uses",
    ),
)

# Findings that predate this check.
#
# These migrations were written and applied before the rule existed, and Flyway records a checksum
# for each, so they cannot be edited to comply -- changing an applied migration fails validation at
# startup. Recording them here rather than tuning the rules until history passes: a rule weakened to
# accommodate the past stops catching the case in the future.
#
# Keyed by file and rule rather than by line, which is safe for the same reason the entries exist:
# an applied migration is immutable, so one of these files cannot acquire a new violation of the
# same rule without failing Flyway first.
ACCEPTED_BEFORE_THIS_CHECK: dict[tuple[str, str], str] = {
    ("V002__roles_and_grants_foundation.sql", "revoke"): (
        "establishes what ramals_core_runtime may do; there was no released image to break"
    ),
    ("V020__approval_audit_outcomes.sql", "add-check-or-unique"): (
        "constrains admin_activity outcomes; shipped and applied before this rule existed"
    ),
    # The four below were invisible until this checker began matching statements instead of lines.
    # Their ADD CONSTRAINT and its CHECK sit on different lines, so the rule never saw them together
    # -- the bypass was not hypothetical in this repository, it was already load-bearing.
    #
    # Three of them are safe in fact, for a reason this checker deliberately does not try to prove.
    # Establishing it needs constraint satisfiability against a column's default, and a rule clever
    # enough to clear these would be a rule nobody could predict the behaviour of. They are recorded
    # as accepted rather than reasoned away.
    ("V009__evidence_confidence.sql", "add-check-or-unique"): (
        "bounds the confidence columns V009 itself adds; they are nullable and the checks admit "
        "NULL, which is what the previous image writes"
    ),
    ("V016__learning_domain_type.sql", "add-check-or-unique"): (
        "constrains domain_type, which V016 itself adds with a default the check permits"
    ),
    ("V017__assessment_content_trust_state.sql", "add-check-or-unique"): (
        "constrains the trust-state columns V017 itself adds, with defaults the checks permit"
    ),
    ("V022__ai_execution_commissioning.sql", "add-check-or-unique"): (
        "bounds token and cost columns V021 created, so unlike the three above this one was a real "
        "rollback hazard when it shipped: the previous image could have written a negative value "
        "the new check refuses. Applied and immutable, so it is recorded rather than corrected"
    ),
}


@dataclass(frozen=True)
class Statement:
    """One complete SQL statement, neutralised, with the line it began on."""

    text: str
    start_line: int


@dataclass(frozen=True)
class Finding:
    file: str
    line: int
    rule: Rule
    statement: str


# A named CHECK whose body is a membership test, e.g.
#   CONSTRAINT ck_evidence_type CHECK (evidence_type IN ('DIAGNOSTIC', 'QUIZ'))
# Read from the raw migration text rather than the neutralised copy, because neutralising blanks
# string literals and the values are the whole point here.
NAMED_IN_CHECK = re.compile(
    r"CONSTRAINT\s+(?P<name>\w+)\s+CHECK\s*\(\s*(?P<column>[\w.]+)\s+IN\s*\((?P<values>[^)]*)\)",
    re.IGNORECASE | re.DOTALL,
)
QUOTED_VALUE = re.compile(r"'([^']*)'")


def membership_checks(text: str) -> dict[str, tuple[str, frozenset[str]]]:
    """Every named ``CHECK (col IN (...))`` in one migration, by constraint name."""
    found: dict[str, tuple[str, frozenset[str]]] = {}
    for match in NAMED_IN_CHECK.finditer(text):
        values = frozenset(QUOTED_VALUE.findall(match.group("values")))
        if values:
            found[match.group("name").lower()] = (match.group("column").lower(), values)
    return found


def widens_a_membership_check(
    action: str,
    here: dict[str, tuple[str, frozenset[str]]],
    known: dict[str, tuple[str, frozenset[str]]],
) -> bool:
    """Whether this ADD CONSTRAINT only *widens* an existing membership check.

    A rollback restores the previous image, and that image writes only values the old constraint
    already allowed. If the new allowed set is a superset of the old one, every such row still
    satisfies the new constraint, so the rollback hazard this rule exists to catch cannot occur.

    Proved by comparing the two value sets, not asserted in a comment. Removing or renaming a value
    is a narrowing and stays a finding, which is the case that actually breaks a rollback.
    """
    named = ADDED_CONSTRAINT_NAME.search(action)
    if not named:
        return False
    name = named.group(1).lower()
    previous = known.get(name)
    current = here.get(name)
    if previous is None or current is None:
        return False
    previous_column, previous_values = previous
    current_column, current_values = current
    return previous_column == current_column and previous_values <= current_values


ADDED_CONSTRAINT_NAME = re.compile(r"\bADD\s+CONSTRAINT\s+(\w+)", re.IGNORECASE)


def neutralise(text: str) -> list[str]:
    """Blanks out what must not be pattern-matched, keeping line numbers intact.

    Three things are removed:

    * **line comments**, because a comment describing a DROP COLUMN is not one. They are read for
      declarations before this point.
    * **string literals**, because ``RAISE EXCEPTION 'cannot drop column'`` is prose.
    * **dollar-quoted bodies**, for the same reason: the migrations use them for trigger functions
      whose text mentions the operations they refuse.

    Blanked rather than deleted so a reported line number still points at the real line. A DDL
    statement hidden inside a function body would evade this, which is accepted: creating a function
    applies no schema change, and something would still have to call it.
    """
    output: list[str] = []
    in_dollar_quote = False

    for line in text.split("\n"):
        # Dollar-quoted body boundaries. Each "$$" toggles, so a body opened and closed on one line
        # behaves exactly like one spanning many and neither case needs its own branch.
        segments = line.split("$$")
        outside: list[str] = []
        for index, segment in enumerate(segments):
            if not in_dollar_quote:
                outside.append(segment)
            if index < len(segments) - 1:
                in_dollar_quote = not in_dollar_quote
        working = " ".join(outside)

        working = re.sub(r"'[^']*'", "''", working)
        working = re.sub(r"--.*$", "", working)
        output.append(working)

    return output


def segment(lines: list[str]) -> list[Statement]:
    """Groups neutralised lines into complete statements.

    Split on the terminator, which is reliable here precisely because it runs after neutralisation:
    a semicolon inside a comment, a string or a function body is already gone. The statement's start
    line is the first line carrying any of its text, which is where a reader looks and where its
    declaration sits.
    """
    statements: list[Statement] = []
    collected: list[str] = []
    start_line: int | None = None

    for number, line in enumerate(lines, start=1):
        remaining = line
        while ";" in remaining:
            head, remaining = remaining.split(";", 1)
            if head.strip() and start_line is None:
                start_line = number
            collected.append(head)
            body = " ".join(collected).strip()
            if body:
                statements.append(
                    Statement(text=re.sub(r"\s+", " ", body), start_line=start_line or number)
                )
            collected, start_line = [], None
        if remaining.strip():
            if start_line is None:
                start_line = number
            collected.append(remaining)

    # A trailing statement with no terminator is still a statement, and refusing to look at it would
    # make a missing semicolon a way past the gate.
    body = " ".join(collected).strip()
    if body:
        statements.append(
            Statement(text=re.sub(r"\s+", " ", body), start_line=start_line or len(lines))
        )
    return statements


def actions_of(statement: Statement) -> list[str]:
    """An ALTER TABLE's comma-separated actions, or the whole statement for anything else.

    Rules are applied per action so that one action cannot answer for another. Without this, the
    "added without a default" rule reads ``ADD COLUMN a NOT NULL, ADD COLUMN b DEFAULT 'x'`` as
    compliant: its lookahead finds b's default and clears a's violation.

    Commas inside parentheses are not separators -- ``CHECK (x IN ('a', 'b'))`` and
    ``FOREIGN KEY (a, b)`` are one action each.
    """
    if not ALTER_TABLE.match(statement.text):
        return [statement.text]

    actions: list[str] = []
    depth = 0
    current: list[str] = []
    for character in statement.text:
        if character == "(":
            depth += 1
        elif character == ")":
            depth = max(0, depth - 1)
        if character == "," and depth == 0:
            actions.append("".join(current))
            current = []
            continue
        current.append(character)
    actions.append("".join(current))
    return [action.strip() for action in actions if action.strip()]


def declarations_by_line(text: str) -> dict[int, str]:
    """Every ``-- expand-contract:`` declaration, by the line it appears on."""
    found: dict[int, str] = {}
    for number, line in enumerate(text.split("\n"), start=1):
        match = DECLARATION.search(line)
        if match:
            found[number] = match.group("reason").strip()
    return found


def declared_for(line_number: int, declarations: dict[int, str]) -> str | None:
    """A declaration on the statement's own line, or on the line above it.

    Deliberately not file-scoped. One declaration at the top of a file would licence every statement
    in it, including ones added later by someone who never read it. Anchored to the statement's
    start rather than to the line a rule happened to match, so wrapping a statement over several
    lines does not detach it from its declaration.
    """
    for candidate in (line_number, line_number - 1):
        if candidate in declarations:
            return declarations[candidate]
    return None


def created_here(statements: list[Statement]) -> set[str]:
    """Tables this migration creates. Nothing deployed writes to them yet."""
    created: set[str] = set()
    for statement in statements:
        for match in CREATE_TABLE.finditer(statement.text):
            created.add(match.group(1).lower())
    return created


def columns_added_here(statements: list[Statement]) -> set[tuple[str, str]]:
    """Columns this migration adds, as (table, column).

    Used by the DROP DEFAULT rule. Adding a column with a default to classify existing rows and then
    dropping the default so later writes are explicit is a complete expand: the column did not exist
    for the previous image to depend on. V017 does exactly this.
    """
    added: set[tuple[str, str]] = set()
    for statement in statements:
        table = ALTER_TABLE.match(statement.text)
        if not table:
            continue
        name = table.group(1).lower()
        for action in actions_of(statement):
            column = ADD_COLUMN_NAME.search(action)
            if column:
                added.add((name, column.group(1).lower()))
    return added


def exempt(
    rule: Rule,
    statement: Statement,
    action: str,
    created: set[str],
    added_columns: set[tuple[str, str]],
) -> bool:
    """Whether this migration created the thing the rule is protecting.

    No previously released image writes to a table that did not exist, or relies on the default of a
    column it has never heard of. This is the same reasoning that keeps a NOT NULL column inside
    CREATE TABLE from being a finding.
    """
    altered = ALTER_TABLE.match(statement.text)
    if altered and altered.group(1).lower() in created:
        return True

    if rule.name == "create-unique-index":
        target = INDEX_TARGET.search(statement.text)
        return bool(target and target.group(1).lower() in created)

    if rule.name == "drop-default" and altered:
        column = DROP_DEFAULT_COLUMN.search(action)
        if column:
            return (altered.group(1).lower(), column.group(1).lower()) in added_columns

    return False


def check_file(
    path: Path, known_checks: dict[str, tuple[str, frozenset[str]]] | None = None
) -> tuple[list[Finding], list[str]]:
    """Returns (findings, errors) for one migration."""
    text = path.read_text(encoding="utf-8")
    known_checks = known_checks or {}
    checks_here = membership_checks(text)
    declarations = declarations_by_line(text)
    statements = segment(neutralise(text))

    created = created_here(statements)
    added_columns = columns_added_here(statements)

    findings: list[Finding] = []
    errors: list[str] = []

    for statement in statements:
        for action in actions_of(statement):
            for rule in RULES:
                if not rule.pattern.search(action):
                    continue
                if (path.name, rule.name) in ACCEPTED_BEFORE_THIS_CHECK:
                    continue
                if exempt(rule, statement, action, created, added_columns):
                    continue
                if rule.name == "add-check-or-unique" and widens_a_membership_check(
                    action, checks_here, known_checks
                ):
                    continue

                declaration = declared_for(statement.start_line, declarations)
                if declaration is None:
                    findings.append(
                        Finding(path.name, statement.start_line, rule, action.strip())
                    )
                elif not DECLARATION_NAMES_A_VERSION.search(declaration):
                    errors.append(
                        f"{path.name}:{statement.start_line}: the expand-contract declaration must "
                        f"name the migration whose expand made this safe (e.g. 'CONTRACT of V018'), "
                        f"got: {declaration!r}"
                    )
    return findings, errors


def main(argv: list[str]) -> int:
    directory = Path(argv[1]) if len(argv) > 1 else DEFAULT_DIRECTORY
    if not directory.is_dir():
        print(f"no migration directory at {directory}", file=sys.stderr)
        return 2

    migrations = sorted(directory.glob("V*.sql"))
    if not migrations:
        # An empty run reporting success is how a check quietly stops checking -- a moved directory
        # or a renamed suffix would otherwise read as a clean bill of health.
        print(f"no migrations found in {directory}; refusing to report success", file=sys.stderr)
        return 2

    findings: list[Finding] = []
    errors: list[str] = []
    # Constraint definitions established by *earlier* migrations. A migration's own ADD is not its
    # own precedent, so this is merged only after that file has been checked.
    known_checks: dict[str, tuple[str, frozenset[str]]] = {}
    for path in migrations:
        file_findings, file_errors = check_file(path, known_checks)
        findings.extend(file_findings)
        errors.extend(file_errors)
        known_checks.update(membership_checks(path.read_text(encoding="utf-8")))

    if findings or errors:
        print("Migrations that the previously released image cannot run against:\n")
        for finding in findings:
            print(f"  {finding.file}:{finding.line}  [{finding.rule.name}]")
            print(f"      {finding.statement}")
            print(f"      breaks rollback: {finding.rule.breaks}")
            print(
                "      if this is the contract half of a change whose expand already shipped, say "
                "so on the line above:"
            )
            print("        -- expand-contract: CONTRACT of V0NN, <why the old column is unused>\n")
        for error in errors:
            print(f"  {error}\n")
        print(
            f"{len(findings) + len(errors)} problem(s) across {len(migrations)} migrations.\n"
            "A rollback restores the previous image and never the schema, so a migration that "
            "removes or narrows what that image depends on turns a bad release into an outage."
        )
        return 1

    print(
        f"Checked {len(migrations)} migrations: every one is safe for the previously released "
        "image to run against."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
