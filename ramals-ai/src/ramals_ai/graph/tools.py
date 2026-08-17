"""Tool capability checks (Doc 02 §5).

Four rules, and the third is the one that does the work:

1. Each agent has an explicit allowlist.
2. A tool not on it cannot be called.
3. Authorization happens **at execution time**, not when the graph is built. A graph assembled with
   a capability it later loses must fail at the call, not succeed because it was legal at wiring
   time.
4. Tool output is untrusted data. It is recorded as something a tool said, never promoted into a
   decision.

No mutating tool exists here. Tutor V1 starts with none at all, and the registry has no way to
declare one that writes — a mutating capability would need a new type, which is the point at which
somebody has to justify it.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from opentelemetry import metrics

from ramals_ai.contracts.generated import AgentType

logger = logging.getLogger(__name__)

_meter = metrics.get_meter("ramals-ai")

tool_denials = _meter.create_counter(
    "ramals.ai.graph.tool.denied",
    description="Tool invocations refused because the agent lacked the capability",
)
tool_invocations = _meter.create_counter(
    "ramals.ai.graph.tool.invoked",
    description="Tool invocations permitted and executed",
)


class ToolDenied(PermissionError):  # noqa: N818 - a denial, not an error condition
    """An agent attempted a capability it does not hold.

    A denial is a security event, not a routing hiccup: something asked the graph to do work outside
    what its agent was authorized for. It is counted, logged, and never retried on another path.
    """

    def __init__(self, agent_type: AgentType, tool: str) -> None:
        super().__init__(f"{agent_type} is not authorized to call '{tool}'")
        self.agent_type = agent_type
        self.tool = tool


@dataclass(frozen=True)
class ReadOnlyTool:
    """A capability that observes and never mutates.

    The only kind that exists. There is no `MutatingTool`, and adding one would be a visible,
    reviewable change rather than a flag somebody set to true.
    """

    name: str
    description: str
    run: Callable[[dict[str, Any]], dict[str, Any]]


@dataclass(frozen=True)
class ToolRegistry:
    """Which agent may call which tool.

    Empty allowlists are the default and the correct starting point: Doc 02 §5 says Tutor V1 begins
    with no mutating tools, and MVP-1 begins with no tools at all. Curriculum and retrieval tools
    arrive in MVP-2 behind MCP, with their own authorization story.
    """

    tools: dict[str, ReadOnlyTool] = field(default_factory=dict)
    allowlists: dict[AgentType, frozenset[str]] = field(default_factory=dict)

    def allowed(self, agent_type: AgentType) -> frozenset[str]:
        return self.allowlists.get(agent_type, frozenset())

    def authorize(self, agent_type: AgentType, tool: str) -> ReadOnlyTool:
        """Checks the capability now, at the moment of use.

        Deliberately not cached and not resolved when the graph is constructed. A capability check
        performed once at wiring time answers "was this legal when we started", which is not the
        question that matters when the call actually happens.
        """
        if tool not in self.allowed(agent_type):
            tool_denials.add(1, {"agent": agent_type.value, "tool": tool})
            logger.warning(
                "denied a tool invocation",
                extra={
                    "operation": "graph.tool.denied",
                    "agentType": agent_type.value,
                    "tool": tool,
                    "errorCode": "TOOL_NOT_AUTHORIZED",
                },
            )
            raise ToolDenied(agent_type, tool)

        resolved = self.tools.get(tool)
        if resolved is None:
            # On the allowlist but not registered: a configuration error, and still a denial. The
            # alternative -- treating it as absent-therefore-harmless -- would let a typo in an
            # allowlist read as a working capability.
            tool_denials.add(1, {"agent": agent_type.value, "tool": tool})
            raise ToolDenied(agent_type, tool)
        return resolved

    def invoke(self, agent_type: AgentType, tool: str, arguments: dict[str, Any]) -> dict[str, Any]:
        """Authorizes, then runs, then labels the result as untrusted."""
        resolved = self.authorize(agent_type, tool)
        tool_invocations.add(1, {"agent": agent_type.value, "tool": tool})
        output = resolved.run(arguments)
        # The wrapper is not decoration. Downstream code sees a record of what a tool returned,
        # which is harder to mistake for a fact the platform has established.
        return {"tool": tool, "trusted": False, "output": output}


def empty_registry() -> ToolRegistry:
    """The MVP-1 registry: no tools, no allowlists.

    Returned by a function rather than exposed as a module constant so no caller can mutate the
    shared default and quietly widen what every agent in the process is allowed to do.
    """
    return ToolRegistry()
