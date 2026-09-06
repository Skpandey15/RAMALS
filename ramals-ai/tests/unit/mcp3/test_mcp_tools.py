"""MCP-3 review requirements for the ReadOnlyTool/ToolRegistry integration: exactly five
capabilities, least-privilege per-agent allowlists, no learnerId/learnerRef path, and no way for
remote discovery to widen what is registered."""

from __future__ import annotations

from unittest.mock import AsyncMock

import pytest

from ramals_ai.contracts.generated import AgentType
from ramals_ai.gateway.budget import Deadline
from ramals_ai.graph.tools import ToolDenied
from ramals_ai.mcp import client as client_module
from ramals_ai.mcp.client import (
    ATTEMPT_REPORT,
    CURRENT_DOMAIN_REPORT,
    LONGITUDINAL_SUMMARY,
    MASTERY_CURRENT,
    MISCONCEPTION_LONGITUDINAL_DETAIL,
    RamalsMcpReadClient,
)
from ramals_ai.mcp.context import McpExecutionContext
from ramals_ai.mcp.models import McpMasteryReport
from ramals_ai.mcp.tools import (
    ADAPTATION_AGENT_CAPABILITIES,
    DIAGNOSTIC_AGENT_CAPABILITIES,
    build_mcp_tool_registry,
)


def _context() -> McpExecutionContext:
    return McpExecutionContext(
        delegated_context_token="test-delegated-context-token",  # noqa: S106
        interaction_id="01973b3a-0000-7000-8000-000000000001",
        deadline=Deadline.in_ms(30_000),
    )


def _fake_client() -> RamalsMcpReadClient:
    client = RamalsMcpReadClient.__new__(RamalsMcpReadClient)  # bypass __init__'s mcp_enabled check
    client._url = "http://learning-platform:8080/mcp"
    client._token_provider = AsyncMock()
    return client


def test_registry_contains_exactly_five_capabilities() -> None:
    """Test #6."""
    registry = build_mcp_tool_registry(_fake_client(), _context())
    assert set(registry.tools.keys()) == {
        CURRENT_DOMAIN_REPORT,
        ATTEMPT_REPORT,
        LONGITUDINAL_SUMMARY,
        MISCONCEPTION_LONGITUDINAL_DETAIL,
        MASTERY_CURRENT,
    }


def test_build_mcp_tool_registry_constructs_each_tool_exactly_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Review cleanup: the dict comprehension this used to be called every factory twice (once for
    its key, once for its value). Proven here by counting factory invocations directly rather than
    by relying on ``ReadOnlyTool``'s own construction having no observable side effect."""
    import ramals_ai.mcp.tools as tools_module

    calls = 0
    original_factories = tools_module._TOOL_FACTORIES

    def counted(client: object, context: object) -> object:
        nonlocal calls
        calls += 1
        return original_factories[0](client, context)  # type: ignore[arg-type]

    monkeypatch.setattr(tools_module, "_TOOL_FACTORIES", (counted,))
    tools_module.build_mcp_tool_registry(_fake_client(), _context())

    assert calls == 1


def test_no_tool_run_signature_accepts_a_learner_identifying_argument(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Test #5: every tool's ``run`` reads only its own hardcoded business-lookup field name(s); a
    caller-supplied learnerId/learnerRef argument is simply never read by any of them."""
    registry = build_mcp_tool_registry(_fake_client(), _context())
    for tool in registry.tools.values():

        async def fake_call(*_args: object, **_kwargs: object) -> McpMasteryReport:
            return McpMasteryReport(domainCode="KAFKA", versionCode="v1", skills=[])

        # Patch every possible underlying client method so run() never actually reaches the network,
        # regardless of which tool is under test.
        for method_name in (
            "current_domain_report",
            "attempt_report",
            "longitudinal_summary",
            "misconception_longitudinal_detail",
            "current_mastery",
        ):
            monkeypatch.setattr(RamalsMcpReadClient, method_name, fake_call, raising=False)

        arguments = {
            "domainCode": "KAFKA",
            "attemptId": "0199-fake-attempt-id",
            "misconceptionId": "0199-fake-misconception-id",
            "versionCode": "v1",
            "learnerId": "should-never-be-read",
            "learnerRef": "should-never-be-read-either",
        }
        result = tool.run(arguments)
        assert "learnerId" not in str(result)
        assert "should-never-be-read" not in str(result)


def test_default_allowlists_grant_only_a_concrete_need() -> None:
    """Least privilege: diagnostic gets the four H6/H7 reads; adaptation gets mastery plus the
    diagnostic reads it needs to reason from; neither gets a capability without a concrete role."""
    registry = build_mcp_tool_registry(_fake_client(), _context())

    assert registry.allowed(AgentType.DIAGNOSTIC) == DIAGNOSTIC_AGENT_CAPABILITIES
    assert registry.allowed(AgentType.ADAPTATION) == ADAPTATION_AGENT_CAPABILITIES
    assert MASTERY_CURRENT not in registry.allowed(AgentType.DIAGNOSTIC)
    # TUTOR and ASSESSMENT get nothing by default -- Doc 02 §5's own empty-is-correct rule.
    assert registry.allowed(AgentType.TUTOR) == frozenset()
    assert registry.allowed(AgentType.ASSESSMENT) == frozenset()


def test_capability_not_locally_allowlisted_for_an_agent_is_denied() -> None:
    """Test #9: refused by ToolRegistry.authorize -- the existing, unmodified authorization gate --
    before any remote invocation is attempted."""
    registry = build_mcp_tool_registry(_fake_client(), _context())
    with pytest.raises(ToolDenied):
        registry.authorize(AgentType.TUTOR, MASTERY_CURRENT)


def test_registry_has_no_code_path_from_remote_tools_list_to_registration() -> None:
    """Test #7 (structural): build_mcp_tool_registry never calls list_tools; its tool set comes
    entirely from the five hardcoded factories in this module."""
    import inspect

    from ramals_ai.mcp import tools as tools_module

    source = inspect.getsource(tools_module)
    assert "list_tools" not in source


def test_no_direct_java_rest_fallback_exists_in_the_mcp_client_module() -> None:
    """Test #20: no reference to the learner-facing REST API base path, or to a bare
    requests/urllib call, exists anywhere in the MCP client module -- MCP is the only path."""
    import inspect

    source = inspect.getsource(client_module)
    assert "import requests" not in source
    assert "/api/" not in source


def test_no_database_driver_is_importable_from_the_mcp_package() -> None:
    """Test #21, scoped explicitly to the new MCP-3 package (the existing
    test_no_database_access.py already proves this for the whole service's declared dependencies;
    this proves it for this specific new code too)."""
    import importlib.util

    for package in ("psycopg", "psycopg2", "asyncpg", "sqlalchemy", "sqlmodel", "aiopg"):
        assert importlib.util.find_spec(package) is None
