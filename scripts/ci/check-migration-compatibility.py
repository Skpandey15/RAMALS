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
}


@dataclass(frozen=True)
class Finding:
    file: str
    line: int
    rule: Rule
    statement: str


def strip_uncheckable(text: str) -> list[str]:
    """Blanks out what must not be pattern-matched, keeping line numbers intact.

    Three things are removed:

    * **line comments**, because a comment describing a DROP COLUMN is not one. They are handled by
      the caller before this point when looking for declarations.
    * **string literals**, because ``RAISE EXCEPTION 'cannot drop column'`` is prose.
    * **dollar-quoted bodies**, for the same reason: the migrations use them for trigger functions
      whose text mentions the operations they refuse.

    Blanked rather than deleted so a reported line number still points at the real line. A DDL
    statement hidden inside a function body would evade this, which is accepted: creating a function
    applies no schema change, and something would still have to call it.
    """
    lines = text.split("\n")
    output: list[str] = []
    in_dollar_quote = False

    for line in lines:
        working = line

        # Dollar-quoted body boundaries. Each "$$" toggles, so a body opened and closed on one line
        # behaves exactly like one spanning many and neither case needs its own branch.
        segments = working.split("$$")
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
    in it, including ones added later by someone who never read it.
    """
    for candidate in (line_number, line_number - 1):
        if candidate in declarations:
            return declarations[candidate]
    return None


def check_file(path: Path) -> tuple[list[Finding], list[str]]:
    """Returns (findings, errors) for one migration."""
    text = path.read_text(encoding="utf-8")
    declarations = declarations_by_line(text)
    checkable = strip_uncheckable(text)

    findings: list[Finding] = []
    errors: list[str] = []

    for number, line in enumerate(checkable, start=1):
        if not line.strip():
            continue
        for rule in RULES:
            if not rule.pattern.search(line):
                continue
            if (path.name, rule.name) in ACCEPTED_BEFORE_THIS_CHECK:
                continue
            declaration = declared_for(number, declarations)
            if declaration is None:
                findings.append(Finding(path.name, number, rule, line.strip()))
            elif not DECLARATION_NAMES_A_VERSION.search(declaration):
                errors.append(
                    f"{path.name}:{number}: the expand-contract declaration must name the migration "
                    f"whose expand made this safe (e.g. 'CONTRACT of V018'), got: {declaration!r}"
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
    for path in migrations:
        file_findings, file_errors = check_file(path)
        findings.extend(file_findings)
        errors.extend(file_errors)

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
