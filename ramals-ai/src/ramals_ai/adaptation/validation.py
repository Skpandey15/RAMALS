"""Validation for non-authoritative adaptation proposals."""

from __future__ import annotations

import json
from typing import Any

VALID_ACTIONS = frozenset({"COLLECT_EVIDENCE", "RETEACH", "PRACTICE", "ADVANCE"})
MAX_RATIONALE_CHARS = 600
_FIELDS = frozenset({"skillCode", "recommendedAction", "rationale"})


def validate(raw: str, context: dict[str, Any]) -> list[str]:
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError, TypeError:
        return ["SCHEMA_NOT_JSON"]
    if not isinstance(parsed, dict):
        return ["SCHEMA_NOT_OBJECT"]

    errors: list[str] = []
    skill = parsed.get("skillCode")
    if not isinstance(skill, str) or not skill.strip():
        errors.append("SCHEMA_MISSING_SKILL_CODE")
    elif isinstance(context.get("skillCode"), str) and skill != context["skillCode"]:
        errors.append("POLICY_SKILL_NOT_REQUESTED")

    if parsed.get("recommendedAction") not in VALID_ACTIONS:
        errors.append("SCHEMA_BAD_RECOMMENDED_ACTION")

    rationale = parsed.get("rationale")
    if not isinstance(rationale, str) or not rationale.strip():
        errors.append("SCHEMA_MISSING_RATIONALE")
    elif len(rationale) > MAX_RATIONALE_CHARS:
        errors.append("SCHEMA_RATIONALE_TOO_LONG")

    if set(parsed) - _FIELDS:
        errors.append("SCHEMA_UNEXPECTED_FIELD")
    return errors
