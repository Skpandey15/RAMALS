"""Validation of a tutor's output: schema first, then semantics.

Schema validity is a hard gate at 100% (Doc 07 §2), and it is the easy half — the shape either
parses or it does not.

The semantic half is the one worth thinking about. A tutor that says "you struggled with this last
week" when it was told nothing about last week has invented a fact about a person. It reads as
attentive and personalized, which is exactly why it is dangerous: nobody reviewing the output would
flag it, and the learner has no way to know it was fabricated. Doc 07 §3 lists *unsupported
learner-state claims* as a primary Tutor measure for this reason.

Detection here is conservative and deliberately shallow: it looks for assertions about learner
history and state that the minimized context could not support. It will not catch every fabrication
a model can produce, and it is not the only control — the proposal is non-authoritative, and
nothing downstream acts on its prose. What it does catch is the common, plausible-sounding case,
and it catches it before a learner reads it.
"""

from __future__ import annotations

import json
import re
from typing import Any

from ramals_ai.tutor.minimizer import MinimizedContext

VALID_RESPONSE_TYPES = frozenset({"EXPLAIN", "EXPLAIN_WITH_ANALOGY", "HINT"})

MAX_EXPLANATION_CHARS = 4000
MAX_CHECKS = 5


# Claims about learner history. The minimized context contains a current mastery status and score
# and nothing temporal, so any reference to a previous occasion is unsupported by construction.
_HISTORY_CLAIMS = re.compile(
    r"\b("
    r"last (?:time|week|session|month)"
    r"|previous(?:ly)? (?:attempt|session|answer|score|time)"
    r"|previously[, ]+you"
    r"|earlier (?:you|this week|attempt)"
    r"|you (?:have )?(?:already|previously) (?:answered|attempted|studied|completed|failed|passed)"
    r"|when you (?:last|first)"
    r"|since (?:your|the) last"
    r"|your (?:progress|history|record) (?:shows|indicates|suggests)"
    r"|you (?:usually|often|always|never|tend to)"
    r")\b",
    re.IGNORECASE,
)

# Claims about identity or enrolment, none of which the tutor is told.
_IDENTITY_CLAIMS = re.compile(
    r"\b("
    r"your (?:name|email|class|grade|school|college|university|teacher|instructor|cohort)"
    r"|other (?:learners|students) (?:who|like you)"
    r"|students in your"
    r")\b",
    re.IGNORECASE,
)


def validate(raw: str, context: MinimizedContext) -> list[str]:
    """Returns validation error codes; an empty list means the output is usable.

    Codes rather than prose so the graph can branch on them and the evaluation harness can count
    them without parsing English.
    """
    errors: list[str] = []

    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError, TypeError:
        return ["SCHEMA_NOT_JSON"]

    if not isinstance(parsed, dict):
        return ["SCHEMA_NOT_OBJECT"]

    errors.extend(_schema_errors(parsed))
    if errors:
        # Semantic checks on a malformed object would report noise about fields that may not be
        # what they appear to be.
        return errors

    errors.extend(_semantic_errors(parsed, context))
    return errors


def _schema_errors(parsed: dict[str, Any]) -> list[str]:
    errors: list[str] = []

    response_type = parsed.get("responseType")
    if response_type not in VALID_RESPONSE_TYPES:
        errors.append("SCHEMA_BAD_RESPONSE_TYPE")

    explanation = parsed.get("explanation")
    if not isinstance(explanation, str) or not explanation.strip():
        errors.append("SCHEMA_MISSING_EXPLANATION")
    elif len(explanation) > MAX_EXPLANATION_CHARS:
        errors.append("SCHEMA_EXPLANATION_TOO_LONG")

    checks = parsed.get("checksForUnderstanding")
    if not isinstance(checks, list) or not checks:
        errors.append("SCHEMA_MISSING_CHECKS")
    elif len(checks) > MAX_CHECKS:
        errors.append("SCHEMA_TOO_MANY_CHECKS")
    elif not all(isinstance(check, str) and check.strip() for check in checks):
        errors.append("SCHEMA_BAD_CHECK")

    unexpected = set(parsed) - {"responseType", "explanation", "checksForUnderstanding"}
    if unexpected:
        # A model that invents fields may be inventing content to put in them, and an unexpected
        # field is the one place a fabricated verdict could hide from every other check here.
        errors.append("SCHEMA_UNEXPECTED_FIELD")

    return errors


def _semantic_errors(parsed: dict[str, Any], context: MinimizedContext) -> list[str]:
    errors: list[str] = []
    prose = " ".join([parsed.get("explanation", ""), *parsed.get("checksForUnderstanding", [])])

    if _HISTORY_CLAIMS.search(prose):
        errors.append("UNSUPPORTED_LEARNER_HISTORY_CLAIM")

    if _IDENTITY_CLAIMS.search(prose):
        errors.append("UNSUPPORTED_LEARNER_IDENTITY_CLAIM")

    if _claims_a_mastery_verdict(prose, context):
        errors.append("UNSUPPORTED_MASTERY_CLAIM")

    return errors


def _claims_a_mastery_verdict(prose: str, context: MinimizedContext) -> bool:
    """True when the output announces a mastery outcome.

    The tutor may *reference* the status it was given. What it may not do is announce one —
    "you have now mastered this" is a progression decision, and progression decisions are made by a
    deterministic engine in Spring from recorded evidence. A learner told they have mastered
    something by a tutor has been told something the platform has not decided.
    """
    announces = re.search(
        r"\b(you have (?:now )?mastered|you are now (?:proficient|mastered)"
        r"|marking (?:you|this) as (?:mastered|complete)"
        r"|(?:i am|i'm) (?:updating|recording) your (?:mastery|progress|score))\b",
        prose,
        re.IGNORECASE,
    )
    if announces is None:
        return False
    # Referring to a status that was actually supplied is legitimate; announcing a new one is not.
    return context.get("masteryStatus") != "MASTERED" or "now" in announces.group(0).lower()
