"""Bounded graph execution (M1-T06, Doc 02).

The acceptance criteria are three claims, and each is only worth as much as the test that tries to
break it: no unbounded loops, deterministic budget and deadline stops, and graph state that carries
no authority.

The repair cycle is the part that matters. ``validate_output`` can route back through
``bounded_repair``, and an unbounded version of that is an agent that retries a model until the
money runs out. Three separate bounds stop it, and each is tested in isolation so a run cannot pass
because a different one happened to bite first.
"""

from __future__ import annotations

import uuid
from dataclasses import replace
from decimal import Decimal

import pytest

from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import AgentType, InteractionClass
from ramals_ai.gateway import budget
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.graph import limits, runtime
from ramals_ai.graph.limits import CeilingExceeded
from ramals_ai.graph.runtime import GraphRun
from ramals_ai.graph.state import AgentState

MESSAGES = (Message(role="user", content="Explain Kafka partitioning."),)


class ManualClock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance_ms(self, milliseconds: float) -> None:
        self.now += milliseconds / 1000.0


def build(
    *, provider: object | None = None, validator: object | None = None
) -> tuple[GraphRun, ManualClock]:
    clock = ManualClock()
    gateway = LLMGateway(
        provider or FakeProvider(),  # type: ignore[arg-type]
        clock=clock,
        sleep=lambda seconds: clock.advance_ms(seconds * 1000),
    )
    return GraphRun(gateway, validator=validator), clock  # type: ignore[arg-type]


def state_for(
    run: GraphRun,
    clock: ManualClock,
    *,
    agent_type: AgentType = AgentType.TUTOR,
    deadline_ms: int = 60_000,
    route: ModelRoute = ModelRoute.CI_FAKE,
) -> AgentState:
    return run.build_state(
        agent_type=agent_type,
        route=route,
        deadline=Deadline.in_ms(deadline_ms, clock=clock),
        interaction_id=str(uuid.uuid7()),
        request_id=str(uuid.uuid4()),
        proposal_id=str(uuid.uuid7()),
        minimized_learning_context={"skillCode": "KAFKA_TOPIC"},
    )


# -- graph transitions ----------------------------------------------------------------------------


def test_a_valid_run_walks_the_standard_graph() -> None:
    """Doc 02 §3, in order, with no repair when the output is fine."""
    run, clock = build()
    result = run.run(state_for(run, clock), route=ModelRoute.CI_FAKE, messages=MESSAGES)

    assert result.trace == [
        "load_context",
        "policy_precheck",
        "plan",
        "model_or_tool",
        "validate_output",
        "finalize",
    ]
    assert result.final_proposal is not None
    assert result.validation_errors == []


def test_the_repair_loop_can_succeed_when_the_step_budget_allows_one() -> None:
    """The loop must be able to succeed, not merely to terminate.

    Run with the topology-derived node-execution ceiling. This proves the repair mechanism works
    with the two documented repair cycles available.
    """
    attempts = {"n": 0}

    def validator(_text: str) -> list[str]:
        attempts["n"] += 1
        return ["MALFORMED"] if attempts["n"] == 1 else []

    run, clock = build(validator=validator)
    state = state_for(run, clock)
    state.ceilings = replace(state.ceilings, max_node_executions=12)

    result = run.run(state, route=ModelRoute.CI_FAKE, messages=MESSAGES)

    assert "bounded_repair" in result.trace
    assert result.repair_cycle_count == 1
    assert result.validation_errors == []
    assert result.final_proposal is not None


def test_documented_node_and_repair_budgets_are_derived_from_graph() -> None:
    """Doc 02 §4 budgets agree with the current graph topology."""
    assert limits.STANDARD_GRAPH_NODE_EXECUTIONS == 6
    assert limits.REPAIR_CYCLE_NODE_EXECUTIONS == 3
    assert limits.MAX_REPAIR_CYCLES == 2
    assert limits.MAX_NODE_EXECUTIONS == 6 + (3 * 2) == 12
    assert limits.REPAIR_ROUTE_RESERVE == 4

    run, clock = build(validator=lambda _text: ["ALWAYS_MALFORMED"])
    result = run.run(state_for(run, clock), route=ModelRoute.CI_FAKE, messages=MESSAGES)

    assert result.repair_cycle_count == limits.MAX_REPAIR_CYCLES
    assert result.node_execution_count == limits.MAX_NODE_EXECUTIONS
    assert result.trace.count("bounded_repair") == limits.MAX_REPAIR_CYCLES


def test_routing_is_a_pure_function_of_state() -> None:
    """Deterministic stops (acceptance criterion) require a branch that consults nothing else."""
    run, clock = build()
    state = state_for(run, clock)
    state.validation_errors = ["MALFORMED"]

    first = runtime.route_for_validation(state)
    clock.advance_ms(5_000)
    second = runtime.route_for_validation(state)

    assert first == second == runtime.REPAIR


# -- node-execution and repair-cycle ceilings -----------------------------------------------------


def test_the_node_execution_ceiling_is_derived_from_doc_02s_graph() -> None:
    assert limits.MAX_NODE_EXECUTIONS == 12


def test_the_repair_cycle_ceiling_is_doc_02s_two() -> None:
    assert limits.MAX_REPAIR_CYCLES == 2


def test_a_permanently_invalid_output_stops_without_looping() -> None:
    """The decisive test: an output that never validates must still terminate."""
    run, clock = build(validator=lambda _text: ["ALWAYS_MALFORMED"])
    state = state_for(run, clock)
    state.ceilings = replace(state.ceilings, max_node_executions=12)

    result = run.run(state, route=ModelRoute.CI_FAKE, messages=MESSAGES)

    assert result.repair_cycle_count <= limits.MAX_REPAIR_CYCLES
    assert result.node_execution_count <= limits.MAX_NODE_EXECUTIONS
    # It finishes reporting the failure rather than raising: the caller gets a proposal marked
    # invalid, which is more useful than an exception carrying nothing.
    assert result.trace.count("bounded_repair") <= limits.MAX_REPAIR_CYCLES
    assert result.validation_errors, "an unrepairable output must still be reported as invalid"


def test_the_step_ceiling_stops_a_run_that_would_exceed_it() -> None:
    run, clock = build()
    state = state_for(run, clock)
    state.node_execution_count = limits.MAX_NODE_EXECUTIONS

    with pytest.raises(CeilingExceeded) as stop:
        run.run(state, route=ModelRoute.CI_FAKE, messages=MESSAGES)

    assert stop.value.control == "node execution"
    assert stop.value.limit == limits.MAX_NODE_EXECUTIONS


def test_a_node_is_counted_on_entry_not_on_success() -> None:
    """A node that fails must still consume a step, or a failing node loops for free."""
    run, clock = build()
    state = state_for(run, clock)
    state.enter_node("probe")
    assert state.node_execution_count == 1


def test_the_repair_ceiling_refuses_a_third_attempt() -> None:
    run, clock = build()
    state = state_for(run, clock)
    state.record_repair()
    state.record_repair()

    with pytest.raises(CeilingExceeded) as stop:
        state.record_repair()

    assert stop.value.control == "repair cycle"


# -- model-call ceilings ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("agent_type", "expected"),
    [
        (AgentType.TUTOR, 3),
        (AgentType.DIAGNOSTIC, 3),
        (AgentType.ADAPTATION, 3),
        # Doc 02 §4 allows assessment one more: item plus rubric is a longer chain.
        (AgentType.ASSESSMENT, 4),
    ],
)
def test_model_call_ceilings_match_doc_02(agent_type: AgentType, expected: int) -> None:
    assert limits.model_call_ceiling(agent_type) == expected


def test_the_model_call_ceiling_stops_the_run() -> None:
    run, clock = build()
    state = state_for(run, clock, agent_type=AgentType.TUTOR)
    for _ in range(3):
        state.record_model_call(Decimal("0.000000"))

    with pytest.raises(CeilingExceeded) as stop:
        state.record_model_call(Decimal("0.000000"))

    assert stop.value.control == "model call"
    assert stop.value.limit == 3


def test_the_model_call_ceiling_is_checked_before_provider_dispatch() -> None:
    class CountingProvider(FakeProvider):
        def __init__(self) -> None:
            super().__init__()
            self.calls = 0

        def complete(self, request: ProviderRequest) -> ProviderResponse:  # noqa: ARG002
            self.calls += 1
            return super().complete(request)

    provider = CountingProvider()
    run, clock = build(provider=provider)
    state = state_for(run, clock, agent_type=AgentType.TUTOR)
    state.model_call_count = state.ceilings.max_model_calls

    with pytest.raises(CeilingExceeded) as stop:
        run.run(state, route=ModelRoute.CI_FAKE, messages=MESSAGES)

    assert stop.value.control == "model call"
    assert provider.calls == 0, "the graph contacted the provider after its call ceiling was spent"


def test_assessment_may_make_the_extra_call_a_tutor_may_not() -> None:
    run, clock = build()
    assessment = state_for(run, clock, agent_type=AgentType.ASSESSMENT)
    for _ in range(4):
        assessment.record_model_call(Decimal("0.000000"))
    assert assessment.model_call_count == 4


def test_interaction_class_is_carried_in_graph_state() -> None:
    run, clock = build()
    state = run.build_state(
        agent_type=AgentType.ASSESSMENT,
        interaction_class=InteractionClass.ASSESSMENT_PROPOSAL,
        route=ModelRoute.CI_FAKE,
        deadline=Deadline.in_ms(10_000, clock=clock),
        interaction_id=str(uuid.uuid7()),
        request_id=str(uuid.uuid4()),
        proposal_id=str(uuid.uuid7()),
        minimized_learning_context={},
    )

    assert state.interaction_class is InteractionClass.ASSESSMENT_PROPOSAL


def test_gateway_usage_is_accumulated_in_graph_state() -> None:
    class UsageProvider(FakeProvider):
        def complete(self, request: ProviderRequest) -> ProviderResponse:  # noqa: ARG002
            return ProviderResponse(
                text='{"ok": true}',
                input_tokens=7,
                cached_input_tokens=2,
                output_tokens=3,
            )

    run, clock = build(provider=UsageProvider())
    state = state_for(run, clock)
    result = run.run(state, route=ModelRoute.CI_FAKE, messages=MESSAGES)

    assert result.input_tokens == 7
    assert result.cached_input_tokens == 2
    assert result.output_tokens == 3
    assert result.latency_ms >= 0


# -- cost and deadline exhaustion ------------------------------------------------------------------


def test_the_cost_budget_is_the_routes_ceiling_not_a_local_constant() -> None:
    """Doc 02 §4 carries no independent cost constant; two numbers for one budget would drift."""
    run, clock = build()
    state = state_for(run, clock)

    gateway_budget = runtime.cost_budget_of(run._gateway, ModelRoute.CI_FAKE)  # noqa: SLF001
    assert state.cost_budget_usd == gateway_budget


def test_no_independent_cost_constant_exists_in_the_graph_package() -> None:
    """Doc 02 §4 declares no cost constant, so neither may this package.

    Checked against module-level names rather than source text: the rule is about a *constant* being
    introduced, and the words "cost" and "budget" legitimately appear in field names and prose.
    """
    numeric_constants = {
        name
        for name in dir(limits)
        if name.isupper() and isinstance(getattr(limits, name), (int, float, Decimal))
    }

    assert not any("COST" in name or "USD" in name for name in numeric_constants), (
        f"the graph must read the route's Doc 04 cost ceiling, not declare one: {numeric_constants}"
    )
    assert numeric_constants == {
        "MAX_NODE_EXECUTIONS",
        "MAX_REPAIR_CYCLES",
        "REPAIR_CYCLE_NODE_EXECUTIONS",
        "REPAIR_ROUTE_RESERVE",
        "STANDARD_GRAPH_NODE_EXECUTIONS",
    }


def test_cumulative_cost_stops_the_run_even_when_each_call_was_affordable() -> None:
    """The gateway bounds one call; this bounds the run. Three cheap calls can still overrun."""
    run, clock = build()
    state = state_for(run, clock)
    state.cost_budget_usd = Decimal("0.010000")

    state.record_model_call(Decimal("0.006000"))
    with pytest.raises(CeilingExceeded) as stop:
        state.record_model_call(Decimal("0.006000"))

    assert stop.value.control == "request cost"


def test_repair_call_is_refused_before_dispatch_when_prior_spend_leaves_no_room() -> None:
    class MeteredProvider(FakeProvider):
        def __init__(self) -> None:
            super().__init__()
            self.calls = 0

        def count_input_tokens(self, model: str, messages: tuple[Message, ...]) -> int:  # noqa: ARG002
            return 1000

        def complete(self, _request: ProviderRequest) -> ProviderResponse:
            self.calls += 1
            return ProviderResponse(
                text="repairable output",
                input_tokens=1000,
                cached_input_tokens=0,
                output_tokens=200,
            )

    attempts = {"count": 0}

    def validator(_text: str) -> list[str]:
        attempts["count"] += 1
        return ["MALFORMED"] if attempts["count"] == 1 else []

    provider = MeteredProvider()
    run, clock = build(provider=provider, validator=validator)
    state = state_for(run, clock, route=ModelRoute.TUTOR_DEFAULT)
    state.cost_budget_usd = Decimal("0.022000")

    with pytest.raises(GatewayError) as refusal:
        run.run(state, route=ModelRoute.TUTOR_DEFAULT, messages=MESSAGES)

    assert refusal.value.code is GatewayErrorCode.COST_CEILING_EXCEEDED
    assert provider.calls == 1
    assert state.cost_spent_usd == Decimal("0.006000")
    assert state.model_call_count == 1


def test_successful_dispatch_records_actual_cost_and_model_latency() -> None:
    class MeteredProvider(FakeProvider):
        def __init__(self) -> None:
            super().__init__()
            self.calls = 0

        def count_input_tokens(self, model: str, messages: tuple[Message, ...]) -> int:  # noqa: ARG002
            return 10

        def complete(self, _request: ProviderRequest) -> ProviderResponse:
            self.calls += 1
            return ProviderResponse(
                text="successful output",
                input_tokens=10,
                cached_input_tokens=2,
                output_tokens=20,
            )

    provider = MeteredProvider()
    run, clock = build(provider=provider)
    state = state_for(run, clock, route=ModelRoute.TUTOR_DEFAULT)

    result = run.run(state, route=ModelRoute.TUTOR_DEFAULT, messages=MESSAGES)
    config = run._gateway.registry.resolve(ModelRoute.TUTOR_DEFAULT)  # noqa: SLF001
    expected_cost = budget.actual_cost_usd(config, input_tokens=10, output_tokens=20)

    assert provider.calls == 1
    assert result.cost_spent_usd == expected_cost
    assert result.input_tokens == 10
    assert result.cached_input_tokens == 2
    assert result.output_tokens == 20
    assert result.latency_ms >= 0


def test_an_expired_deadline_stops_the_run_before_any_model_call() -> None:
    class CountingProvider(FakeProvider):
        def __init__(self) -> None:
            super().__init__()
            self.calls = 0

        def complete(self, request: ProviderRequest) -> ProviderResponse:
            self.calls += 1
            return super().complete(request)

    provider = CountingProvider()
    run, clock = build(provider=provider)
    state = state_for(run, clock, deadline_ms=1_000)
    clock.advance_ms(1_500)

    with pytest.raises(GatewayError) as stop:
        run.run(state, route=ModelRoute.CI_FAKE, messages=MESSAGES)

    assert stop.value.code is GatewayErrorCode.DEADLINE_EXCEEDED
    assert provider.calls == 0, "the deadline must stop the run before it spends anything"


def test_a_ceiling_stop_preserves_the_counters() -> None:
    """A stop that discarded its counters would answer 'it stopped' but not 'how far did it get'."""
    run, clock = build()
    state = state_for(run, clock)
    state.node_execution_count = limits.MAX_NODE_EXECUTIONS

    with pytest.raises(CeilingExceeded):
        run.run(state, route=ModelRoute.CI_FAKE, messages=MESSAGES)

    assert state.node_execution_count == limits.MAX_NODE_EXECUTIONS
    assert state.model_call_count == 0


# -- graph state carries no authority ------------------------------------------------------------


def test_graph_state_declares_no_authoritative_field() -> None:
    """Acceptance criterion. A model must not be able to influence a record by writing a number.

    If graph state ever carried a mastery value or a progression decision, a plausible-looking
    dictionary entry would be a path into the learner's record, and the MVP-0 control boundary would
    be worth nothing.
    """
    import dataclasses

    forbidden = {
        "mastery_score",
        "mastery_status",
        "evidence_confidence",
        "progression",
        "unlocked",
        "authoritative",
        "decision",
        "grade",
        "score",
    }
    fields = {field.name for field in dataclasses.fields(AgentState)}

    assert not (fields & forbidden), f"graph state must confer no authority: {fields & forbidden}"


def test_the_final_proposal_is_only_ever_a_proposal() -> None:
    run, clock = build()
    result = run.run(state_for(run, clock), route=ModelRoute.CI_FAKE, messages=MESSAGES)

    assert result.final_proposal is not None
    assert "masteryScore" not in result.final_proposal
    assert "decision" not in result.final_proposal
