"""Context minimization — the last thing between a request and a prompt.

Doc 07 makes active answer-key leakage and cross-learner leakage *hard* gates: zero incidents, no
tolerance. The way to meet a zero-incident gate is not to instruct a model carefully. It is to
ensure the material is never in the process.

Two independent things already work in our favour. ``core.assessment_item_version.answer_key_jsonb``
is server-only and selected by no learner-facing read path, so an answer key does not leave Spring.
And ``LearningContext`` sets ``additionalProperties: false``, so a payload carrying an extra field
fails contract validation before anything here runs.

This module is the third line, and it is the only one that assumes the other two failed. It builds
the prompt context from an **allowlist**: fields not named here cannot reach a prompt, whatever
arrives in the envelope, however it got there. A denylist of forbidden keys would be the wrong
shape — it would need updating every time somebody invents a new way to spell "answer".
"""

from __future__ import annotations

import logging
from typing import Any

from opentelemetry import metrics

from ramals_ai.contracts.generated import AIRequestEnvelope

logger = logging.getLogger(__name__)

_meter = metrics.get_meter("ramals-ai")

minimizer_drops = _meter.create_counter(
    "ramals.ai.tutor.minimizer.dropped",
    description="Fields removed by context minimization before prompt assembly",
)

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


class MinimizedContext(dict[str, Any]):
    """A dictionary that is known to contain only allowlisted material.

    A distinct type rather than a plain dict so that "this has been minimized" is visible at every
    call site, and so a future prompt builder cannot be handed raw envelope data by accident.
    """


def minimize(envelope: AIRequestEnvelope) -> MinimizedContext:
    """Reduces a request to the fields a tutor prompt may see.

    The learner reference is dropped entirely. Spring authorized the request and correlates it by
    ``interactionId``; the model has no use for an identifier, and anything the model never receives
    cannot appear in what it writes.
    """
    minimized = MinimizedContext()

    if envelope.learningContext is not None:
        supplied = envelope.learningContext.model_dump(exclude_none=True)
        _copy_allowed(supplied, LEARNING_CONTEXT_ALLOWLIST, minimized, "learningContext")

    if envelope.domainContext is not None:
        domain = envelope.domainContext.model_dump(exclude_none=True)
        allowed: dict[str, Any] = {}
        _copy_allowed(domain, DOMAIN_CONTEXT_ALLOWLIST, allowed, "domainContext")
        minimized["domain"] = allowed

    return minimized


def _copy_allowed(
    source: dict[str, Any],
    allowlist: frozenset[str],
    destination: dict[str, Any],
    origin: str,
) -> None:
    """Copies allowlisted keys and counts everything refused.

    Refusals are counted rather than silently ignored: a non-zero rate means an upstream caller is
    sending material the tutor is not entitled to, which is worth knowing about long before it is
    worth panicking about.
    """
    for key, value in source.items():
        if key in allowlist:
            destination[key] = value
            continue
        minimizer_drops.add(1, {"origin": origin, "field": key})
        logger.warning(
            "dropped a field during context minimization",
            extra={
                "operation": "tutor.minimize",
                "origin": origin,
                "droppedField": key,
            },
        )


def contains_forbidden_material(rendered: str, forbidden: tuple[str, ...]) -> list[str]:
    """Reports any forbidden string present in rendered prompt text.

    A belt-and-braces check used by the leakage tests. The allowlist is what makes leakage
    impossible; this is what proves it, and would catch a future prompt builder that reached around
    the minimizer to read the envelope directly.
    """
    lowered = rendered.lower()
    return [candidate for candidate in forbidden if candidate.lower() in lowered]
