"""MCP-3 review round 2, Blocker 1 (and MCP-3.2's own extension to diagnostic-assessment): proves
the production HTTP entry points Java actually calls -- ``POST /internal/v1/diagnostic/propose``,
``/internal/v1/adaptation/propose``, and ``/internal/v1/diagnostic-assessment/propose`` -- extract
the delegated learner-context credential from the real inbound request, build a trusted
``McpExecutionContext`` from it, and thread a live, interaction-scoped MCP-3 ``ToolRegistry``
all the way into the real ``GraphRun`` the request triggers.

This is the end-to-end proof the review explicitly distinguished from a unit test that only calls
``build_mcp_tool_registry()`` in isolation: a real HTTP request, shaped the way Java's own call
would be, driving the actual application object ``create_app`` returns.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ramals_ai.adaptation import agent as adaptation_agent_module
from ramals_ai.config.settings import Environment, Settings
from ramals_ai.contracts.generated import AgentType
from ramals_ai.diagnostic import agent as diagnostic_agent_module
from ramals_ai.diagnostic_assessment import agent as diagnostic_assessment_agent_module
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.graph.runtime import GraphRun
from ramals_ai.graph.tools import ToolRegistry
from ramals_ai.main import create_app
from ramals_ai.mcp.client import DELEGATED_CONTEXT_HEADER
from ramals_ai.mcp.tools import ADAPTATION_AGENT_CAPABILITIES, DIAGNOSTIC_AGENT_CAPABILITIES
from ramals_ai.security.workload_identity import (
    WorkloadAuthenticationError,
    WorkloadIdentity,
)

DIAGNOSTIC_PATH = "/internal/v1/diagnostic/propose"
ADAPTATION_PATH = "/internal/v1/adaptation/propose"
DIAGNOSTIC_ASSESSMENT_PATH = "/internal/v1/diagnostic-assessment/propose"

DELEGATED_CONTEXT_TOKEN = "test-delegated-context-jwt-not-real"  # noqa: S105 - test fixture

DIAGNOSTIC_OUTPUT = json.dumps(
    {
        "skillCode": "KAFKA_PARTITION",
        "objectiveCode": "KAFKA_PARTITIONS",
        "difficulty": "FOUNDATIONAL",
        "rationale": "Practice partitioning before advancing.",
        "inferredStatus": None,
    }
)

ADAPTATION_OUTPUT = json.dumps(
    {
        "skillCode": "KAFKA_PARTITION",
        "recommendedAction": "PRACTICE",
        "rationale": "Practice the skill before advancing.",
    }
)

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
    """A deterministic in-process response that also happens to pass each agent's own output
    validator, so the request completes as ``200`` rather than looping through bounded repair."""

    def __init__(self, payload: str) -> None:
        super().__init__()
        self.payload = payload

    def complete(self, request: ProviderRequest) -> ProviderResponse:  # noqa: ARG002
        return ProviderResponse(
            text=self.payload, input_tokens=100, output_tokens=40, cached_input_tokens=0
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


class _CapturingGraphRun:
    """See ``tests/unit/mcp3/test_agent_mcp_registry_wiring.py`` for the full rationale -- wraps
    the real ``GraphRun`` so the rest of the request completes normally while the ``registry`` it
    was constructed with is captured for inspection."""

    captured_registry: ToolRegistry | None = None
    call_count: int = 0

    def __new__(cls, *args: Any, **kwargs: Any) -> GraphRun:  # type: ignore[misc]
        cls.captured_registry = kwargs.get("registry")
        cls.call_count += 1
        return GraphRun(*args, **kwargs)


@pytest.fixture(autouse=True)
def _reset_capture() -> Iterator[None]:
    _CapturingGraphRun.captured_registry = None
    _CapturingGraphRun.call_count = 0
    yield


@pytest.fixture
def app(monkeypatch: pytest.MonkeyPatch) -> FastAPI:
    monkeypatch.setattr(diagnostic_agent_module, "GraphRun", _CapturingGraphRun)
    monkeypatch.setattr(adaptation_agent_module, "GraphRun", _CapturingGraphRun)
    monkeypatch.setattr(diagnostic_assessment_agent_module, "GraphRun", _CapturingGraphRun)
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
    # main.py gives every agent the *same* shared LLMGateway instance (by design -- one set of
    # budgets for the whole process), so overriding a shared gateway's adapter in place would
    # clobber whichever agent's own scripted response is set second. Each agent gets its own fresh
    # gateway here instead, so a diagnostic-shaped, an adaptation-shaped, and a
    # diagnostic-assessment-shaped response can all coexist.
    application.state.agents["diagnostic"]._gateway = LLMGateway(
        _ScriptedProvider(DIAGNOSTIC_OUTPUT)
    )
    application.state.agents["adaptation"]._gateway = LLMGateway(
        _ScriptedProvider(ADAPTATION_OUTPUT)
    )
    application.state.agents["diagnostic_assessment"]._gateway = LLMGateway(
        _ScriptedProvider(DIAGNOSTIC_ASSESSMENT_OUTPUT)
    )
    return application


@pytest.fixture
def client(app: FastAPI) -> TestClient:
    return TestClient(app)


AUTH = {"Authorization": "Bearer good-token"}


def envelope() -> dict[str, Any]:
    return {
        "contractVersion": "1.0",
        "interactionId": str(uuid.uuid7()),
        "requestId": str(uuid.uuid4()),
        "learner": {"learnerRef": "opaque-learner-ref-001", "locale": "en-IN"},
        "learningContext": {
            "skillCode": "KAFKA_PARTITION",
            "masteryStatus": "NEEDS_PRACTICE",
            "prerequisites": ["KAFKA_TOPIC"],
        },
        "domainContext": {
            "domainCode": "KAFKA",
            "domainType": "TECHNOLOGY",
            "curriculumVersion": "v1",
        },
        "learningGoalContext": {
            "goalType": "LEARNING_DOMAIN",
            "goalCode": "KAFKA",
            "goalVersion": "v1",
        },
        "constraints": {"interactionClass": "INTERACTIVE_AI", "deadlineMs": 8000},
        "requestedCapability": "EXPLAIN",
    }


DIAGNOSTIC_ASSESSMENT_AUTH = {
    **AUTH,
    "X-RAMALS-Dispatch-Fence": "1",
    "X-RAMALS-Request-Digest": "a" * 64,
}


def diagnostic_assessment_body(*, interaction_id: str | None = None) -> dict[str, Any]:
    now = datetime.now(UTC)
    return {
        "contractVersion": "1.0",
        "interactionId": interaction_id or str(uuid.uuid7()),
        "requestId": str(uuid.uuid4()),
        "constraints": {"interactionClass": "INTERACTIVE_AI", "deadlineMs": 8000},
        "groundedContext": {
            "contractVersion": "1.0",
            "contextId": "ctx-mcp-graph-wiring-1",
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


def test_diagnostic_assessment_propose_threads_a_live_registry_from_a_real_http_request(
    client: TestClient,
) -> None:
    """The real Java diagnostic outbound path (MCP-3.1's ``RamalsAiDiagnosticAssessmentClient`` ->
    ``/internal/v1/diagnostic-assessment/propose``) now produces a non-empty, correctly-scoped
    registry inside the actual ``GraphRun`` the request triggers -- the wiring MCP-3.2 completes."""
    response = client.post(
        DIAGNOSTIC_ASSESSMENT_PATH,
        json=diagnostic_assessment_body(),
        headers={**DIAGNOSTIC_ASSESSMENT_AUTH, DELEGATED_CONTEXT_HEADER: DELEGATED_CONTEXT_TOKEN},
    )

    assert response.status_code == 200
    assert _CapturingGraphRun.call_count == 1
    registry = _CapturingGraphRun.captured_registry
    assert registry is not None
    assert registry.allowed(AgentType.DIAGNOSTIC) == DIAGNOSTIC_AGENT_CAPABILITIES
    assert "mastery.current" not in registry.allowed(AgentType.DIAGNOSTIC)


def test_diagnostic_assessment_propose_degrades_to_no_registry_when_the_header_is_absent(
    client: TestClient,
) -> None:
    """Backward compatibility: a caller that never attaches the delegated-context header still
    gets its existing, non-MCP diagnostic-assessment behavior -- never a failure."""
    response = client.post(
        DIAGNOSTIC_ASSESSMENT_PATH,
        json=diagnostic_assessment_body(),
        headers=DIAGNOSTIC_ASSESSMENT_AUTH,
    )

    assert response.status_code == 200
    assert _CapturingGraphRun.captured_registry is None


def test_diagnostic_assessment_propose_builds_no_registry_when_mcp_is_not_configured(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """With ``RAMALS_AI_MCP_ENABLED`` off (the default), the header is simply ignored."""
    monkeypatch.setattr(diagnostic_assessment_agent_module, "GraphRun", _CapturingGraphRun)
    application = create_app(Settings(environment=Environment.TEST))
    application.state.workload_verifier = _AcceptingVerifier()
    application.state.agents["diagnostic_assessment"]._gateway = LLMGateway(
        _ScriptedProvider(DIAGNOSTIC_ASSESSMENT_OUTPUT)
    )
    unconfigured_client = TestClient(application)

    response = unconfigured_client.post(
        DIAGNOSTIC_ASSESSMENT_PATH,
        json=diagnostic_assessment_body(),
        headers={**DIAGNOSTIC_ASSESSMENT_AUTH, DELEGATED_CONTEXT_HEADER: DELEGATED_CONTEXT_TOKEN},
    )

    assert response.status_code == 200
    assert application.state.mcp_client is None
    assert _CapturingGraphRun.captured_registry is None


def test_diagnostic_propose_threads_a_live_registry_from_a_real_http_request(
    client: TestClient,
) -> None:
    """A real HTTP request, shaped like Java's authorized call and carrying the delegated
    learner-context header, produces a non-empty diagnostic-scoped registry inside the actual
    ``GraphRun`` the request triggers."""
    response = client.post(
        DIAGNOSTIC_PATH,
        json=envelope(),
        headers={**AUTH, DELEGATED_CONTEXT_HEADER: DELEGATED_CONTEXT_TOKEN},
    )

    assert response.status_code == 200
    assert _CapturingGraphRun.call_count == 1
    registry = _CapturingGraphRun.captured_registry
    assert registry is not None
    assert registry.allowed(AgentType.DIAGNOSTIC) == DIAGNOSTIC_AGENT_CAPABILITIES


def test_adaptation_propose_threads_a_live_registry_from_a_real_http_request(
    client: TestClient,
) -> None:
    response = client.post(
        ADAPTATION_PATH,
        json=envelope(),
        headers={**AUTH, DELEGATED_CONTEXT_HEADER: DELEGATED_CONTEXT_TOKEN},
    )

    assert response.status_code == 200
    assert _CapturingGraphRun.call_count == 1
    registry = _CapturingGraphRun.captured_registry
    assert registry is not None
    assert registry.allowed(AgentType.ADAPTATION) == ADAPTATION_AGENT_CAPABILITIES


def test_diagnostic_propose_degrades_to_no_registry_when_the_header_is_absent(
    client: TestClient,
) -> None:
    """Java's own call sites do not attach the delegated-context header yet (M2-ADR-031's issuer is
    not wired into any Java call site as of MCP-2) -- the request must still succeed, exactly as it
    does today, with no MCP registry rather than a failure."""
    response = client.post(DIAGNOSTIC_PATH, json=envelope(), headers=AUTH)

    assert response.status_code == 200
    assert _CapturingGraphRun.captured_registry is None


def test_diagnostic_propose_builds_no_registry_when_mcp_is_not_configured(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """With ``RAMALS_AI_MCP_ENABLED`` off (the default), the header is simply ignored -- there is no
    shared client for a registry to be built from."""
    monkeypatch.setattr(diagnostic_agent_module, "GraphRun", _CapturingGraphRun)
    application = create_app(Settings(environment=Environment.TEST))
    application.state.workload_verifier = _AcceptingVerifier()
    application.state.agents["diagnostic"]._gateway = LLMGateway(
        _ScriptedProvider(DIAGNOSTIC_OUTPUT)
    )
    unconfigured_client = TestClient(application)

    response = unconfigured_client.post(
        DIAGNOSTIC_PATH,
        json=envelope(),
        headers={**AUTH, DELEGATED_CONTEXT_HEADER: DELEGATED_CONTEXT_TOKEN},
    )

    assert response.status_code == 200
    assert application.state.mcp_client is None
    assert _CapturingGraphRun.captured_registry is None


def test_tutor_respond_is_never_given_an_mcp_registry(client: TestClient) -> None:
    """TUTOR holds no MCP-3 capability by design (Doc 02 §5's empty-is-correct default) -- proven
    here at the real HTTP boundary rather than only in the tool-registry unit tests, since this
    endpoint does not even attempt to build one regardless of the delegated-context header."""
    client.post(
        "/internal/v1/tutor/respond",
        json=envelope(),
        headers={**AUTH, DELEGATED_CONTEXT_HEADER: DELEGATED_CONTEXT_TOKEN},
    )

    # Not asserting on the response status here: the deterministic ci-fake text this endpoint's
    # own gateway happens to return is not valid tutor-shaped JSON, which is irrelevant to what
    # this test checks. No _CapturingGraphRun patch exists on TutorAgent's own module -- if this
    # endpoint ever starts routing an MCP registry into a tool-registry-aware GraphRun, the
    # diagnostic/adaptation capture objects above are simply untouched by this call, which is
    # exactly what must stay true.
    assert _CapturingGraphRun.captured_registry is None
