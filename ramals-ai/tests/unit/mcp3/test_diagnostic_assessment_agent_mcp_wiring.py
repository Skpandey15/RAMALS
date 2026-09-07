"""MCP-3.2: proves ``DiagnosticAssessmentAgent`` -- the real production agent class ``main.py``
constructs and ``api/internal.py`` calls for ``/internal/v1/diagnostic-assessment/propose`` --
builds and threads a live, interaction-scoped MCP-3 ``ToolRegistry`` into the real ``GraphRun`` it
constructs, exactly the same proof ``test_agent_mcp_registry_wiring.py`` already gives for
``DiagnosticAgent``/``AdaptationAgent``. A test that only called ``build_mcp_tool_registry()``
directly would not prove this -- see that file's own module docstring for the full rationale.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from ramals_ai.config.settings import ModelRoute, Settings
from ramals_ai.contracts.generated import AgentType
from ramals_ai.diagnostic_assessment import agent as diagnostic_assessment_agent_module
from ramals_ai.diagnostic_assessment.agent import DiagnosticAssessmentAgent
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.graph.runtime import GraphRun
from ramals_ai.graph.tools import ToolRegistry
from ramals_ai.grounding.contracts import GroundedContext
from ramals_ai.mcp.client import RamalsMcpReadClient
from ramals_ai.mcp.context import McpExecutionContext
from ramals_ai.mcp.tools import DIAGNOSTIC_AGENT_CAPABILITIES
from ramals_ai.security.mcp_workload_identity import McpWorkloadTokenProvider

_MCP_SETTINGS = Settings(
    mcp_enabled=True,
    mcp_base_url="http://learning-platform:8080",
    mcp_workload_token_url="http://keycloak:8080/realms/ramals/protocol/openid-connect/token",
    mcp_workload_client_secret="test-only-secret-not-real",  # noqa: S106 - test fixture
)


def _mcp_client() -> RamalsMcpReadClient:
    return RamalsMcpReadClient(_MCP_SETTINGS, McpWorkloadTokenProvider(_MCP_SETTINGS))


def _mcp_context() -> McpExecutionContext:
    return McpExecutionContext(
        delegated_context_token="test-delegated-context-token",  # noqa: S106
        interaction_id="01973b3a-0000-7000-8000-000000000001",
        deadline=Deadline.in_ms(30_000),
    )


def _grounded_context() -> GroundedContext:
    now = datetime.now(UTC)
    return GroundedContext.model_validate(
        {
            "contractVersion": "1.0",
            "contextId": "ctx-diagnostic-assessment-1",
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
        }
    )


WELL_FORMED = json.dumps(
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
            text=WELL_FORMED, input_tokens=100, output_tokens=40, cached_input_tokens=0
        )


class _CapturingGraphRun:
    """See ``test_agent_mcp_registry_wiring.py`` for the full rationale -- wraps the real
    ``GraphRun`` so the rest of ``propose()`` completes normally while the ``registry`` it was
    constructed with is captured for inspection."""

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


def test_diagnostic_assessment_agent_threads_a_live_registry_into_the_real_graph_run(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The real ``DiagnosticAssessmentAgent.propose`` -- exactly what ``api/internal.py`` calls --
    constructs ``GraphRun`` with a non-empty, correctly-scoped registry when a Java-shaped call
    arrives with a real MCP client and a delegated interaction context."""
    monkeypatch.setattr(diagnostic_assessment_agent_module, "GraphRun", _CapturingGraphRun)
    agent = DiagnosticAssessmentAgent(
        LLMGateway(_ScriptedProvider()), route=ModelRoute.CI_FAKE, mcp_client=_mcp_client()
    )

    agent.propose(
        _grounded_context(),
        interaction_id="interaction-1",
        request_id="request-1",
        deadline=Deadline.in_ms(8_000),
        mcp_execution_context=_mcp_context(),
    )

    assert _CapturingGraphRun.call_count == 1
    registry = _CapturingGraphRun.captured_registry
    assert registry is not None
    assert registry.allowed(AgentType.DIAGNOSTIC) == DIAGNOSTIC_AGENT_CAPABILITIES
    # No mastery.current: Java's own delegated capability policy for this path (MCP-3.1
    # AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES) never includes it.
    assert "mastery.current" not in registry.allowed(AgentType.DIAGNOSTIC)
    for capability in DIAGNOSTIC_AGENT_CAPABILITIES:
        registry.authorize(AgentType.DIAGNOSTIC, capability)  # must not raise ToolDenied


def test_diagnostic_assessment_agent_falls_back_to_no_registry_when_no_delegated_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A real MCP client configured, but no per-interaction context supplied: the agent degrades to
    ``GraphRun``'s own empty-registry default rather than failing the whole proposal -- exactly
    today's existing no-MCP behavior, preserved."""
    monkeypatch.setattr(diagnostic_assessment_agent_module, "GraphRun", _CapturingGraphRun)
    agent = DiagnosticAssessmentAgent(
        LLMGateway(_ScriptedProvider()), route=ModelRoute.CI_FAKE, mcp_client=_mcp_client()
    )

    envelope = agent.propose(
        _grounded_context(),
        interaction_id="interaction-1",
        request_id="request-1",
        deadline=Deadline.in_ms(8_000),
    )

    assert _CapturingGraphRun.captured_registry is None
    assert envelope.proposal is not None  # the proposal still completes normally


def test_diagnostic_assessment_agent_builds_no_registry_when_mcp_is_not_configured_at_all(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """No shared MCP client at all (``RAMALS_AI_MCP_ENABLED`` off, the default): the same safe
    degrade, even if a caller somehow supplied a context."""
    monkeypatch.setattr(diagnostic_assessment_agent_module, "GraphRun", _CapturingGraphRun)
    agent = DiagnosticAssessmentAgent(
        LLMGateway(_ScriptedProvider()), route=ModelRoute.CI_FAKE, mcp_client=None
    )

    agent.propose(
        _grounded_context(),
        interaction_id="interaction-1",
        request_id="request-1",
        deadline=Deadline.in_ms(8_000),
        mcp_execution_context=_mcp_context(),
    )

    assert _CapturingGraphRun.captured_registry is None
