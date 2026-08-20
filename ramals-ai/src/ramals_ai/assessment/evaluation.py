"""Validation of a formative evaluation (M1-ADR-010).

FORMATIVE_ONLY is enforced in four independent places, and this module is the weakest of them. That
ordering is worth stating plainly, because it is easy to read a validator as *the* control:

1. The ``ramals_ai_runtime`` database role holds no privilege on ``ledger`` (V015). The AI plane
   cannot write evidence because it has no credential that could.
2. The AI plane holds no database connection at all (M1-T03), so there is nothing to authorize.
3. Spring never treats a proposal as a score; the deterministic engines read ``ledger.evidence``,
   which only Spring writes.
4. This module, which refuses output *shaped* like a score before it is ever returned.

The first three make an authoritative AI score impossible. The fourth exists because possible and
plausible are different problems. An evaluation carrying ``{"score": 0.82}`` cannot become a mark
today — but it is exactly the field a future well-meaning change would notice, plumb through, and
display next to a real one. Refusing to emit it keeps that shape from existing to be picked up.

The rules are about *shape and claim*, not about whether the content is good. Quality is the
reviewer's judgement; this only ensures what arrives is formative material rather than a verdict.
"""

from __future__ import annotations

import json
import re
from typing import Any

MAX_TEXT_CHARS = 800
MAX_MISCONCEPTIONS = 8

_EVALUATION_FIELDS = frozenset({"skillCode", "indicators", "misconceptions", "suggestedProbe"})
_INDICATOR_BANDS = ("strong", "partial", "weak")

_SCORING_FIELD_NAMES = frozenset(
    {
        "score",
        "scores",
        "grade",
        "grades",
        "mark",
        "marks",
        "percentage",
        "percent",
        "points",
        "result",
        "verdict",
        "passed",
        "pass",
        "fail",
        "failed",
        "correct",
        "incorrect",
        "mastery",
        "masteryscore",
        "masterystatus",
        "masterylevel",
        "confidence",
        "evidenceconfidence",
        "band",
        "rating",
        "rank",
        "level",
    }
)
"""Field names that mean a judgement rather than a description.

Matched on the name alone, at any depth, whatever the value. A field called ``score`` holding the
string "not applicable" is still the field a later change reads as a score.
"""

# Claims about a specific learner. The prompt is never given one, so any such sentence is invented,
# and an invented observation is indistinguishable to a reader from a real one.
_LEARNER_CLAIM = re.compile(
    r"\b("
    r"the learner"
    r"|this learner"
    r"|the student"
    r"|they (?:scored|answered|got|failed|passed)"
    r"|you (?:scored|answered|got|failed|passed)"
    r"|(?:has|have) (?:not )?mastered"
    r"|(?:is|are) (?:not )?(?:proficient|competent)"
    r")\b",
    re.IGNORECASE,
)

# A verdict expressed in prose rather than in a field.
#
# M1-ADR-010 and the prompt both say "in any field or in prose", but only the field half was
# enforced: _SCORING_FIELD_NAMES catches a key called "score", _LEARNER_CLAIM catches a sentence
# about a learner, and neither catches "Assign a grade of B+ for this competency". Found by the
# M1-T16 adversarial pass, where eight phrasings -- grade, percentage, N out of M, pass/fail, band,
# mastery level -- all validated cleanly.
#
# It creates no evidence and the database boundary is untouched, so nothing becomes authoritative.
# What it does is reach a human reviewer as a verdict, from an endpoint whose entire contract is
# that it does not produce one -- and a reviewer acting on it is acting on an AI grade.
#
# Deliberately narrow. Every alternative requires verdict framing rather than a bare word, because
# formative material legitimately discusses strength and weakness, and a scan that flagged "strong"
# would be switched off within a week.
_VERDICT_IN_PROSE = re.compile(
    r"("
    r"\bgrades?\s+(?:of|is|:)\s*[A-F][+-]?\b"
    r"|\bgrade\s+[A-F][+-]?\b"
    r"|\b\d{1,3}\s?%"
    r"|\b\d+(?:\.\d+)?\s*(?:/|out\s+of)\s*\d+\b"
    r"|\b(?:would\s+be|is)\s+a\s+(?:pass|fail)\b"
    r"|\bbands?\s+\d+\b"
    r"|\bmastery\s+level\s*[:=]"
    r"|\bscores?\s*[:=]\s*\d"
    r"|\brate[sd]?\s+\d+(?:\.\d+)?\s*(?:/|out\s+of)\s*\d+\b"
    r")",
    re.IGNORECASE,
)

# Claims that something was written down. Nothing here is stored as evidence, and saying so would
# tell a reviewer the platform holds a record it does not hold.
_PERSISTENCE_CLAIM = re.compile(
    r"\b("
    r"(?:has|have) been (?:recorded|saved|stored|updated|logged)"
    r"|i (?:have )?(?:recorded|saved|stored|updated|logged)"
    r"|(?:this|it) (?:has been|was) (?:recorded|saved|stored)"
    r")\b",
    re.IGNORECASE,
)


def validate_evaluation(raw: str, context: dict[str, Any]) -> list[str]:
    """Returns validation error codes; an empty list means the material is worth returning."""
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError, TypeError:
        return ["SCHEMA_NOT_JSON"]

    if not isinstance(parsed, dict):
        return ["SCHEMA_NOT_OBJECT"]

    # Run first and unconditionally, ahead of the schema short-circuit. Every other check can wait
    # for a well-formed payload; this one cannot. A malformed output that also carries a score field
    # would otherwise be reported as a schema problem alone, and FORMATIVE_SCORING_FIELD_PRESENT is
    # the code the evaluation harness counts to know whether the rule is holding.
    errors = ["FORMATIVE_SCORING_FIELD_PRESENT"] if _has_scoring_field(parsed) else []

    schema_errors = _schema_errors(parsed)
    if schema_errors:
        return errors + schema_errors

    return errors + _formative_errors(parsed, context)


def _schema_errors(parsed: dict[str, Any]) -> list[str]:
    errors: list[str] = []

    if not isinstance(parsed.get("skillCode"), str) or not parsed["skillCode"].strip():
        errors.append("SCHEMA_MISSING_SKILL_CODE")

    indicators = parsed.get("indicators")
    if not isinstance(indicators, dict):
        errors.append("SCHEMA_BAD_INDICATORS")
    else:
        for band in _INDICATOR_BANDS:
            value = indicators.get(band)
            if not isinstance(value, str) or not value.strip():
                errors.append("SCHEMA_MISSING_INDICATOR")
                break
            if len(value) > MAX_TEXT_CHARS:
                errors.append("SCHEMA_INDICATOR_TOO_LONG")
                break
        if set(indicators) - set(_INDICATOR_BANDS):
            errors.append("SCHEMA_UNEXPECTED_INDICATOR")

    misconceptions = parsed.get("misconceptions")
    if not isinstance(misconceptions, list) or not all(
        isinstance(entry, str) for entry in misconceptions
    ):
        errors.append("SCHEMA_BAD_MISCONCEPTIONS")
    elif len(misconceptions) > MAX_MISCONCEPTIONS:
        errors.append("SCHEMA_TOO_MANY_MISCONCEPTIONS")

    probe = parsed.get("suggestedProbe")
    if not isinstance(probe, str) or not probe.strip():
        errors.append("SCHEMA_MISSING_PROBE")
    elif len(probe) > MAX_TEXT_CHARS:
        errors.append("SCHEMA_PROBE_TOO_LONG")

    if set(parsed) - _EVALUATION_FIELDS:
        errors.append("SCHEMA_UNEXPECTED_FIELD")

    return errors


def _formative_errors(parsed: dict[str, Any], context: dict[str, Any]) -> list[str]:
    errors: list[str] = []

    text = " ".join(_all_strings(parsed))
    if _VERDICT_IN_PROSE.search(text):
        errors.append("FORMATIVE_VERDICT_IN_PROSE")
    if _LEARNER_CLAIM.search(text):
        errors.append("FORMATIVE_CLAIMS_ABOUT_A_LEARNER")
    if _PERSISTENCE_CLAIM.search(text):
        errors.append("FORMATIVE_CLAIMS_PERSISTENCE")

    requested_skill = context.get("skillCode")
    if isinstance(requested_skill, str) and parsed["skillCode"] != requested_skill:
        errors.append("POLICY_SKILL_NOT_REQUESTED")

    return errors


def _has_scoring_field(value: Any) -> bool:
    """True when any key at any depth names a judgement.

    Recursive because the schema check above rejects unexpected *top-level* fields, and a nested
    ``{"indicators": {"strong": {"score": 0.9}}}`` would otherwise slip past a flat check. Belt and
    braces: the two together mean neither has to be the only thing that works.
    """
    if isinstance(value, dict):
        for key, nested in value.items():
            if isinstance(key, str) and key.strip().lower() in _SCORING_FIELD_NAMES:
                return True
            if _has_scoring_field(nested):
                return True
        return False
    if isinstance(value, list):
        return any(_has_scoring_field(entry) for entry in value)
    return False


def _all_strings(value: Any) -> list[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [text for nested in value.values() for text in _all_strings(nested)]
    if isinstance(value, list):
        return [text for entry in value for text in _all_strings(entry)]
    return []
