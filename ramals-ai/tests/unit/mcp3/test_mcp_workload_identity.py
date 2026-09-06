"""MCP-3 review requirements #1-#3: the reverse workload token provider requests the correct
client identity, targets the correct audience, and never touches ramals-core-workload's own secret.
"""

from __future__ import annotations

from typing import Any

import httpx2
import pytest

from ramals_ai.config.settings import Settings
from ramals_ai.mcp.errors import McpError, McpErrorCode
from ramals_ai.security.mcp_workload_identity import McpWorkloadTokenProvider

_MCP_SETTINGS = Settings(
    mcp_enabled=True,
    mcp_base_url="http://learning-platform:8080",
    mcp_workload_token_url="http://keycloak:8080/realms/ramals/protocol/openid-connect/token",
    mcp_workload_client_secret="test-only-secret-not-real",  # noqa: S106 - test fixture
)


@pytest.fixture
def captured_requests() -> list[httpx2.Request]:
    return []


def _fake_transport(captured: list[httpx2.Request]) -> httpx2.MockTransport:
    def handler(request: httpx2.Request) -> httpx2.Response:
        captured.append(request)
        return httpx2.Response(
            200, json={"access_token": "fake-mcp-workload-token", "expires_in": 300}
        )

    return httpx2.MockTransport(handler)


@pytest.mark.anyio
async def test_token_provider_requests_ramals_ai_workload_client_identity(
    monkeypatch: pytest.MonkeyPatch, captured_requests: list[httpx2.Request]
) -> None:
    """Test #1."""
    transport = _fake_transport(captured_requests)
    _patch_async_client(monkeypatch, transport)

    provider = McpWorkloadTokenProvider(_MCP_SETTINGS)
    await provider.get_token(timeout_s=10.0)

    assert len(captured_requests) == 1
    body = captured_requests[0].content.decode()
    assert "client_id=ramals-ai-workload" in body


@pytest.mark.anyio
async def test_token_provider_targets_ramals_mcp_audience(
    monkeypatch: pytest.MonkeyPatch, captured_requests: list[httpx2.Request]
) -> None:
    """Test #2."""
    transport = _fake_transport(captured_requests)
    _patch_async_client(monkeypatch, transport)

    provider = McpWorkloadTokenProvider(_MCP_SETTINGS)
    await provider.get_token(timeout_s=10.0)

    body = captured_requests[0].content.decode()
    assert "audience=ramals-mcp" in body


@pytest.mark.anyio
async def test_token_provider_never_references_ramals_core_workload(
    monkeypatch: pytest.MonkeyPatch, captured_requests: list[httpx2.Request]
) -> None:
    """Test #3: the reverse-direction provider never uses M1-ADR-003's own Java-to-ramals-ai
    credential -- neither its client id nor any secret configured for it exists anywhere this
    provider can reach."""
    transport = _fake_transport(captured_requests)
    _patch_async_client(monkeypatch, transport)

    provider = McpWorkloadTokenProvider(_MCP_SETTINGS)
    await provider.get_token(timeout_s=10.0)

    body = captured_requests[0].content.decode()
    assert "ramals-core-workload" not in body
    # Settings itself carries no field this provider could even read to obtain that credential.
    assert not hasattr(_MCP_SETTINGS, "core_workload_client_secret")


@pytest.mark.anyio
async def test_disabled_mcp_raises_before_any_request(
    captured_requests: list[httpx2.Request],
) -> None:
    disabled = Settings(mcp_enabled=False)
    with pytest.raises(McpError) as failure:
        McpWorkloadTokenProvider(disabled)
    assert failure.value.code is McpErrorCode.MCP_DISABLED
    assert captured_requests == []


@pytest.mark.anyio
async def test_token_is_cached_across_calls(
    monkeypatch: pytest.MonkeyPatch, captured_requests: list[httpx2.Request]
) -> None:
    transport = _fake_transport(captured_requests)
    _patch_async_client(monkeypatch, transport)

    provider = McpWorkloadTokenProvider(_MCP_SETTINGS)
    first = await provider.get_token(timeout_s=10.0)
    second = await provider.get_token(timeout_s=10.0)

    assert first == second == "fake-mcp-workload-token"
    assert len(captured_requests) == 1  # only one real request behind two calls


@pytest.mark.anyio
async def test_token_acquisition_failure_never_leaks_url_or_secret(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def handler(_request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(500, text="internal keycloak error")

    _patch_async_client(monkeypatch, httpx2.MockTransport(handler))

    provider = McpWorkloadTokenProvider(_MCP_SETTINGS)
    with pytest.raises(McpError) as failure:
        await provider.get_token(timeout_s=10.0)

    assert failure.value.code is McpErrorCode.MCP_WORKLOAD_TOKEN_ACQUISITION_FAILED
    message = str(failure.value)
    secret = _MCP_SETTINGS.mcp_workload_client_secret
    assert secret is not None
    assert secret not in message
    assert _MCP_SETTINGS.mcp_workload_token_url not in message


def _patch_async_client(
    monkeypatch: pytest.MonkeyPatch,
    transport: httpx2.MockTransport,
    *,
    captured_kwargs: list[dict[str, Any]] | None = None,
) -> None:
    original_init = httpx2.AsyncClient.__init__

    def patched_init(self: httpx2.AsyncClient, *args: Any, **kwargs: Any) -> None:
        if captured_kwargs is not None:
            captured_kwargs.append(dict(kwargs))
        kwargs["transport"] = transport
        original_init(self, *args, **kwargs)

    monkeypatch.setattr(httpx2.AsyncClient, "__init__", patched_init)


# -- MCP-3 review round 2, Blocker 2: token acquisition must respect the caller's deadline ---------


@pytest.mark.anyio
async def test_cached_token_path_makes_no_network_request_regardless_of_timeout(
    monkeypatch: pytest.MonkeyPatch, captured_requests: list[httpx2.Request]
) -> None:
    """Review test #1: a cache hit costs no token-endpoint call -- proven here with a deliberately
    tiny ``timeout_s`` that a real fetch could not possibly complete within, to show the fast path
    never even looks at it."""
    transport = _fake_transport(captured_requests)
    _patch_async_client(monkeypatch, transport)

    provider = McpWorkloadTokenProvider(_MCP_SETTINGS)
    await provider.get_token(timeout_s=10.0)  # populates the cache
    cached = await provider.get_token(timeout_s=0.001)  # would fail closed if this reached _fetch

    assert cached == "fake-mcp-workload-token"
    assert len(captured_requests) == 1  # still just the one real request, from the first call


@pytest.mark.anyio
async def test_real_acquisition_is_bounded_by_the_callers_remaining_timeout_not_a_fixed_value(
    monkeypatch: pytest.MonkeyPatch, captured_requests: list[httpx2.Request]
) -> None:
    """Review test #2: with 100 ms remaining on the interaction, a real (uncached) acquisition must
    be given a ~100 ms transport timeout, never the old fixed 10-second one -- proven by capturing
    the actual ``httpx2.Timeout`` the provider builds its client with."""
    transport = _fake_transport(captured_requests)
    captured_kwargs: list[dict[str, Any]] = []
    _patch_async_client(monkeypatch, transport, captured_kwargs=captured_kwargs)

    provider = McpWorkloadTokenProvider(_MCP_SETTINGS)
    await provider.get_token(timeout_s=0.1)

    assert len(captured_kwargs) == 1
    timeout = captured_kwargs[0]["timeout"]
    assert isinstance(timeout, httpx2.Timeout)
    # httpx2.Timeout normalizes a single positional value onto every leg (connect/read/write/pool).
    assert timeout.read == pytest.approx(0.1)
    assert timeout.read != pytest.approx(10.0)


@pytest.mark.anyio
async def test_zero_or_negative_remaining_timeout_fails_closed_before_any_network_call(
    captured_requests: list[httpx2.Request],
) -> None:
    """Review tests #3/#4 (provider half): once the caller's own remaining budget reaches zero,
    acquisition must fail immediately with ``MCP_DEADLINE_EXCEEDED`` -- never attempt the network
    call, and never be given ``httpx2.Timeout(0)``, which httpx reads as *no* timeout rather than
    *no time left*. Uses a fresh, never-yet-cached provider, so the only way this could reach the
    network is if the guard were missing."""
    provider = McpWorkloadTokenProvider(_MCP_SETTINGS)

    with pytest.raises(McpError) as failure:
        await provider.get_token(timeout_s=0.0)
    assert failure.value.code is McpErrorCode.MCP_DEADLINE_EXCEEDED

    with pytest.raises(McpError) as failure:
        await provider.get_token(timeout_s=-5.0)
    assert failure.value.code is McpErrorCode.MCP_DEADLINE_EXCEEDED

    assert captured_requests == []  # neither attempt reached a transport at all


@pytest.mark.anyio
async def test_token_endpoint_failure_is_not_retried(
    monkeypatch: pytest.MonkeyPatch, captured_requests: list[httpx2.Request]
) -> None:
    """Review test #6: exactly one request reaches the token endpoint per call; a failure there is
    reported, never retried by this provider itself (bounded retry, if any, is the caller's own
    policy, and only for safe transport-level failures -- never an auth/token failure)."""

    def handler(request: httpx2.Request) -> httpx2.Response:
        captured_requests.append(request)
        return httpx2.Response(500, text="internal keycloak error")

    _patch_async_client(monkeypatch, httpx2.MockTransport(handler))

    provider = McpWorkloadTokenProvider(_MCP_SETTINGS)
    with pytest.raises(McpError):
        await provider.get_token(timeout_s=10.0)

    assert len(captured_requests) == 1
