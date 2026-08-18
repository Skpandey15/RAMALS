"""What an assessment prompt is entitled to see.

Narrower than the tutor's, and narrower in a specific direction: an item generator is given the
*curriculum* it must write to, and nothing about how any learner performed. It does not receive a
mastery score, an evidence confidence or a learner reference.

That is not caution for its own sake. An item written to suit one learner's recorded weakness is an
item whose difficulty is entangled with that learner's history, and every later learner answering it
produces evidence the mastery engine will read as if the item were neutral. The generator is writing
curriculum, not a personalized hint, so it is given curriculum.

``masteryStatus`` is the one learner-derived field allowed through, because the requested difficulty
band has to come from somewhere and a coarse status is the least specific thing that answers it.
"""

from __future__ import annotations

from typing import Any

from ramals_ai.contracts.generated import AIRequestEnvelope
from ramals_ai.prompting.minimizer import MinimizedContext, copy_allowed

LEARNING_CONTEXT_ALLOWLIST = frozenset({"skillCode", "masteryStatus"})
"""The skill to write an item for, and how far along its learners are. Nothing else.

Absent and deliberately so: ``masteryScore`` and ``evidenceConfidence`` (numbers about one learner,
which an item must not be tuned to), ``prerequisites`` (the generator writes for one skill, and a
prerequisite list invites items that test two), and any learner identifier.
"""

DOMAIN_CONTEXT_ALLOWLIST = frozenset({"domainCode", "domainType", "curriculumVersion"})


def minimize(envelope: AIRequestEnvelope) -> MinimizedContext:
    """Reduces a request to the fields an assessment prompt may see."""
    minimized = MinimizedContext()

    if envelope.learningContext is not None:
        supplied = envelope.learningContext.model_dump(exclude_none=True)
        copy_allowed(
            supplied, LEARNING_CONTEXT_ALLOWLIST, minimized, "learningContext", agent="assessment"
        )

    if envelope.domainContext is not None:
        domain = envelope.domainContext.model_dump(exclude_none=True)
        allowed: dict[str, Any] = {}
        copy_allowed(domain, DOMAIN_CONTEXT_ALLOWLIST, allowed, "domainContext", agent="assessment")
        minimized["domain"] = allowed

    return minimized
