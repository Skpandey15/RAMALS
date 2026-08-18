"""What an assessment prompt is entitled to see.

The narrowest allowlist in the service, and narrow in a specific direction: an item generator is
given the *curriculum* it must write to and nothing whatsoever about how any learner performed.

The principle is that the agent receives the **decision, not the learner-derived reason for it**.
Spring decides which difficulty band to commission — deterministically, from mastery state it owns —
and passes that band as a requested difficulty. It does not pass the mastery status the band was
derived from.

The two look interchangeable and are not. ``masteryStatus`` is learner-specific: it lives on
``ledger.mastery_snapshot`` keyed by learner and skill, and it means *this learner is at this
status*. An item written with that in the prompt has a difficulty entangled with one learner's
history, while every later learner answering it produces evidence the mastery engine reads as
neutral. A requested band carries the same operational information with none of the provenance, so
the item is a curriculum artefact rather than a personalized one — which is what it has to be, since
it outlives the request and is answered by everyone.

It also means the prompt holds nothing that could be echoed back as an observation, which is the
property M1-ADR-010 needs from the evaluate path.
"""

from __future__ import annotations

from typing import Any

from ramals_ai.contracts.generated import AIRequestEnvelope
from ramals_ai.prompting.minimizer import MinimizedContext, copy_allowed

LEARNING_CONTEXT_ALLOWLIST = frozenset({"skillCode"})
"""The skill to write about. Nothing else from the learning context reaches an assessment prompt.

Absent and deliberately so: ``masteryScore`` and ``evidenceConfidence`` (numbers about one learner),
``masteryStatus`` (a verdict about one learner, replaced by the requested difficulty band Spring
derives from it), ``prerequisites`` (the generator writes for one skill, and a prerequisite list
invites items that test two), and any learner identifier.
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
