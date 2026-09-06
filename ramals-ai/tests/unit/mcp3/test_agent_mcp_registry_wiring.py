"""MCP-3 review round 2, Blocker 1: proves ``DiagnosticAgent``/``AdaptationAgent`` -- the real
production agent classes ``main.py`` constructs and ``api/internal.py`` calls -- actually build and
thread a live, interaction-scoped MCP-3 ``ToolRegistry`` into the real ``GraphRun`` they construct,
rather than the registry sitting unused beside the graph. A test that only called
``build_mcp_tool_registry()`` directly would not prove this: it would prove the factory works in
isolation, not that anything in the production call path ever invokes it.

Captures the ``registry`` a real ``GraphRun`` construction receives by wrapping the class these
agent modules actually import and call, rather than mocking anything about ``GraphRun`` itself --
the constructed object is the real one, and the rest of ``propose()`` runs unmodified against it
(against the deterministic ``ci-fake`` route, so no network call happens anywhere in these tests).
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Iterator
from typing import Any

import pytest

from ramals_ai.adaptation import agent as adaptation_agent_module
from ramals_ai.adaptation.agent import AdaptationAgent
from ramals_ai.config.settings import ModelRoute, Settings
from ramals_ai.contracts.generated import AgentType, AIRequestEnvelope
from ramals_ai.diagnostic import agent as diagnostic_agent_module
from ramals_ai.diagnostic.agent import DiagnosticAgent
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.graph.runtime import GraphRun
from ramals_ai.graph.tools import ToolRegistry
from ramals_ai.mcp.client import RamalsMcpReadClient
from ramals_ai.mcp.context import McpExecutionContext
from ramals_ai.mcp.tools import ADAPTATION_AGENT_CAPABILITIES, DIAGNOSTIC_AGENT_CAPABILITIES
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


def envelope() -> AIRequestEnvelope:
    return AIRequestEnvelope.model_validate(
        {
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
            "constraints": {"interactionClass": "INTERACTIVE_AI", "deadlineMs": 8000},
            "requestedCapability": "EXPLAIN",
        }
    )


DIAGNOSTIC_OUTPUT = json.dumps(
    {
        "skillCode": "KAFKA_PARTITION",
        "objectiveCode": "KAFKA_PARTITIONS",
        "difficulty": "FOUNDATIONAL",
        "rationale": "Practice partitioning before advancing.",
        "inferredStatus": "NEEDS_PRACTICE",
    }
)

ADAPTATION_OUTPUT = json.dumps(
    {
        "skillCode": "KAFKA_PARTITION",
        "recommendedAction": "PRACTICE",
        "rationale": "Practice the skill before advancing.",
    }
)


class _CapturingGraphRun:
    """Wraps the real ``GraphRun`` class: captures the ``registry`` kwarg a construction received,
    then constructs and returns a genuine, fully-functional ``GraphRun`` -- so the rest of
    ``propose()`` (``build_prompt``, ``build_state``, ``run``) runs completely unmodified."""

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


def test_diagnostic_agent_threads_a_live_registry_into_the_real_graph_run(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The real ``DiagnosticAgent.propose`` -- exactly what ``api/internal.py`` calls -- constructs
    ``GraphRun`` with a non-empty, correctly-scoped registry when a Java-shaped call arrives with a
    real MCP client and a delegated interaction context."""
    monkeypatch.setattr(diagnostic_agent_module, "GraphRun", _CapturingGraphRun)
    provider = FakeProvider()
    agent = DiagnosticAgent(
        LLMGateway(provider), route=ModelRoute.CI_FAKE, mcp_client=_mcp_client()
    )

    agent.propose(envelope(), deadline=Deadline.in_ms(8000), mcp_execution_context=_mcp_context())

    assert _CapturingGraphRun.call_count == 1
    registry = _CapturingGraphRun.captured_registry
    assert registry is not None
    assert registry.allowed(AgentType.DIAGNOSTIC) == DIAGNOSTIC_AGENT_CAPABILITIES
    # Scoped to exactly this agent type -- the shared multi-agent default is never handed to a
    # single agent's own per-interaction registry.
    assert registry.allowed(AgentType.ADAPTATION) == frozenset()
    for capability in DIAGNOSTIC_AGENT_CAPABILITIES:
        registry.authorize(AgentType.DIAGNOSTIC, capability)  # must not raise ToolDenied


def test_adaptation_agent_threads_a_live_registry_into_the_real_graph_run(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Same proof, for ``AdaptationAgent`` -- the least-privilege mastery + diagnostic-read set."""
    monkeypatch.setattr(adaptation_agent_module, "GraphRun", _CapturingGraphRun)
    provider = FakeProvider()
    agent = AdaptationAgent(
        LLMGateway(provider), route=ModelRoute.CI_FAKE, mcp_client=_mcp_client()
    )

    agent.propose(envelope(), deadline=Deadline.in_ms(8000), mcp_execution_context=_mcp_context())

    assert _CapturingGraphRun.call_count == 1
    registry = _CapturingGraphRun.captured_registry
    assert registry is not None
    assert registry.allowed(AgentType.ADAPTATION) == ADAPTATION_AGENT_CAPABILITIES
    assert registry.allowed(AgentType.DIAGNOSTIC) == frozenset()


def test_diagnostic_agent_falls_back_to_no_registry_when_java_sent_no_delegated_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A real MCP client configured, but no per-interaction context supplied (Java's own call sites
    do not attach one yet): the agent degrades to ``GraphRun``'s own empty-registry default rather
    than failing the whole proposal -- exactly today's existing no-MCP behavior."""
    monkeypatch.setattr(diagnostic_agent_module, "GraphRun", _CapturingGraphRun)
    provider = FakeProvider()
    agent = DiagnosticAgent(
        LLMGateway(provider), route=ModelRoute.CI_FAKE, mcp_client=_mcp_client()
    )

    proposal = agent.propose(envelope(), deadline=Deadline.in_ms(8000))

    assert _CapturingGraphRun.captured_registry is None
    assert proposal.proposal is not None  # the proposal still completes normally


def test_diagnostic_agent_builds_no_registry_when_mcp_is_not_configured_at_all(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """No shared MCP client at all (``RAMALS_AI_MCP_ENABLED`` off, the default): the same safe
    degrade, even if a caller somehow supplied a context."""
    monkeypatch.setattr(diagnostic_agent_module, "GraphRun", _CapturingGraphRun)
    provider = FakeProvider()
    agent = DiagnosticAgent(LLMGateway(provider), route=ModelRoute.CI_FAKE, mcp_client=None)

    agent.propose(envelope(), deadline=Deadline.in_ms(8000), mcp_execution_context=_mcp_context())

    assert _CapturingGraphRun.captured_registry is None
