"""MCP-3 review requirements: capability allowlist, deadline enforcement, denial-reason mapping,
and result-schema validation, exercised directly against ``RamalsMcpReadClient``'s own logic."""

from __future__ import annotations

import time
from unittest.mock import AsyncMock

import httpx2
import pytest
from mcp import types

from ramals_ai.config.settings import Settings
from ramals_ai.gateway.budget import Deadline
from ramals_ai.mcp import client as client_module
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


def _client_with_stub_token_provider(
    token: str = "fixed-mcp-workload-token",  # noqa: S107 - test fixture default
) -> RamalsMcpReadClient:
    """A client whose token provider never touches the network -- an ``AsyncMock`` returning a
    fixed token instantly, so these tests isolate ``RamalsMcpReadClient._call``'s own
    deadline-recompute logic from real token-endpoint timing entirely."""
    client = RamalsMcpReadClient.__new__(RamalsMcpReadClient)  # bypass __init__'s mcp_enabled check
    client._url = _MCP_SETTINGS.mcp_base_url.rstrip("/") + "/mcp"
    client._token_provider = AsyncMock()
    client._token_provider.get_token = AsyncMock(return_value=token)
    return client


class _SteppedClock:
    """A ``Deadline`` clock that returns each given value in order, then repeats the last.

    Deterministic without real sleeping: ``Deadline.in_ms`` reads the clock once to compute
    ``expires_at``, and ``remaining_ms()`` reads it once per call thereafter -- so the sequence
    given here controls exactly what each successive deadline check sees, independent of how long
    the (stubbed, instant) work between checks actually took in real wall-clock time.
    """

    def __init__(self, *values: float) -> None:
        self._values = list(values)
        self._index = 0

    def __call__(self) -> float:
        value = self._values[min(self._index, len(self._values) - 1)]
        self._index += 1
        return value


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


# -- MCP-3 review round 2, Blocker 2: token acquisition must respect the caller's deadline --------
#
# Token acquisition is part of the MCP invocation's own budget, not a side channel exempt from it.
# These prove the caller's remaining deadline is recomputed *after* acquiring a token -- never
# assumed unchanged from before the fetch -- and that the transport is never opened once that
# recheck finds nothing left.


@pytest.mark.anyio
async def test_deadline_expiring_during_token_acquisition_raises_deadline_exceeded() -> None:
    """Test #3: the deadline can lapse *during* acquisition, not only before or after it -- proven
    with a clock that reports the deadline as still open for the pre-token check and expired for
    the post-token recheck immediately afterward, regardless of how little real wall-clock time the
    stubbed (instant) token fetch actually took."""
    clock = _SteppedClock(0.0, 0.0, 0.5)  # [Deadline.in_ms's own read, pre-token, post-token]
    deadline = Deadline.in_ms(100, clock=clock)
    context = McpExecutionContext(
        delegated_context_token="test-token",  # noqa: S106
        interaction_id="01973b3a-0000-7000-8000-000000000001",
        deadline=deadline,
    )
    client = _client_with_stub_token_provider()

    with pytest.raises(McpError) as failure:
        await client._call(context, "mastery.current", {"domainCode": "KAFKA", "versionCode": "v1"})

    assert failure.value.code is McpErrorCode.MCP_DEADLINE_EXCEEDED


@pytest.mark.anyio
async def test_authoritative_mcp_transport_is_never_opened_once_deadline_expires_post_token(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Test #4: once the post-token recheck finds the deadline expired, the MCP transport must
    never be opened at all -- proven by making its own entry point raise if it is ever reached."""

    def _must_not_be_called(*_args: object, **_kwargs: object) -> None:
        raise AssertionError("MCP transport was opened after the deadline had already expired")

    monkeypatch.setattr(client_module, "create_mcp_http_client", _must_not_be_called)

    clock = _SteppedClock(0.0, 0.0, 0.5)
    deadline = Deadline.in_ms(100, clock=clock)
    context = McpExecutionContext(
        delegated_context_token="test-token",  # noqa: S106
        interaction_id="01973b3a-0000-7000-8000-000000000001",
        deadline=deadline,
    )
    client = _client_with_stub_token_provider()

    with pytest.raises(McpError) as failure:
        await client._call(context, "mastery.current", {"domainCode": "KAFKA", "versionCode": "v1"})

    assert failure.value.code is McpErrorCode.MCP_DEADLINE_EXCEEDED


@pytest.mark.anyio
async def test_transport_timeout_uses_the_recomputed_post_token_remaining_budget(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Test #5: the timeout the MCP transport is opened with reflects what remained *after* token
    acquisition, never the figure computed before it -- proven by reading back the actual
    ``httpx2.Timeout`` the transport layer was invoked with."""
    captured_timeouts: list[httpx2.Timeout] = []

    class _StopBeforeTransport:
        async def __aenter__(self) -> None:
            raise RuntimeError("stop before a real transport is opened")

        async def __aexit__(self, *exc_info: object) -> bool:
            return False

    def fake_create_mcp_http_client(
        *, headers: dict[str, str], timeout: httpx2.Timeout
    ) -> _StopBeforeTransport:
        del headers
        captured_timeouts.append(timeout)
        return _StopBeforeTransport()

    monkeypatch.setattr(client_module, "create_mcp_http_client", fake_create_mcp_http_client)

    # 100 ms remained at the pre-token check; only 80 ms remains by the post-token recheck.
    clock = _SteppedClock(0.0, 0.0, 0.02)
    deadline = Deadline.in_ms(100, clock=clock)
    context = McpExecutionContext(
        delegated_context_token="test-token",  # noqa: S106
        interaction_id="01973b3a-0000-7000-8000-000000000001",
        deadline=deadline,
    )
    client = _client_with_stub_token_provider()

    with pytest.raises(McpError):
        await client._call(context, "mastery.current", {"domainCode": "KAFKA", "versionCode": "v1"})

    assert len(captured_timeouts) == 1
    assert captured_timeouts[0].read == pytest.approx(0.08, abs=0.001)
    assert captured_timeouts[0].read != pytest.approx(0.1, abs=0.001)


@pytest.mark.anyio
async def test_client_does_not_retry_a_token_acquisition_failure() -> None:
    """Test #6 (client half; see also test_mcp_workload_identity.py's provider-level version):
    ``_call`` makes exactly one token-acquisition attempt and propagates its failure immediately --
    no internal retry loop exists that could obscure the caller's own bounded-retry policy."""
    client = _client_with_stub_token_provider()
    client._token_provider.get_token = AsyncMock(  # type: ignore[method-assign]
        side_effect=McpError(McpErrorCode.MCP_WORKLOAD_TOKEN_ACQUISITION_FAILED, "boom")
    )

    with pytest.raises(McpError) as failure:
        await client._call(
            _context(), "mastery.current", {"domainCode": "KAFKA", "versionCode": "v1"}
        )

    assert failure.value.code is McpErrorCode.MCP_WORKLOAD_TOKEN_ACQUISITION_FAILED
    assert client._token_provider.get_token.await_count == 1


@pytest.mark.anyio
async def test_post_token_deadline_exceeded_error_never_contains_the_delegated_context_token() -> (
    None
):
    """Test #7: the new post-token-acquisition deadline-exceeded path carries no secret either."""
    secret_token = "extremely-secret-delegated-context-value-2"  # noqa: S105
    clock = _SteppedClock(0.0, 0.0, 0.5)
    deadline = Deadline.in_ms(100, clock=clock)
    context = McpExecutionContext(
        delegated_context_token=secret_token,
        interaction_id="01973b3a-0000-7000-8000-000000000001",
        deadline=deadline,
    )
    client = _client_with_stub_token_provider()

    with pytest.raises(McpError) as failure:
        await client._call(context, "mastery.current", {"domainCode": "KAFKA", "versionCode": "v1"})

    assert secret_token not in str(failure.value)
