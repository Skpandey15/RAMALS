"""Correlation identifiers carried across the Spring to Python boundary.

The identifiers answer different questions and must not be conflated (Master Plan §4):

* ``interactionId`` — one logical learner action. Stable across safe retries. This is the support
  code a learner can read off an error screen.
* ``traceId`` — one distributed execution. A retry produces a new one.
* ``spanId`` — one operation inside that execution.
* ``requestId`` — one transport attempt. New on every retry.
* ``agentRunId`` — one orchestrated graph execution. Distinct from all three above: a request that
  is retried produces several, and one interaction may involve several agents.
* ``toolCallId`` — one tool invocation inside a run.

The last two are the Observability HLD §9 fields. Agent work needs correlation beyond standard
request tracing because a run is not a request: it makes several model calls, may repair its own
output, and can attempt capabilities it does not hold. "Which run did this" and "which attempt did
this" are questions the transport identifiers cannot answer.

The validation rule is copied deliberately from the Java ``UuidV7.isCanonical``: a canonical
lowercase UUIDv7. Two runtimes disagreeing about what a valid interactionId looks like would mean a
value Spring happily mints is refused here — the sort of fault that only appears in production, and
only for the requests you most want to trace.
"""

from __future__ import annotations

import uuid
from collections.abc import Iterator
from contextlib import contextmanager
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

# Bound for the duration of one graph run and one tool invocation respectively. Held here beside the
# request identifiers, and emitted by the same formatter, so an agent log line is correlated by the
# same mechanism as every other line rather than by each call site remembering to pass them.
_agent_run_id: ContextVar[str] = ContextVar("agent_run_id", default="")
_proposal_id: ContextVar[str] = ContextVar("proposal_id", default="")
_tool_call_id: ContextVar[str] = ContextVar("tool_call_id", default="")


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


def new_agent_run_id() -> str:
    """Time-ordered, so the runs behind one interaction sort into the order they happened."""
    return str(uuid.uuid7())


def new_tool_call_id() -> str:
    return str(uuid.uuid7())


@contextmanager
def agent_run(agent_run_id: str, proposal_id: str) -> Iterator[None]:
    """Binds one graph run's identifiers for its duration.

    A context manager rather than a bind/reset pair because a run that raises must still unbind: a
    leaked agentRunId would attach one run's identity to a later run's log lines, which is worse
    than having none at all -- an absent field reads as missing, a wrong one reads as evidence.
    """
    run_token = _agent_run_id.set(agent_run_id)
    proposal_token = _proposal_id.set(proposal_id)
    try:
        yield
    finally:
        _agent_run_id.reset(run_token)
        _proposal_id.reset(proposal_token)


@contextmanager
def tool_call(tool_call_id: str) -> Iterator[None]:
    """Binds one tool invocation's identifier for its duration.

    Nested inside :func:`agent_run`, and unbound on the way out for the same reason: a denial is a
    security event, and one attributed to the wrong invocation is a misleading record of it.
    """
    token = _tool_call_id.set(tool_call_id)
    try:
        yield
    finally:
        _tool_call_id.reset(token)


def current_agent_run_id() -> str:
    return _agent_run_id.get()


def current_proposal_id() -> str:
    return _proposal_id.get()


def current_tool_call_id() -> str:
    return _tool_call_id.get()
