"""Validation of a diagnostic proposal.

The deterministic gate in Spring is the authority: it refuses anything that would harm a learner,
against the curriculum graph and recorded mastery, and no amount of prompting replaces it.

So why validate here at all? Because a proposal refused downstream has already cost a model call and
a learner's wait. Catching the recognisable mistakes at the boundary makes the repair loop from
M1-T06 useful — the agent gets told what was wrong and can try again inside the same request, rather
than returning something the platform will silently discard.

The two rules that matter are the ones the gate enforces:

* an objective must belong to the skill it is proposed for;
* sparse evidence must not be reported as a verdict.

Both are checked against the *minimized context the agent was given*, which is the only ground truth
available on this side. Where the context is silent, this defers rather than guesses, and the gate
decides.
"""

from __future__ import annotations

import json
import re
from typing import Any

VALID_DIFFICULTIES = frozenset({"FOUNDATIONAL", "INTERMEDIATE", "ADVANCED"})

MAX_RATIONALE_CHARS = 600

SPARSE_STATUSES = frozenset({"INSUFFICIENT_EVIDENCE", None})
"""States meaning the platform has not measured enough to say anything."""

# Language that reports a verdict about the learner. Checked in the rationale because that is where
# an inference gets justified, and a rationale is the part a reviewer reads.
_VERDICT_LANGUAGE = re.compile(
    r"\b("
    r"(?:has|have) (?:not )?master(?:ed|y)"
    r"|(?:is|are) (?:not )?(?:proficient|competent)"
    r"|(?:is|are) failing"
    r"|(?:has|have) failed"
    r"|clearly (?:does not|doesn't) understand"
    r"|does not know"
    r"|no understanding of"
    r")\b",
    re.IGNORECASE,
)


def validate(raw: str, context: dict[str, Any]) -> list[str]:
    """Returns validation error codes; an empty list means the proposal is worth sending on.

    Codes rather than prose, so the graph can branch on them and the evaluation harness can count
    them without parsing English.
    """
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError, TypeError:
        return ["SCHEMA_NOT_JSON"]

    if not isinstance(parsed, dict):
        return ["SCHEMA_NOT_OBJECT"]

    errors = _schema_errors(parsed)
    if errors:
        return errors

    return _policy_errors(parsed, context)


def _schema_errors(parsed: dict[str, Any]) -> list[str]:
    errors: list[str] = []

    if not isinstance(parsed.get("skillCode"), str) or not parsed["skillCode"].strip():
        errors.append("SCHEMA_MISSING_SKILL_CODE")

    if not isinstance(parsed.get("objectiveCode"), str) or not parsed["objectiveCode"].strip():
        errors.append("SCHEMA_MISSING_OBJECTIVE_CODE")

    if parsed.get("difficulty") not in VALID_DIFFICULTIES:
        errors.append("SCHEMA_BAD_DIFFICULTY")

    rationale = parsed.get("rationale")
    if not isinstance(rationale, str) or not rationale.strip():
        # A probe without a reason cannot be reviewed, and an unreviewable proposal is one nobody
        # can disagree with -- which is the opposite of what a proposing agent is for.
        errors.append("SCHEMA_MISSING_RATIONALE")
    elif len(rationale) > MAX_RATIONALE_CHARS:
        errors.append("SCHEMA_RATIONALE_TOO_LONG")

    allowed = {"skillCode", "objectiveCode", "difficulty", "rationale", "inferredStatus"}
    if set(parsed) - allowed:
        errors.append("SCHEMA_UNEXPECTED_FIELD")

    return errors


def _policy_errors(parsed: dict[str, Any], context: dict[str, Any]) -> list[str]:
    errors: list[str] = []

    if _objective_is_not_in_skill(parsed, context):
        errors.append("OBJECTIVE_NOT_IN_SKILL")

    if _infers_a_verdict_from_sparse_evidence(parsed, context):
        errors.append("INFERRED_VERDICT_FROM_INSUFFICIENT_EVIDENCE")

    return errors


def _objective_is_not_in_skill(parsed: dict[str, Any], context: dict[str, Any]) -> bool:
    """True when the proposed objective is not one the context offered for that skill.

    Defers when the context lists no objectives at all: the agent was not told what exists, so it
    cannot be faulted for the choice, and the gate has the curriculum to decide properly.
    """
    available = context.get("objectives")
    if not isinstance(available, dict) or not available:
        return False

    objectives_for_skill = available.get(parsed.get("skillCode"))
    if not isinstance(objectives_for_skill, list) or not objectives_for_skill:
        # The skill was not among those offered. Refusing here would duplicate the gate's
        # UNKNOWN_SKILL decision on partial information; let the authority answer it.
        return False

    return parsed.get("objectiveCode") not in objectives_for_skill


def _infers_a_verdict_from_sparse_evidence(parsed: dict[str, Any], context: dict[str, Any]) -> bool:
    """True when the proposal turns "not enough evidence" into a claim about the learner.

    Mirrors the gate exactly, including the part that is easy to get backwards: restating
    ``INSUFFICIENT_EVIDENCE`` is fine, because "we do not know yet" is the only honest inference
    available and the agent needs it to explain why it wants another probe.
    """
    coverage = context.get("coverage")
    recorded = None
    if isinstance(coverage, dict):
        recorded = coverage.get(parsed.get("skillCode"))

    if recorded not in SPARSE_STATUSES:
        return False

    inferred = parsed.get("inferredStatus")
    if inferred is not None and inferred != "INSUFFICIENT_EVIDENCE":
        return True

    # A rationale can assert a verdict the structured field carefully avoids, and the rationale is
    # what a human reviewer reads. Checking only the field would police the tidy half.
    rationale = parsed.get("rationale")
    return isinstance(rationale, str) and bool(_VERDICT_LANGUAGE.search(rationale))
