"""Tool denial and per-node tracing (M1-T06, Doc 02 §5 and §7).

Two of the required test groups, kept together because they share a concern: what the graph is
allowed to do, and whether anyone can tell afterwards what it did.

Tool denial is a security property. An agent reaching for a capability it does not hold is not a
routing mistake — something asked the graph to work outside its authorization, and that must fail
loudly, be counted, and never be retried down another path.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable, Iterator
from dataclasses import replace

import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import AgentType
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import Message
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.gateway.routes import default_registry
from ramals_ai.graph.runtime import GraphRun
from ramals_ai.graph.tools import ReadOnlyTool, ToolDenied, ToolRegistry, empty_registry
from ramals_ai.prompting.templates import BuiltPrompt, PromptTemplateId

MESSAGES = (Message(role="user", content="Explain Kafka partitioning."),)

# Fixture messages under the identity the route points at. ``build_state`` refuses a mismatch, so
# the identity cannot simply be invented here.
PROMPT = BuiltPrompt(
    template_id=PromptTemplateId.TUTOR_EXPLAIN,
    version=default_registry()
    .resolve(ModelRoute.CI_FAKE)
    .prompt_version_for(PromptTemplateId.TUTOR_EXPLAIN),
    messages=MESSAGES,
)


# -- tool denial ---------------------------------------------------------------------------------


def test_mvp1_registers_no_tools_at_all() -> None:
    """Doc 02 §5: Tutor V1 starts with no mutating tools; MVP-1 starts with none whatsoever."""
    registry = empty_registry()
    assert registry.tools == {}
    for agent_type in AgentType:
        assert registry.allowed(agent_type) == frozenset()


def test_a_tool_not_on_the_allowlist_is_denied() -> None:
    registry = empty_registry()
    with pytest.raises(ToolDenied) as denial:
        registry.authorize(AgentType.TUTOR, "search_curriculum")
    assert denial.value.tool == "search_curriculum"


def test_authorization_happens_at_execution_time() -> None:
    """A capability granted at wiring time and revoked before use must fail at the call.

    The check answers "may this happen now", not "was this legal when the graph was assembled" --
    which is the question a cached decision would be answering.
    """
    tool = ReadOnlyTool(name="probe", description="reads", run=lambda _args: {"ok": True})
    granted = ToolRegistry(
        tools={"probe": tool}, allowlists={AgentType.TUTOR: frozenset({"probe"})}
    )

    assert granted.invoke(AgentType.TUTOR, "probe", {})["output"] == {"ok": True}

    revoked = ToolRegistry(tools={"probe": tool}, allowlists={AgentType.TUTOR: frozenset()})
    with pytest.raises(ToolDenied):
        revoked.invoke(AgentType.TUTOR, "probe", {})


def test_an_allowlisted_but_unregistered_tool_is_denied() -> None:
    """A typo in an allowlist must not read as a working capability."""
    registry = ToolRegistry(tools={}, allowlists={AgentType.TUTOR: frozenset({"typo"})})
    with pytest.raises(ToolDenied):
        registry.authorize(AgentType.TUTOR, "typo")


def test_one_agents_capability_is_not_anothers() -> None:
    tool = ReadOnlyTool(name="probe", description="reads", run=lambda _args: {"ok": True})
    registry = ToolRegistry(
        tools={"probe": tool}, allowlists={AgentType.TUTOR: frozenset({"probe"})}
    )

    registry.authorize(AgentType.TUTOR, "probe")
    with pytest.raises(ToolDenied):
        registry.authorize(AgentType.ASSESSMENT, "probe")


def test_tool_output_is_labelled_untrusted() -> None:
    """Doc 02 §5. A tool result is evidence of what a tool said, not a fact the platform holds."""
    tool = ReadOnlyTool(name="probe", description="reads", run=lambda _args: {"value": 42})
    registry = ToolRegistry(
        tools={"probe": tool}, allowlists={AgentType.TUTOR: frozenset({"probe"})}
    )

    result = registry.invoke(AgentType.TUTOR, "probe", {})

    assert result["trusted"] is False
    assert result["output"] == {"value": 42}


def test_no_mutating_tool_type_exists() -> None:
    """Doc 02 §5: no mutating DB, shell or arbitrary HTTP tool by default.

    Enforced by there being no way to declare one. A boolean flag would make a mutating tool a
    setting; requiring a new type makes it a decision somebody has to justify.
    """
    from ramals_ai.graph import tools

    exported = {name for name in dir(tools) if name.endswith("Tool")}
    assert exported == {"ReadOnlyTool"}, f"unexpected tool types: {exported}"


# -- per-node spans ------------------------------------------------------------------------------


@pytest.fixture
def recorded_spans() -> Iterator[Callable[[], list[str]]]:
    """Captures spans from a provider local to this test.

    A separate provider rather than the global one: OpenTelemetry permits setting the global exactly
    once per process, and the conftest meter provider already demonstrated what happens when a test
    assumes otherwise.
    """
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))

    original = trace.get_tracer_provider()
    trace._TRACER_PROVIDER = provider  # noqa: SLF001 - no supported API for scoped replacement

    def names() -> list[str]:
        return [span.name for span in exporter.get_finished_spans()]

    yield names

    trace._TRACER_PROVIDER = original  # noqa: SLF001


def test_every_node_opens_its_own_span(recorded_spans: Callable[[], list[str]]) -> None:
    """Doc 02 §7. "Which node" is the first question when a run misbehaves, and it is
    unanswerable after the fact unless the spans were recorded while it ran."""
    clock_value = {"now": 1000.0}

    def clock() -> float:
        return clock_value["now"]

    gateway = LLMGateway(FakeProvider(), clock=clock, sleep=lambda _s: None)
    run = GraphRun(gateway)
    state = run.build_state(
        prompt=PROMPT,
        agent_type=AgentType.TUTOR,
        route=ModelRoute.CI_FAKE,
        deadline=Deadline.in_ms(60_000, clock=clock),
        interaction_id=str(uuid.uuid7()),
        request_id=str(uuid.uuid4()),
        proposal_id=str(uuid.uuid7()),
        minimized_learning_context={"skillCode": "KAFKA_TOPIC"},
    )

    run.run(state, route=ModelRoute.CI_FAKE)

    names = recorded_spans()
    for node in (
        "graph.load_context",
        "graph.policy_precheck",
        "graph.plan",
        "graph.model_or_tool",
        "graph.validate_output",
        "graph.finalize",
    ):
        assert node in names, f"no span recorded for {node}; spans seen: {names}"


def test_a_repair_loop_records_its_own_span(recorded_spans: Callable[[], list[str]]) -> None:
    """The topology-derived node ceiling leaves room for a repair span."""
    attempts = {"n": 0}

    def validator(_text: str) -> list[str]:
        attempts["n"] += 1
        return ["MALFORMED"] if attempts["n"] == 1 else []

    def clock() -> float:
        return 1000.0

    gateway = LLMGateway(FakeProvider(), clock=clock, sleep=lambda _s: None)
    run = GraphRun(gateway, validator=validator)
    state = run.build_state(
        prompt=PROMPT,
        agent_type=AgentType.TUTOR,
        route=ModelRoute.CI_FAKE,
        deadline=Deadline.in_ms(60_000, clock=clock),
        interaction_id=str(uuid.uuid7()),
        request_id=str(uuid.uuid4()),
        proposal_id=str(uuid.uuid7()),
        minimized_learning_context={"skillCode": "KAFKA_TOPIC"},
    )
    state.ceilings = replace(state.ceilings, max_node_executions=12)

    run.run(state, route=ModelRoute.CI_FAKE)

    assert "graph.bounded_repair" in recorded_spans()
