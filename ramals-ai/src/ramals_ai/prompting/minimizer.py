"""Context minimization — the last thing between a request and a prompt.

Doc 07 makes active answer-key leakage and cross-learner leakage *hard* gates: zero incidents, no
tolerance. The way to meet a zero-incident gate is not to instruct a model carefully. It is to
ensure the material is never in the process.

This module holds the mechanism; each agent declares its own allowlist beside its own prompt,
because what a tutor is entitled to see and what an item generator is entitled to see are different
questions and should not drift into one shared list that grows to satisfy whichever agent needed
most. The mechanism being shared is what stops the *enforcement* from drifting.

An allowlist rather than a denylist, throughout: a denylist would need updating every time somebody
invents a new way to spell "answer".
"""

from __future__ import annotations

import logging
from typing import Any

from opentelemetry import metrics

logger = logging.getLogger(__name__)

_meter = metrics.get_meter("ramals-ai")

minimizer_drops = _meter.create_counter(
    "ramals.ai.minimizer.dropped",
    description="Fields removed by context minimization before prompt assembly",
)


class MinimizedContext(dict[str, Any]):
    """A dictionary that is known to contain only allowlisted material.

    A distinct type rather than a plain dict so that "this has been minimized" is visible at every
    call site, and so a future prompt builder cannot be handed raw envelope data by accident.
    """


def copy_allowed(
    source: dict[str, Any],
    allowlist: frozenset[str],
    destination: dict[str, Any],
    origin: str,
    *,
    agent: str,
) -> None:
    """Copies allowlisted keys and counts everything refused.

    Refusals are counted rather than silently ignored: a non-zero rate means an upstream caller is
    sending material this agent is not entitled to, which is worth knowing about long before it is
    worth panicking about.
    """
    for key, value in source.items():
        if key in allowlist:
            destination[key] = value
            continue
        minimizer_drops.add(1, {"origin": origin, "field": key, "agent": agent})
        logger.warning(
            "dropped a field during context minimization",
            extra={
                "operation": f"{agent}.minimize",
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
