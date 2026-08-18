"""Independent budgets for bounded graph runs (Doc 02 §4).

The graph has four separate controls: node executions, repair cycles, model calls, and the
caller-owned absolute deadline. The first three are counted here; the deadline is represented by
``gateway.budget.Deadline`` and is deliberately not converted into a graph-local number.

An agent loop with no ceiling is not a bug that shows up in testing — it is a bill, a hung request,
and a learner staring at a spinner, all discovered in production at once.

One constant is deliberately absent. Doc 02 §4 states that per-request cost is governed by the
Doc 04 route ceiling and that *this document carries no independent cost constant*. So the graph
reads the route's ceiling through the gateway rather than declaring its own, and
``test_no_independent_cost_constant`` asserts that no such constant reappears here. Two numbers for
one budget is how they drift apart, and the looser one always wins.
"""

from __future__ import annotations

from dataclasses import dataclass

from ramals_ai.contracts.generated import AgentType

STANDARD_GRAPH_NODE_EXECUTIONS = 6
"""Node executions in the current graph when no repair is needed."""

REPAIR_CYCLE_NODE_EXECUTIONS = 3
"""Additional node executions for one repair: repair, model, and validation."""

MAX_REPAIR_CYCLES = 2
"""Doc 02 §4. A third attempt at repairing the same malformed output is not a strategy."""

MAX_NODE_EXECUTIONS = (
    STANDARD_GRAPH_NODE_EXECUTIONS + REPAIR_CYCLE_NODE_EXECUTIONS * MAX_REPAIR_CYCLES
)
"""Node-execution ceiling derived from the current graph and repair ceiling.

The previous value of 8 was inconsistent with two permitted repairs. The current topology requires
6 + (3 * 2) = 12 executions, which permits exactly those two repair cycles.
"""

REPAIR_ROUTE_RESERVE = REPAIR_CYCLE_NODE_EXECUTIONS + 1
"""Nodes needed after validation to repair and finalize: repair, model, validate, finalize."""

_MODEL_CALL_CEILINGS: dict[AgentType, int] = {
    AgentType.TUTOR: 3,
    AgentType.DIAGNOSTIC: 3,
    AgentType.ADAPTATION: 3,
    # Doc 02 §4 gives assessment proposals one more: generating an item and its rubric is genuinely
    # a longer chain than answering a question.
    AgentType.ASSESSMENT: 4,
}


def model_call_ceiling(agent_type: AgentType) -> int:
    """Model calls this agent may make in one graph run."""
    return _MODEL_CALL_CEILINGS[agent_type]


@dataclass(frozen=True)
class Ceilings:
    """The bounds one graph run must stay inside.

    Resolved once at the start of a run and never recomputed. A ceiling that can be recalculated
    mid-run is a ceiling that can be raised mid-run.
    """

    max_node_executions: int = MAX_NODE_EXECUTIONS
    max_repair_cycles: int = MAX_REPAIR_CYCLES
    max_model_calls: int = 3

    @classmethod
    def for_agent(cls, agent_type: AgentType) -> Ceilings:
        return cls(max_model_calls=model_call_ceiling(agent_type))


class CeilingExceeded(RuntimeError):  # noqa: N818 - names the event, not an error kind
    """A run hit a bound and was stopped.

    Deliberately not a subclass of the gateway's error type. A budget refusal from the gateway means
    "this call was too expensive to make"; this means "this run was allowed to make calls and used
    up its allowance". Conflating them would hide which limit actually bit.
    """

    def __init__(self, control: str, limit: int, attempted: int) -> None:
        super().__init__(f"{control} ceiling exceeded: limit {limit}, attempted {attempted}")
        self.control = control
        self.limit = limit
        self.attempted = attempted
