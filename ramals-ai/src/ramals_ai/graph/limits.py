"""Execution ceilings for bounded graph runs (Doc 02 §4).

Every ceiling here answers the same question: what stops a graph that has decided to keep going?
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

MAX_GRAPH_STEPS = 8
"""Doc 02 §4. Counts node executions, so a repair loop consumes steps like anything else.

**These two ceilings cannot both be satisfied.** The standard graph in Doc 02 §3 is six node
executions with no repair (load_context, policy_precheck, plan, model_or_tool, validate_output,
finalize). Each repair adds three more (bounded_repair, model_or_tool, validate_output), so one
repair needs 9 and the two that §4 permits need 12. Against a ceiling of 8, the repair loop is
unreachable.

Both numbers are implemented exactly as documented rather than one being widened to fit. The
consequence is visible and tested: ``route_for_validation`` refuses a repair it cannot complete, so
an invalid output finalizes as invalid instead of looping. Resolving it is a governance decision --
raise the step ceiling to at least 12, or define a step as something coarser than a node execution
-- and not one this module should make on its own.
"""

MAX_REPAIR_LOOPS = 2
"""Doc 02 §4. A third attempt at repairing the same malformed output is not a strategy."""

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

    max_steps: int = MAX_GRAPH_STEPS
    max_repairs: int = MAX_REPAIR_LOOPS
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
