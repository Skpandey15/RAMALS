"""``ReadOnlyTool`` adapters over the five MCP-2 capabilities, and per-agent-type registry wiring
(M2-ADR-031).

Bridges ``graph/tools.py``'s existing, synchronous ``ReadOnlyTool``/``ToolRegistry`` abstraction (no
change to either) to the async ``RamalsMcpReadClient``. Every tool here is read-only by construction
-- there is no ``MutatingTool`` type for one to declare, and none is declared here.

A ``ToolRegistry`` built by this module is scoped to exactly one authorized interaction: it closes
over that interaction's own ``McpExecutionContext`` (the delegated learner-context credential and
deadline), so it must be built fresh per interaction, from the ``McpExecutionContext`` Java's own
call into ramals-ai for that interaction supplied -- never shared across learners or interactions,
and never held longer than the interaction it was built for.
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from typing import Any

import anyio
from pydantic import BaseModel

from ramals_ai.contracts.generated import AgentType
from ramals_ai.graph.tools import ReadOnlyTool, ToolRegistry
from ramals_ai.mcp.client import (
    ATTEMPT_REPORT,
    CURRENT_DOMAIN_REPORT,
    LONGITUDINAL_SUMMARY,
    MASTERY_CURRENT,
    MISCONCEPTION_LONGITUDINAL_DETAIL,
    RamalsMcpReadClient,
)
from ramals_ai.mcp.context import McpExecutionContext
from ramals_ai.mcp.errors import McpError, McpErrorCode

logger = logging.getLogger(__name__)

#: Least-privilege per-agent allowlists (Doc 02 §5). Named here, not invented broader: a diagnostic
#: reasoning node may read every H6/H7 capability; adaptation additionally needs mastery, plus the
#: diagnostic reads it reasons from. Neither gets a capability it does not have a concrete need for.
DIAGNOSTIC_AGENT_CAPABILITIES: frozenset[str] = frozenset(
    {CURRENT_DOMAIN_REPORT, ATTEMPT_REPORT, LONGITUDINAL_SUMMARY, MISCONCEPTION_LONGITUDINAL_DETAIL}
)
ADAPTATION_AGENT_CAPABILITIES: frozenset[str] = frozenset(
    {MASTERY_CURRENT, CURRENT_DOMAIN_REPORT, LONGITUDINAL_SUMMARY}
)

_DEFAULT_ALLOWLISTS: dict[AgentType, frozenset[str]] = {
    AgentType.DIAGNOSTIC: DIAGNOSTIC_AGENT_CAPABILITIES,
    AgentType.ADAPTATION: ADAPTATION_AGENT_CAPABILITIES,
    # TUTOR and ASSESSMENT get none by default -- Doc 02 §5's own "empty is the correct starting
    # point" rule. A future need is an explicit, reviewed addition to _DEFAULT_ALLOWLISTS, never an
    # inherited default.
}


def _run_sync[ResultModelT: BaseModel](
    coro_factory: Callable[[], Awaitable[ResultModelT]],
) -> dict[str, Any]:
    """Bridges one async MCP call into ``ReadOnlyTool.run``'s synchronous
    ``Callable[[dict], dict]`` shape.

    ``anyio.run`` starts a fresh event loop for exactly this one call and tears it down on return,
    which is correct here because graph execution (``GraphRun.run``, ``TutorAgent.respond``, ...) is
    itself synchronous and -- in the deployed FastAPI app -- runs inside a worker thread with no
    event loop of its own already running, never on the main event loop thread.
    """
    result: ResultModelT = anyio.run(coro_factory)
    return result.model_dump(by_alias=True, mode="json")


def _current_domain_report_tool(
    client: RamalsMcpReadClient, context: McpExecutionContext
) -> ReadOnlyTool:
    def run(arguments: dict[str, Any]) -> dict[str, Any]:
        domain_code = _require_str(arguments, "domainCode")
        return _run_sync(lambda: client.current_domain_report(context, domain_code=domain_code))

    return ReadOnlyTool(
        name=CURRENT_DOMAIN_REPORT,
        description="The learner's complete current H6 diagnostic view for one domain.",
        run=run,
    )


def _attempt_report_tool(client: RamalsMcpReadClient, context: McpExecutionContext) -> ReadOnlyTool:
    def run(arguments: dict[str, Any]) -> dict[str, Any]:
        attempt_id = _require_str(arguments, "attemptId")
        return _run_sync(lambda: client.attempt_report(context, attempt_id=attempt_id))

    return ReadOnlyTool(
        name=ATTEMPT_REPORT,
        description="The exact-attempt H6 diagnostic findings one assessment attempt produced.",
        run=run,
    )


def _longitudinal_summary_tool(
    client: RamalsMcpReadClient, context: McpExecutionContext
) -> ReadOnlyTool:
    def run(arguments: dict[str, Any]) -> dict[str, Any]:
        domain_code = _require_str(arguments, "domainCode")
        return _run_sync(lambda: client.longitudinal_summary(context, domain_code=domain_code))

    return ReadOnlyTool(
        name=LONGITUDINAL_SUMMARY,
        description="Every misconception with an H7 baseline in one domain.",
        run=run,
    )


def _misconception_longitudinal_detail_tool(
    client: RamalsMcpReadClient, context: McpExecutionContext
) -> ReadOnlyTool:
    def run(arguments: dict[str, Any]) -> dict[str, Any]:
        misconception_id = _require_str(arguments, "misconceptionId")
        return _run_sync(
            lambda: client.misconception_longitudinal_detail(
                context, misconception_id=misconception_id
            )
        )

    return ReadOnlyTool(
        name=MISCONCEPTION_LONGITUDINAL_DETAIL,
        description="The H7 longitudinal projection for exactly one misconception.",
        run=run,
    )


def _mastery_current_tool(
    client: RamalsMcpReadClient, context: McpExecutionContext
) -> ReadOnlyTool:
    def run(arguments: dict[str, Any]) -> dict[str, Any]:
        domain_code = _require_str(arguments, "domainCode")
        version_code = _require_str(arguments, "versionCode")
        return _run_sync(
            lambda: client.current_mastery(
                context, domain_code=domain_code, version_code=version_code
            )
        )

    return ReadOnlyTool(
        name=MASTERY_CURRENT,
        description="The learner's latest mastery score, evidence confidence, and status.",
        run=run,
    )


_TOOL_FACTORIES: tuple[Callable[[RamalsMcpReadClient, McpExecutionContext], ReadOnlyTool], ...] = (
    _current_domain_report_tool,
    _attempt_report_tool,
    _longitudinal_summary_tool,
    _misconception_longitudinal_detail_tool,
    _mastery_current_tool,
)


def _require_str(arguments: dict[str, Any], field: str) -> str:
    """Business lookup arguments only -- never ``learnerId``/``learnerRef``. A tool built here has
    no parameter path that could accept one: the field names below are exhaustive and hardcoded, so
    a caller supplying an extra key simply has it ignored, never read."""
    value = arguments.get(field)
    if not isinstance(value, str) or not value:
        raise McpError(
            McpErrorCode.MCP_MALFORMED_INPUT,
            f"'{field}' is required and must be a non-empty string",
        )
    return value


def build_mcp_tool_registry(
    client: RamalsMcpReadClient,
    context: McpExecutionContext,
    *,
    allowlists: dict[AgentType, frozenset[str]] | None = None,
) -> ToolRegistry:
    """Builds a fresh, interaction-scoped ``ToolRegistry`` for exactly the five MCP-2 capabilities.

    Must be called once per authorized interaction, with that interaction's own
    ``McpExecutionContext`` -- every ``ReadOnlyTool.run`` closure built here captures ``context`` by
    reference, so a registry built for one interaction must never be reused for another.

    ``allowlists`` defaults to :data:`_DEFAULT_ALLOWLISTS`; passed explicitly only where a caller
    needs a narrower allowlist than the default for a specific run.
    """
    tools = {factory(client, context).name: factory(client, context) for factory in _TOOL_FACTORIES}
    return ToolRegistry(tools=tools, allowlists=dict(allowlists or _DEFAULT_ALLOWLISTS))
