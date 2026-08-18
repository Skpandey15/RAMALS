"""Allowlist for the Adaptation Agent's learner context."""

from __future__ import annotations

from typing import Any

from ramals_ai.contracts.generated import AIRequestEnvelope
from ramals_ai.prompting.minimizer import MinimizedContext, copy_allowed

LEARNING_CONTEXT_ALLOWLIST = frozenset(
    {"skillCode", "masteryScore", "evidenceConfidence", "masteryStatus", "prerequisites"}
)
DOMAIN_CONTEXT_ALLOWLIST = frozenset({"domainCode", "domainType", "curriculumVersion"})
GOAL_CONTEXT_ALLOWLIST = frozenset({"goalType", "goalCode", "targetDate", "goalVersion"})


def minimize(envelope: AIRequestEnvelope) -> MinimizedContext:
    """Copy only adaptation-relevant value fields; never copy learner or transport data."""
    minimized = MinimizedContext()

    if envelope.learningContext is not None:
        copy_allowed(
            envelope.learningContext.model_dump(exclude_none=True),
            LEARNING_CONTEXT_ALLOWLIST,
            minimized,
            "learningContext",
            agent="adaptation",
        )

    if envelope.domainContext is not None:
        domain: dict[str, Any] = {}
        copy_allowed(
            envelope.domainContext.model_dump(exclude_none=True),
            DOMAIN_CONTEXT_ALLOWLIST,
            domain,
            "domainContext",
            agent="adaptation",
        )
        minimized["domain"] = domain

    if envelope.learningGoalContext is not None:
        goal: dict[str, Any] = {}
        copy_allowed(
            envelope.learningGoalContext.model_dump(mode="json", exclude_none=True),
            GOAL_CONTEXT_ALLOWLIST,
            goal,
            "learningGoalContext",
            agent="adaptation",
        )
        minimized["learningGoal"] = goal

    if envelope.requestedCapability:
        minimized["requestedCapability"] = envelope.requestedCapability

    return minimized
