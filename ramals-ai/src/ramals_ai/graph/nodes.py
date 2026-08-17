"""The standard nodes (Doc 02 §3).

Each node takes the state, does one thing, and returns it. Every node is entered through
``state.enter_node``, which is what makes the step ceiling real rather than advisory — a node that
forgets to call it would not be counted, so nodes do not get to choose.

Each node also opens a span. Doc 02 requires per-node tracing, and the reason is concrete: when a
graph run costs more than expected or stops early, "which node" is the first question, and it is
unanswerable after the fact unless the spans were recorded while it ran.

The model call itself is not made here. It goes through ``LLMGateway``, which owns budgets, retries
and provider normalization. A node that called a provider directly would bypass every ceiling
M1-T05 established.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from contextlib import AbstractContextManager
from typing import Any

from opentelemetry.trace import Span

from ramals_ai.gateway.errors import GatewayError
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import Message
from ramals_ai.graph.state import AgentState
from ramals_ai.graph.tools import ToolRegistry
from ramals_ai.telemetry import tracing

logger = logging.getLogger(__name__)

Validator = Callable[[str], list[str]]
"""Returns validation errors for a model output; an empty list means valid."""


def _span(node: str) -> AbstractContextManager[Span]:
    """One span per node (Doc 02 §7). Named uniformly so a waterfall reads as the graph."""
    return tracing.tracer().start_as_current_span(f"graph.{node}")


def load_context(state: AgentState) -> AgentState:
    """Takes the minimized context as given.

    Deliberately does not fetch anything. Spring decided what this agent may see, after authorizing
    the learner; a node that enriched the context here would be widening an authorization decision
    made somewhere else.
    """
    state.enter_node("load_context")
    with _span("load_context"):
        logger.debug(
            "graph loaded context",
            extra={"operation": "graph.load_context", "agentType": state.agent_type.value},
        )
    return state


def policy_precheck(state: AgentState) -> AgentState:
    """Stops a run that cannot legally or affordably finish, before it spends anything.

    Checked here as well as inside the gateway because the cheapest failed run is the one that never
    starts. The gateway refuses a call it cannot afford; this refuses a *run* that has already run
    out of time.
    """
    state.enter_node("policy_precheck")
    with _span("policy_precheck"):
        state.check_deadline()
    return state


def plan(state: AgentState) -> AgentState:
    """Decides what to attempt next.

    A deliberate no-op in MVP-1: with no tools registered there is exactly one plan, which is to ask
    the model. The node exists because Doc 02 §3 puts it in the standard graph, and because the
    branch point needs somewhere to live before MVP-2 gives it something to choose between.
    """
    state.enter_node("plan")
    with _span("plan"):
        pass
    return state


def model_or_tool(
    state: AgentState,
    *,
    gateway: LLMGateway,
    route: Any,
    messages: tuple[Message, ...],
    registry: ToolRegistry,  # noqa: ARG001 - see below
) -> AgentState:
    """Calls the model through the governed gateway, or a tool the agent is authorized for.

    ``registry`` is accepted and currently unused: no agent holds a capability in MVP-1, so there is
    nothing to select. It is threaded through now so that the tool path enters through an authorized
    call site from the first day, rather than being introduced later alongside the first tool -- the
    point at which forgetting the check is easiest and least visible.
    """
    state.enter_node("model_or_tool")
    with _span("model_or_tool"):
        state.check_deadline()
        try:
            result = gateway.complete(
                route=route,
                messages=messages,
                deadline=state.deadline,
                max_output_tokens=state.token_budget or None,
            )
        except GatewayError as failure:
            # The gateway has already classified this. Recording it as a validation error rather
            # than raising lets a bounded repair attempt a different phrasing when the failure was
            # about the output; anything else propagates, because a budget or auth failure is not
            # something a repair loop can fix.
            if failure.code.value == "INVALID_STRUCTURED_OUTPUT":
                state.validation_errors.append(failure.code.value)
                return state
            raise

        state.record_model_call(result.estimated_cost_usd)
        state.final_proposal = {
            "text": result.text,
            "modelRoute": result.route.value,
            "promptVersion": result.prompt_version,
        }
        logger.info(
            "graph made a model call",
            extra={
                "operation": "graph.model_call",
                "route": result.route.value,
                "modelCallCount": state.model_call_count,
                "estimatedCostUsd": result.cost_string,
            },
        )
    return state


def validate_output(state: AgentState, *, validator: Validator | None = None) -> AgentState:
    """Records whether the output is usable. Does not decide what happens next.

    The routing decision lives in the edge function, so that "is this valid" and "what do we do
    about it" stay separable — and so the repair ceiling is enforced in one place rather than
    inside a node that also has opinions about content.
    """
    state.enter_node("validate_output")
    with _span("validate_output"):
        if state.final_proposal is None:
            state.validation_errors.append("NO_PROPOSAL")
            return state
        if validator is not None:
            state.validation_errors = validator(state.final_proposal.get("text", ""))
    return state


def bounded_repair(state: AgentState) -> AgentState:
    """Consumes one repair attempt and clears the failed output.

    Raises once the repair ceiling is reached, which is what stops the validate/repair cycle in
    Doc 02 §3 from being the unbounded loop it would otherwise be.
    """
    state.enter_node("bounded_repair")
    with _span("bounded_repair"):
        state.record_repair()
        state.final_proposal = None
        logger.info(
            "graph attempting a bounded repair",
            extra={
                "operation": "graph.repair",
                "repairCount": state.repair_count,
                "errors": list(state.validation_errors),
            },
        )
    return state


def finalize(state: AgentState) -> AgentState:
    """Marks the run complete. Confers no authority on the result.

    Whatever is in ``final_proposal`` is a proposal: Spring's deterministic policy decides what, if
    anything, it changes.

    Validation errors are deliberately *not* cleared. A run can reach here having exhausted its
    repairs on an output that never validated, and erasing the errors on the way out would make an
    unrepairable result indistinguishable from a clean one -- the caller would receive a proposal
    with no indication that the graph never managed to make it valid.
    """
    state.enter_node("finalize")
    with _span("finalize"):
        logger.debug(
            "graph finalized",
            extra={
                "operation": "graph.finalize",
                "valid": not state.validation_errors,
                "repairCount": state.repair_count,
            },
        )
    return state
