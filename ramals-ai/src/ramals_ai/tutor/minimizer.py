"""What a tutor prompt is entitled to see.

Two independent things already work in our favour. ``core.assessment_item_version.answer_key_jsonb``
is server-only and selected by no learner-facing read path, so an answer key does not leave Spring.
And ``LearningContext`` sets ``additionalProperties: false``, so a payload carrying an extra field
fails contract validation before anything here runs.

The allowlist below is the third line, and it is the only one that assumes the other two failed:
fields not named here cannot reach a prompt, whatever arrives in the envelope, however it got there.
The enforcement itself lives in :mod:`ramals_ai.prompting.minimizer`.
"""

from __future__ import annotations

from typing import Any

from ramals_ai.contracts.generated import AIRequestEnvelope
from ramals_ai.prompting.minimizer import (
    MinimizedContext,
    contains_forbidden_material,
    copy_allowed,
    minimizer_drops,
)

__all__ = [
    "DOMAIN_CONTEXT_ALLOWLIST",
    "LEARNING_CONTEXT_ALLOWLIST",
    "MinimizedContext",
    "contains_forbidden_material",
    "minimize",
    "minimizer_drops",
]

LEARNING_CONTEXT_ALLOWLIST = frozenset(
    {
        "skillCode",
        "masteryScore",
        "evidenceConfidence",
        "masteryStatus",
        "prerequisites",
    }
)
"""Exactly the fields a tutor needs to pitch an explanation, and nothing else.

Note what is absent and deliberately so: no learner identifier, no attempt history, no item text,
no options, no answer key, no other learner's anything. A tutor explains a skill at a level; none of
that requires knowing who is asking.
"""

DOMAIN_CONTEXT_ALLOWLIST = frozenset({"domainCode", "domainType", "curriculumVersion"})


def minimize(envelope: AIRequestEnvelope) -> MinimizedContext:
    """Reduces a request to the fields a tutor prompt may see.

    The learner reference is dropped entirely. Spring authorized the request and correlates it by
    ``interactionId``; the model has no use for an identifier, and anything the model never receives
    cannot appear in what it writes.
    """
    minimized = MinimizedContext()

    if envelope.learningContext is not None:
        supplied = envelope.learningContext.model_dump(exclude_none=True)
        copy_allowed(
            supplied, LEARNING_CONTEXT_ALLOWLIST, minimized, "learningContext", agent="tutor"
        )

    if envelope.domainContext is not None:
        domain = envelope.domainContext.model_dump(exclude_none=True)
        allowed: dict[str, Any] = {}
        copy_allowed(domain, DOMAIN_CONTEXT_ALLOWLIST, allowed, "domainContext", agent="tutor")
        minimized["domain"] = allowed

    return minimized
