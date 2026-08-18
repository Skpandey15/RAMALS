"""Validation of an assessment proposal.

Spring's ``ContentValidationPipeline`` is the authority (M1-ADR-006). It runs over every candidate
before anything is stored, it decides the trust state, and it would refuse a bad item whether or not
this module existed.

So why validate here? Because a candidate refused downstream has already cost a model call. Checking
the recognisable mistakes at the boundary makes the repair loop from M1-T06 useful: the agent is
told what was wrong and can rewrite inside the same request, instead of returning something Spring
will discard and a reviewer will never see.

That makes this a deliberate mirror of the Java validators, and the mirror is one-directional.
Rules are copied *from* Spring, never invented here — a rule that exists only on this side would
admit content Spring then refuses, or refuse content Spring would have accepted, and either way the
authority would no longer be the thing deciding.

None of this promotes. Passing every check here means an item has failed to be rejected early; it is
still UNVERIFIED, and a human still has to approve it.
"""

from __future__ import annotations

import json
import re
from typing import Any

VALID_DIFFICULTIES = frozenset({"FOUNDATIONAL", "INTERMEDIATE", "ADVANCED"})

MIN_OPTIONS = 2
MAX_OPTIONS = 6
MAX_STEM_CHARS = 1200
MAX_RATIONALE_CHARS = 600

AMBIGUOUS_OPTIONS = frozenset({"all of the above", "none of the above", "both a and b"})
"""Options that make an item ambiguous regardless of what the learner knows. Mirrors
``QualitySafetyValidator.AMBIGUOUS_MARKERS``."""

_GENERATOR_NARRATION = re.compile(
    r"\b(as an ai|as a language model|as an assistant|here is (?:a|your) question"
    r"|i (?:have|will) (?:written|write|generated|generate))\b",
    re.IGNORECASE,
)

_ITEM_FIELDS = frozenset(
    {"skillCode", "objectiveCode", "difficulty", "stem", "options", "answerKey", "rationale"}
)


def validate_item(raw: str, context: dict[str, Any]) -> list[str]:
    """Returns validation error codes; an empty list means the candidate is worth sending on.

    Codes rather than prose, so the graph can branch on them and the evaluation harness can count
    them without parsing English.
    """
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError, TypeError:
        return ["SCHEMA_NOT_JSON"]

    if not isinstance(parsed, dict):
        return ["SCHEMA_NOT_OBJECT"]

    errors = _item_schema_errors(parsed)
    if errors:
        return errors

    return _item_structural_errors(parsed) + _item_policy_errors(parsed, context)


def _item_schema_errors(parsed: dict[str, Any]) -> list[str]:
    errors: list[str] = []

    if not isinstance(parsed.get("skillCode"), str) or not parsed["skillCode"].strip():
        errors.append("SCHEMA_MISSING_SKILL_CODE")

    objective = parsed.get("objectiveCode")
    if objective is not None and (not isinstance(objective, str) or not objective.strip()):
        errors.append("SCHEMA_BAD_OBJECTIVE_CODE")

    if parsed.get("difficulty") not in VALID_DIFFICULTIES:
        errors.append("SCHEMA_BAD_DIFFICULTY")

    stem = parsed.get("stem")
    if not isinstance(stem, str) or not stem.strip():
        errors.append("SCHEMA_MISSING_STEM")
    elif len(stem) > MAX_STEM_CHARS:
        errors.append("SCHEMA_STEM_TOO_LONG")

    if not _is_string_list(parsed.get("options")):
        errors.append("SCHEMA_BAD_OPTIONS")

    if not _is_string_list(parsed.get("answerKey")):
        errors.append("SCHEMA_BAD_ANSWER_KEY")

    rationale = parsed.get("rationale")
    if not isinstance(rationale, str) or not rationale.strip():
        # An item without a reason cannot be reviewed quickly, and review is the only thing standing
        # between a generated item and a learner.
        errors.append("SCHEMA_MISSING_RATIONALE")
    elif len(rationale) > MAX_RATIONALE_CHARS:
        errors.append("SCHEMA_RATIONALE_TOO_LONG")

    if set(parsed) - _ITEM_FIELDS:
        errors.append("SCHEMA_UNEXPECTED_FIELD")

    return errors


def _item_structural_errors(parsed: dict[str, Any]) -> list[str]:
    """Mirrors ``StructuralValidator``: shape that makes an item answerable at all."""
    errors: list[str] = []
    options: list[str] = parsed["options"]
    answer_key: list[str] = parsed["answerKey"]

    if len(options) < MIN_OPTIONS:
        errors.append("STRUCTURAL_TOO_FEW_OPTIONS")
    if len(options) > MAX_OPTIONS:
        errors.append("STRUCTURAL_TOO_MANY_OPTIONS")
    if any(not option.strip() for option in options):
        errors.append("STRUCTURAL_EMPTY_OPTION")

    if not answer_key:
        errors.append("STRUCTURAL_NO_CORRECT_OPTION")
    elif not set(answer_key).issubset(set(options)):
        # An item whose key names an option that does not exist is unanswerable correctly, and would
        # score every learner wrong while looking entirely well-formed in a review queue.
        errors.append("STRUCTURAL_ANSWER_KEY_NOT_IN_OPTIONS")
    elif len(set(answer_key)) == len(set(options)):
        errors.append("STRUCTURAL_EVERY_OPTION_CORRECT")

    if len(options) != len(set(options)):
        # Duplicate options make the correct answer ambiguous even when the key is right, and the
        # resulting evidence is noise attributed to a skill.
        errors.append("QUALITY_DUPLICATE_OPTIONS")

    if any(option.strip().lower() in AMBIGUOUS_OPTIONS for option in options):
        errors.append("QUALITY_AGGREGATE_OPTION")

    if _GENERATOR_NARRATION.search(parsed["stem"]):
        # A generator narrating itself into the item. Harmless to a reader, corrosive to trust,
        # and a reliable signal the generation went sideways.
        errors.append("QUALITY_GENERATOR_NARRATION")

    return errors


def _item_policy_errors(parsed: dict[str, Any], context: dict[str, Any]) -> list[str]:
    """Mirrors ``DeterministicPolicyValidator``, against the context the agent was given.

    Defers wherever the context is silent. Spring holds the curriculum graph; guessing here on
    partial information would refuse items the authority would have accepted.
    """
    errors: list[str] = []

    requested_skill = context.get("skillCode")
    if isinstance(requested_skill, str) and parsed["skillCode"] != requested_skill:
        # Writing about a different skill than the one asked for produces evidence attributed to a
        # skill the item does not measure.
        errors.append("POLICY_SKILL_NOT_REQUESTED")

    available = context.get("availableObjectives")
    objective = parsed.get("objectiveCode")
    if (
        isinstance(available, list)
        and available
        and objective is not None
        and objective not in available
    ):
        errors.append("POLICY_OBJECTIVE_NOT_OFFERED")

    return errors


def _is_string_list(value: Any) -> bool:
    return isinstance(value, list) and all(isinstance(entry, str) for entry in value)
