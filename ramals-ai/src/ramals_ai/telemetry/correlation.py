"""Correlation identifiers carried across the Spring to Python boundary.

The identifiers answer different questions and must not be conflated (Master Plan §4):

* ``interactionId`` — one logical learner action. Stable across safe retries. This is the support
  code a learner can read off an error screen.
* ``traceId`` — one distributed execution. A retry produces a new one.
* ``spanId`` — one operation inside that execution.
* ``requestId`` — one transport attempt. New on every retry.

The validation rule is copied deliberately from the Java ``UuidV7.isCanonical``: a canonical
lowercase UUIDv7. Two runtimes disagreeing about what a valid interactionId looks like would mean a
value Spring happily mints is refused here — the sort of fault that only appears in production, and
only for the requests you most want to trace.
"""

from __future__ import annotations

import uuid
from contextvars import ContextVar
from dataclasses import dataclass

INTERACTION_ID_HEADER = "X-Interaction-ID"
REQUEST_ID_HEADER = "X-Request-ID"
TRACE_ID_HEADER = "X-Trace-ID"
TRACEPARENT_HEADER = "traceparent"
TRACESTATE_HEADER = "tracestate"

# Bound per request. ContextVars rather than thread locals because the request path is async, and a
# thread local would leak one request's identifiers into another under concurrency.
_interaction_id: ContextVar[str] = ContextVar("interaction_id", default="")
_request_id: ContextVar[str] = ContextVar("request_id", default="")


@dataclass(frozen=True)
class Correlation:
    """The identifiers bound to the current request."""

    interaction_id: str
    request_id: str
    supplied_interaction_id: bool


def is_canonical_uuid7(value: str | None) -> bool:
    """Mirrors the Java ``UuidV7.isCanonical`` check, including the lowercase requirement."""
    if value is None or len(value) != 36 or value != value.lower():
        return False
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        return False
    return parsed.version == 7 and parsed.variant == uuid.RFC_4122 and str(parsed) == value


def new_interaction_id() -> str:
    return str(uuid.uuid7())


def new_request_id() -> str:
    return str(uuid.uuid4())


def bind(interaction_id: str, request_id: str) -> tuple[object, object]:
    """Binds identifiers for the current context, returning tokens for reset."""
    return _interaction_id.set(interaction_id), _request_id.set(request_id)


def reset(tokens: tuple[object, object]) -> None:
    """Always call this, or a later request inherits the previous request's identifiers."""
    interaction_token, request_token = tokens
    _interaction_id.reset(interaction_token)  # type: ignore[arg-type]
    _request_id.reset(request_token)  # type: ignore[arg-type]


def current_interaction_id() -> str:
    return _interaction_id.get()


def current_request_id() -> str:
    return _request_id.get()
