"""MCP-3 review requirements: capability allowlist, deadline enforcement, denial-reason mapping,
and result-schema validation, exercised directly against ``RamalsMcpReadClient``'s own logic."""

from __future__ import annotations

import time

import pytest
from mcp import types

from ramals_ai.config.settings import Settings
from ramals_ai.gateway.budget import Deadline
from ramals_ai.mcp.client import RamalsMcpReadClient
from ramals_ai.mcp.context import McpExecutionContext
from ramals_ai.mcp.errors import McpError, McpErrorCode
from ramals_ai.security.mcp_workload_identity import McpWorkloadTokenProvider

_MCP_SETTINGS = Settings(
    mcp_enabled=True,
    mcp_base_url="http://learning-platform:8080",
    mcp_workload_token_url="http://keycloak:8080/realms/ramals/protocol/openid-connect/token",
    mcp_workload_client_secret="test-only-secret-not-real",  # noqa: S106 - test fixture
)


def _client() -> RamalsMcpReadClient:
    return RamalsMcpReadClient(_MCP_SETTINGS, McpWorkloadTokenProvider(_MCP_SETTINGS))


def _context(
    *,
    remaining_ms: int = 30_000,
    token: str = "test-delegated-context-token",  # noqa: S107 - test fixture default
) -> McpExecutionContext:
    return McpExecutionContext(
        delegated_context_token=token,
        interaction_id="01973b3a-0000-7000-8000-000000000001",
        deadline=Deadline.in_ms(remaining_ms),
    )


def _text_result(reason: str, *, is_error: bool = True) -> types.CallToolResult:
    return types.CallToolResult(
        content=[types.TextContent(type="text", text=reason)], is_error=is_error
    )


# -- allowlist (test #9 partial, #7) ---------------------------------------------------------------


def test_disabled_mcp_raises_on_construction() -> None:
    with pytest.raises(McpError) as failure:
        RamalsMcpReadClient(Settings(mcp_enabled=False), token_provider=None)  # type: ignore[arg-type]
    assert failure.value.code is McpErrorCode.MCP_DISABLED


@pytest.mark.anyio
async def test_capability_not_in_local_allowlist_is_rejected_before_any_transport_call() -> None:
    """Test #9: a capability the local allowlist does not recognize fails closed before any remote
    call is attempted -- proven here by calling the internal dispatch directly with an
    unrecognized name; no transport object is even constructed."""
    client = _client()
    with pytest.raises(McpError) as failure:
        await client._call(_context(), "diagnostics.request-probe", {})
    assert failure.value.code is McpErrorCode.MCP_CAPABILITY_NOT_ALLOWLISTED


def test_exactly_five_capabilities_are_allowlisted() -> None:
    """Test #6."""
    from ramals_ai.mcp.client import ALLOWLISTED_CAPABILITIES

    assert {
        "diagnostics.current-domain-report",
        "diagnostics.attempt-report",
        "diagnostics.longitudinal-summary",
        "diagnostics.misconception-longitudinal-detail",
        "mastery.current",
    } == ALLOWLISTED_CAPABILITIES


def test_remote_tool_list_response_cannot_widen_the_local_allowlist() -> None:
    """Test #7: the allowlist is a hardcoded, module-level literal -- there is no code path from a
    remote `tools/list` result to this set at all, so a server advertising a sixth tool changes
    nothing about what this client will ever invoke."""
    import inspect

    from ramals_ai.mcp import client as client_module

    source = inspect.getsource(client_module)
    # No reference to a tools/list result feeding the allowlist anywhere in this module.
    assert "list_tools" not in source


# -- deadline (test #13) ---------------------------------------------------------------------------


@pytest.mark.anyio
async def test_call_fails_closed_when_deadline_already_expired() -> None:
    """Test #13."""
    client = _client()
    expired_context = _context(remaining_ms=1)
    time.sleep(0.01)  # let the 1ms deadline actually elapse

    with pytest.raises(McpError) as failure:
        await client._call(
            expired_context, "mastery.current", {"domainCode": "KAFKA", "versionCode": "v1"}
        )

    assert failure.value.code is McpErrorCode.MCP_DEADLINE_EXCEEDED


# -- denial-reason mapping (tests #10, #11, #12) ---------------------------------------------------


def test_missing_delegated_context_reason_maps_correctly() -> None:
    """Test #10."""
    client = _client()
    code = client._map_denial(_text_result("MISSING"))
    assert code is McpErrorCode.MCP_DELEGATED_CONTEXT_MISSING


@pytest.mark.parametrize("reason", ["EXPIRED", "WRONG_AUDIENCE", "BAD_SIGNATURE", "WRONG_ISSUER"])
def test_rejected_delegated_context_reasons_map_correctly(reason: str) -> None:
    """Test #11: expired/rejected delegated context propagates as a governed failure, distinctly
    from a missing one."""
    client = _client()
    code = client._map_denial(_text_result(reason))
    assert code is McpErrorCode.MCP_DELEGATED_CONTEXT_REJECTED


def test_domain_mismatch_reason_maps_correctly() -> None:
    """Test #12: wrong domain fails, distinctly, without any fallback."""
    client = _client()
    code = client._map_denial(_text_result("DOMAIN_MISMATCH"))
    assert code is McpErrorCode.MCP_DOMAIN_MISMATCH


@pytest.mark.parametrize(
    "reason",
    [
        "CAPABILITY_NOT_DELEGATED",
        "CAPABILITY_NOT_REGISTERED",
        "ATTEMPT_NOT_OWNED",
        "MISCONCEPTION_NOT_ACCESSIBLE",
    ],
)
def test_capability_denial_reasons_map_correctly(reason: str) -> None:
    client = _client()
    code = client._map_denial(_text_result(reason))
    assert code is McpErrorCode.MCP_CAPABILITY_DENIED


def test_malformed_request_reason_maps_correctly() -> None:
    client = _client()
    code = client._map_denial(_text_result("MALFORMED_REQUEST"))
    assert code is McpErrorCode.MCP_MALFORMED_INPUT


def test_unrecognized_reason_falls_back_to_server_error_never_to_success() -> None:
    client = _client()
    code = client._map_denial(_text_result("SOME_FUTURE_REASON_THIS_CLIENT_DOES_NOT_KNOW"))
    assert code is McpErrorCode.MCP_SERVER_ERROR


# -- malformed/invalid payload (test #16)


def test_missing_structured_content_fails_schema_validation() -> None:
    """Test #16 (partial): no structured content at all is an invalid payload, not a silently empty
    result."""
    from ramals_ai.mcp.models import McpMasteryReport

    client = _client()
    result = types.CallToolResult(content=[], structured_content=None, is_error=False)
    with pytest.raises(McpError) as failure:
        client._parse("mastery.current", result, McpMasteryReport)
    assert failure.value.code is McpErrorCode.MCP_INVALID_PAYLOAD


def test_malformed_structured_content_fails_result_schema_validation() -> None:
    """Test #16."""
    from ramals_ai.mcp.models import McpMasteryReport

    client = _client()
    result = types.CallToolResult(
        content=[], structured_content={"totally": "unexpected shape"}, is_error=False
    )
    with pytest.raises(McpError) as failure:
        client._parse("mastery.current", result, McpMasteryReport)
    assert failure.value.code is McpErrorCode.MCP_RESULT_SCHEMA_MISMATCH


# -- credentials never in logs/errors (test #18)


@pytest.mark.anyio
async def test_deadline_exceeded_error_never_contains_the_delegated_context_token() -> None:
    """Test #18 (partial)."""
    client = _client()
    secret_token = "extremely-secret-delegated-context-value"  # noqa: S105
    context = _context(remaining_ms=1, token=secret_token)
    time.sleep(0.01)

    with pytest.raises(McpError) as failure:
        await client._call(context, "mastery.current", {"domainCode": "KAFKA", "versionCode": "v1"})

    assert secret_token not in str(failure.value)
