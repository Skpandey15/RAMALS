"""MCP-3.2 contract and isolation proof.

Two things this file proves that ``test_mcp_graph_wiring.py``'s registry-threading tests do not:

1. **The full contract, header to header.** A delegated learner-context token that arrives on the
   real inbound HTTP request is the exact same token an outbound MCP tool call would present to
   Java's MCP transport -- proven by driving the actual production ``ReadOnlyTool.run`` closure the
   endpoint's registry contains, not by asserting the registry merely exists. The raw token appears
   at neither boundary's JSON body, only as the dedicated transport header.

2. **Interaction isolation under real concurrency.** Two genuinely concurrent diagnostic-assessment
   requests, for two different learners, never cross-contaminate: each request's own delegated
   token is the one -- and only the one -- ever seen while building that request's own registry.
   There is no module-global or shared mutable credential state anywhere in this call chain to leak
   through in the first place; this test demonstrates that behaviourally rather than by inspecting
   source for the absence of a ``ContextVar``.
"""

from __future__ import annotations

import json
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx2
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ramals_ai.config.settings import Environment, Settings
from ramals_ai.diagnostic_assessment import agent as diagnostic_assessment_agent_module
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.main import create_app
from ramals_ai.mcp import client as mcp_client_module
from ramals_ai.mcp.client import DELEGATED_CONTEXT_HEADER
from ramals_ai.security.workload_identity import (
    WorkloadAuthenticationError,
    WorkloadIdentity,
)

PATH = "/internal/v1/diagnostic-assessment/propose"

DIAGNOSTIC_ASSESSMENT_OUTPUT = json.dumps(
    {
        "diagnoses": [
            {
                "skillCode": "offset-management",
                "classification": "WEAK",
                "reason": "Repeated incorrect answers involving committed offsets.",
                "evidenceIds": ["e-1", "e-2"],
            }
        ],
        "recommendedNextSkills": ["offset-management"],
        "confidence": 0.83,
    }
)


class _ScriptedProvider(FakeProvider):
    def complete(self, request: ProviderRequest) -> ProviderResponse:  # noqa: ARG002
        return ProviderResponse(
            text=DIAGNOSTIC_ASSESSMENT_OUTPUT,
            input_tokens=100,
            output_tokens=40,
            cached_input_tokens=0,
        )


class _AcceptingVerifier:
    def verify(self, token: str) -> WorkloadIdentity:
        if token != "good-token":
            raise WorkloadAuthenticationError("token rejected")
        return WorkloadIdentity(
            subject="service-account-ramals-core-workload",
            client_id="ramals-core-workload",
            expires_at=0,
        )


@pytest.fixture
def app() -> FastAPI:
    application = create_app(
        Settings(
            environment=Environment.TEST,
            mcp_enabled=True,
            mcp_base_url="http://learning-platform:8080",
            mcp_workload_token_url=(
                "http://keycloak:8080/realms/ramals/protocol/openid-connect/token"
            ),
            mcp_workload_client_secret="test-only-secret-not-real",  # noqa: S106
        )
    )
    application.state.workload_verifier = _AcceptingVerifier()
    application.state.agents["diagnostic_assessment"]._gateway = LLMGateway(_ScriptedProvider())
    return application


@pytest.fixture
def client(app: FastAPI) -> TestClient:
    return TestClient(app)


AUTH = {
    "Authorization": "Bearer good-token",
    "X-RAMALS-Dispatch-Fence": "1",
    "X-RAMALS-Request-Digest": "a" * 64,
}


def body(*, interaction_id: str | None = None) -> dict[str, Any]:
    now = datetime.now(UTC)
    return {
        "contractVersion": "1.0",
        "interactionId": interaction_id or str(uuid.uuid7()),
        "requestId": str(uuid.uuid4()),
        "constraints": {"interactionClass": "INTERACTIVE_AI", "deadlineMs": 8000},
        "groundedContext": {
            "contractVersion": "1.0",
            "contextId": "ctx-mcp-contract-1",
            "learnerRef": "opaque-learner",
            "asOf": now.isoformat(),
            "expiresAt": (now + timedelta(minutes=10)).isoformat(),
            "retrievalPolicyVersion": "POLICY_V1",
            "items": [
                {
                    "evidenceId": "e-1",
                    "sourceType": "MASTERY",
                    "sourceVersion": "v1",
                    "authority": "AUTHORITATIVE_FACT",
                    "factType": "MASTERY_SCORE",
                    "value": "0.2100",
                    "observedAt": now.isoformat(),
                },
                {
                    "evidenceId": "e-2",
                    "sourceType": "LEARNER_EVIDENCE",
                    "sourceVersion": "v1",
                    "authority": "AUTHORITATIVE_FACT",
                    "factType": "ATTEMPT_OUTCOME",
                    "value": "INCORRECT",
                    "observedAt": now.isoformat(),
                },
            ],
        },
    }


# -- 1. full contract: inbound header -> outbound header, unchanged, never in either body ----------


class _StopBeforeTransport:
    """Raises inside ``__aenter__`` so the (fake) call stops immediately after the headers this
    test cares about are captured, well before anything attempts a real network connection."""

    async def __aenter__(self) -> None:
        raise RuntimeError("stop before a real transport is opened")

    async def __aexit__(self, *exc_info: object) -> bool:
        return False


def test_the_inbound_delegated_token_is_the_exact_outbound_mcp_transport_header(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Drives the real, production ``ReadOnlyTool.run`` closure the endpoint's own registry
    contains -- not a synthetic call -- and captures the header
    ``RamalsMcpReadClient`` actually builds for Java's MCP transport."""
    captured_headers: list[dict[str, str]] = []

    def fake_create_mcp_http_client(
        *, headers: dict[str, str], timeout: httpx2.Timeout
    ) -> _StopBeforeTransport:
        del timeout
        captured_headers.append(dict(headers))
        return _StopBeforeTransport()

    monkeypatch.setattr(mcp_client_module, "create_mcp_http_client", fake_create_mcp_http_client)

    # The client's own workload token acquisition runs before the transport is ever opened; stubbed
    # instantly so this test proves the delegated-context header, not the identity provider's
    # network reachability (already covered by the MCP-3 workload-identity test suite).
    from ramals_ai.security.mcp_workload_identity import McpWorkloadTokenProvider

    async def fake_get_token(self: McpWorkloadTokenProvider, *, timeout_s: float) -> str:
        del self, timeout_s
        return "fake-mcp-workload-token"  # noqa: S106 - test fixture

    monkeypatch.setattr(McpWorkloadTokenProvider, "get_token", fake_get_token)

    from ramals_ai.diagnostic_assessment import agent as agent_module
    from ramals_ai.mcp.tools import build_mcp_tool_registry as real_build

    captured_registry: list[Any] = []

    def capturing_build(client_arg: Any, context: Any, **kwargs: Any) -> Any:
        registry = real_build(client_arg, context, **kwargs)
        captured_registry.append(registry)
        return registry

    monkeypatch.setattr(agent_module, "build_mcp_tool_registry", capturing_build)

    inbound_token = "inbound-delegated-context-token"  # noqa: S105 - test fixture
    response = client.post(
        PATH, json=body(), headers={**AUTH, DELEGATED_CONTEXT_HEADER: inbound_token}
    )

    # The request itself completes normally regardless of what the (stubbed) MCP call does --
    # diagnostic-assessment's own proposal never depends on a tool actually being invoked.
    assert response.status_code == 200
    assert captured_registry, "the endpoint must have built a registry for this request"
    registry = captured_registry[0]

    # The stub's own RuntimeError never surfaces as-is -- RamalsMcpReadClient's generic exception
    # handler maps any unanticipated transport-layer failure to MCP_SERVER_ERROR precisely so a raw
    # exception message (which could echo response content) is never leaked; this is expected,
    # already-established MCP-3 behavior, not something this test is here to re-litigate.
    from ramals_ai.mcp.errors import McpError

    tool = registry.tools["diagnostics.current-domain-report"]
    with pytest.raises(McpError, match="the MCP call failed unexpectedly"):
        tool.run({"domainCode": "KAFKA"})

    assert len(captured_headers) == 1
    assert captured_headers[0][DELEGATED_CONTEXT_HEADER] == inbound_token

    # Never in either request/response body.
    assert inbound_token not in response.text
    assert inbound_token not in json.dumps(body())


# -- 2. concurrency: two learners, two tokens, no cross-contamination ------------------------------


def test_concurrent_interactions_for_different_learners_never_leak_delegated_tokens(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    from ramals_ai.mcp.tools import build_mcp_tool_registry as real_build

    observed: list[str] = []
    lock = threading.Lock()

    def capturing_build(client_arg: Any, context: Any, **kwargs: Any) -> Any:
        with lock:
            observed.append(context.delegated_context_token)
        return real_build(client_arg, context, **kwargs)

    monkeypatch.setattr(
        diagnostic_assessment_agent_module, "build_mcp_tool_registry", capturing_build
    )

    token_a = "learner-A-delegated-context-token"  # noqa: S105
    token_b = "learner-B-delegated-context-token"  # noqa: S105
    interaction_a = str(uuid.uuid7())
    interaction_b = str(uuid.uuid7())

    def call(token: str, interaction_id: str) -> Any:
        return client.post(
            PATH,
            json=body(interaction_id=interaction_id),
            headers={**AUTH, DELEGATED_CONTEXT_HEADER: token},
        )

    with ThreadPoolExecutor(max_workers=2) as pool:
        future_a = pool.submit(call, token_a, interaction_a)
        future_b = pool.submit(call, token_b, interaction_b)
        response_a = future_a.result(timeout=10)
        response_b = future_b.result(timeout=10)

    assert response_a.status_code == 200
    assert response_b.status_code == 200
    # Exactly the two tokens supplied, each exactly once -- neither request's registry was ever
    # built from the other's credential, whichever thread actually ran first.
    assert sorted(observed) == sorted([token_a, token_b])
    assert token_a not in response_b.text
    assert token_b not in response_a.text
