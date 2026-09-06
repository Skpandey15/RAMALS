"""The trusted MCP execution context -- deliberately separate from ``AgentState``.

``AgentState`` (``graph/state.py``) is model-relevant working memory: "carries no authority", by its
own docstring, and is exactly the kind of thing a durable LangGraph checkpoint could someday persist
or a debug log could dump. The delegated learner-context credential must never live there, or
anywhere else the model, a checkpoint, or a log line can observe it (M2-ADR-031).

This module is the "smallest safe execution-context abstraction" instead: a small, frozen carrier
built once per authorized interaction, threaded explicitly through the MCP client and tool-registry
factory below, and never stored on ``AgentState`` or any other model-visible object.
"""

from __future__ import annotations

from dataclasses import dataclass

from ramals_ai.gateway.budget import Deadline


@dataclass(frozen=True)
class McpExecutionContext:
    """Trusted, per-interaction security context for MCP-3 calls.

    Constructed exactly once per authorized interaction, from what Java's own call into ramals-ai
    supplied for that interaction -- never cached globally, never reused across learners or
    interactions, never placed in ``AgentState`` or any durable/checkpointed structure.

    Attributes:
        delegated_context_token: the raw, Java-issued delegated learner-context credential
            (M2-ADR-031), forwarded to Java's MCP transport exactly as received -- never parsed,
            never logged, never exposed as a tool argument or model-visible value. Repr-suppressed
            so an accidental ``print(context)``/log of the whole object cannot leak it.
        interaction_id: this interaction's own correlation id, propagated to MCP call telemetry.
        deadline: the interaction's own absolute deadline (already in effect before this context was
            built) -- MCP calls consume this same budget; they never start an independent one.
    """

    delegated_context_token: str
    interaction_id: str
    deadline: Deadline

    def __repr__(self) -> str:  # pragma: no cover - trivial
        return (
            f"McpExecutionContext(delegated_context_token=REDACTED, "
            f"interaction_id={self.interaction_id!r}, deadline=...)"
        )
