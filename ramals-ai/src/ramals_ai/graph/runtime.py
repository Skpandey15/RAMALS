"""Bounded graph execution (Doc 02 §3).

The graph is the one in the design document, unchanged:

    START -> load_context -> policy_precheck -> plan -> model_or_tool -> validate_output
             valid   -> finalize -> END
             invalid -> bounded_repair -> validate_output

The interesting part is not the shape, it is that the loop terminates. ``validate_output`` can send
control back through ``bounded_repair``, and without bounds that is an agent that retries a model
until the money runs out. Three independent things stop it: the repair-cycle ceiling, the
node-execution ceiling, and the caller's absolute deadline. Any one of them is sufficient, which is
deliberate — the failure being guarded against is a bound that turns out not to have been enforced.

Cost is bounded by the route's Doc 04 ceiling, read from the gateway's registry. Doc 02 §4 is
explicit that it declares no cost constant of its own, so neither does this module.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from decimal import Decimal
from typing import Any

from opentelemetry import metrics

from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import AgentType, InteractionClass
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.graph import nodes
from ramals_ai.graph.limits import REPAIR_ROUTE_RESERVE, CeilingExceeded, Ceilings
from ramals_ai.graph.state import AgentState
from ramals_ai.graph.tools import ToolRegistry, empty_registry
from ramals_ai.prompting.templates import BuiltPrompt, PromptRegister, PromptTemplateId

logger = logging.getLogger(__name__)

_meter = metrics.get_meter("ramals-ai")

graph_runs = _meter.create_counter(
    "ramals.ai.graph.runs",
    description="Graph executions, by agent type and outcome",
)
graph_ceiling_stops = _meter.create_counter(
    "ramals.ai.graph.ceiling.stops",
    description="Graph runs stopped by a ceiling, by which control bound them",
)

FINALIZE = "finalize"
REPAIR = "bounded_repair"


def route_for_validation(state: AgentState) -> str:
    """The one branch in the graph: finish, or repair.

    A pure function of state, with no side effects and no clock. Doc 02's acceptance criterion is
    that budget and deadline stops are *deterministic*, and a routing decision that consulted the
    time or a random value could not be.
    """
    if not state.validation_errors:
        return FINALIZE
    if state.repair_cycle_count >= state.ceilings.max_repair_cycles:
        # Out of repairs. Finalizing with the errors recorded beats looping, and beats raising:
        # the caller gets a proposal marked invalid rather than an exception with nothing in it.
        return FINALIZE
    if state.node_executions_remaining < REPAIR_ROUTE_RESERVE:
        # A repair cycle costs four further node executions: bounded_repair, model_or_tool,
        # validate_output and finalize. Starting one that cannot complete would burn the remaining
        # steps and still finish invalid -- strictly worse than finalizing now.
        #
        # The reserve is derived from the current graph topology and the node-execution ceiling in
        # limits.py. It keeps the finalization node inside the budget when a repair is started.
        return FINALIZE
    return REPAIR


class GraphRun:
    """One bounded execution of the standard graph."""

    def __init__(
        self,
        gateway: LLMGateway,
        *,
        registry: ToolRegistry | None = None,
        validator: Callable[[str], list[str]] | None = None,
        prompts: PromptRegister | None = None,
    ) -> None:
        self._gateway = gateway
        self._registry = registry if registry is not None else empty_registry()
        self._validator = validator
        self._prompts = prompts if prompts is not None else _shipped_prompts()

    def build_prompt(
        self,
        *,
        route: ModelRoute,
        template_id: PromptTemplateId,
        **arguments: Any,
    ) -> BuiltPrompt:
        """Builds the revision this route points at, and stamps what it built.

        The caller names the *template*, never the version. That is the whole safety property: an
        agent cannot ask for one revision and record another, because it never gets to say which
        revision it wants -- the route pointer decides, and the register produces the messages and
        the identity in one value.
        """
        config = self._gateway.registry.resolve(route)
        return self._prompts.build(template_id, config.prompt_version_for(template_id), **arguments)

    def build_state(
        self,
        *,
        agent_type: AgentType,
        route: ModelRoute,
        deadline: Deadline,
        interaction_id: str,
        request_id: str,
        proposal_id: str,
        prompt: BuiltPrompt,
        minimized_learning_context: dict[str, Any],
        policy_constraints: dict[str, Any] | None = None,
        agent_version: str = "V1",
        interaction_class: InteractionClass = InteractionClass.INTERACTIVE_AI,
    ) -> AgentState:
        """Resolves the ceilings and budgets for a run before it starts.

        The cost budget comes from the route configuration, not from a constant here. That is the
        Doc 02 §4 rule made structural: there is no second number to drift.
        """
        config = self._gateway.registry.resolve(route)
        pointed_at = config.prompt_version_for(prompt.template_id)
        if prompt.version != pointed_at:
            # Defence in depth. build_prompt already resolves the version from this same pointer, so
            # reaching here means a caller assembled the prompt some other way -- exactly the path
            # that would record an identity the output does not have.
            raise ValueError(
                f"{route} points {prompt.template_id} at {pointed_at}, "
                f"but the prompt supplied was built from {prompt.version}"
            )
        return AgentState(
            contract_version="1.0",
            interaction_id=interaction_id,
            request_id=request_id,
            proposal_id=proposal_id,
            agent_type=agent_type,
            agent_version=agent_version,
            prompt=prompt,
            minimized_learning_context=minimized_learning_context,
            policy_constraints=policy_constraints or {},
            deadline=deadline,
            ceilings=Ceilings.for_agent(agent_type),
            interaction_class=interaction_class,
            token_budget=config.max_output_tokens,
            cost_budget_usd=config.hard_cost_ceiling_usd,
        )

    def run(
        self,
        state: AgentState,
        *,
        route: ModelRoute,
    ) -> AgentState:
        """Executes the graph to completion or to the first ceiling.

        A ceiling stop is not an error path bolted on: it is how a bounded run is expected to end
        when the work does not fit. The state comes back with its counters intact, so the caller can
        see which bound was reached and how much was spent getting there.
        """
        try:
            nodes.load_context(state)
            nodes.policy_precheck(state)
            nodes.plan(state)

            while True:
                nodes.model_or_tool(
                    state,
                    gateway=self._gateway,
                    route=route,
                    registry=self._registry,
                )
                nodes.validate_output(state, validator=self._validator)

                if route_for_validation(state) is FINALIZE:
                    break
                nodes.bounded_repair(state)

            nodes.finalize(state)
        except CeilingExceeded as stop:
            graph_ceiling_stops.add(1, {"agent": state.agent_type.value, "control": stop.control})
            graph_runs.add(1, {"agent": state.agent_type.value, "outcome": "ceiling_stop"})
            logger.warning(
                "graph run stopped by a ceiling",
                extra={
                    "operation": "graph.ceiling_stop",
                    "control": stop.control,
                    "limit": stop.limit,
                    "stepCount": state.node_execution_count,
                    "modelCallCount": state.model_call_count,
                    "repairCount": state.repair_cycle_count,
                },
            )
            raise

        graph_runs.add(1, {"agent": state.agent_type.value, "outcome": "completed"})
        logger.info(
            "graph run completed",
            extra={
                "operation": "graph.complete",
                "agentType": state.agent_type.value,
                "stepCount": state.node_execution_count,
                "modelCallCount": state.model_call_count,
                "repairCount": state.repair_cycle_count,
                "estimatedCostUsd": f"{state.cost_spent_usd:.6f}",
                "trace": ",".join(state.trace),
            },
        )
        return state


def cost_budget_of(gateway: LLMGateway, route: ModelRoute) -> Decimal:
    """The per-request cost bound, always read from the route.

    Exposed so a test can assert the graph's budget *is* the route's ceiling rather than a copy that
    happens to match today.
    """
    return gateway.registry.resolve(route).hard_cost_ceiling_usd


def _shipped_prompts() -> PromptRegister:
    """The image's prompt register, imported at call time.

    Deferred because the register imports every agent's prompt module, and those packages import
    this one -- a module-level import here fails with a partially initialized ``GraphRun``. Verified
    by trying it, not by inspection.
    """
    from ramals_ai.prompting.register import default_prompt_register

    return default_prompt_register()
