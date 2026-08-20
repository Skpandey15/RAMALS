"""Graph state (Doc 02 §2).

``AgentState`` is working memory for one graph run and **carries no authority**. Nothing in it
decides anything about a learner: mastery, progression and evidence are computed by the
deterministic engines in Spring, from data Spring already holds. A field here is a note the graph
made to itself while producing a proposal.

That is not a stylistic point. If graph state were ever treated as authoritative, a model would be
able to influence a learner's record by writing a plausible number into a dictionary, and the entire
MVP-0 control boundary would be worth nothing. ``test_graph_state_is_non_authoritative`` asserts the
type carries no field that could be mistaken for a verdict.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any

from ramals_ai.contracts.generated import AgentType, InteractionClass
from ramals_ai.gateway.budget import Deadline
from ramals_ai.graph.limits import CeilingExceeded, Ceilings
from ramals_ai.prompting.templates import BuiltPrompt, PromptTemplateId
from ramals_ai.telemetry.correlation import new_agent_run_id


@dataclass
class AgentState:
    """Working memory for one bounded graph run.

    Mutable by design — the graph advances it — but every counter only ever increases, and every
    increase is checked against a ceiling resolved before the run began.
    """

    # -- identity, carried through so every span and log line is correlated -----------------------
    contract_version: str
    interaction_id: str
    request_id: str
    proposal_id: str

    agent_type: AgentType
    agent_version: str

    prompt: BuiltPrompt
    """The prompt that was built for this run, with the identity of the artifact that built it.

    Held as one value rather than as a version string beside the messages, so the identity recorded
    on the proposal is necessarily the identity of what was sent. A separate string is a claim; this
    is the thing itself.
    """

    # -- the request ------------------------------------------------------------------------------
    minimized_learning_context: dict[str, Any]
    """Exactly what Spring decided this agent may see. The graph never widens it."""

    policy_constraints: dict[str, Any]

    deadline: Deadline
    """Absolute, from the caller. Retries and repairs spend it; nothing here extends it."""

    ceilings: Ceilings
    interaction_class: InteractionClass = InteractionClass.INTERACTIVE_AI

    agent_run_id: str = field(default_factory=new_agent_run_id)
    """One orchestrated execution of the graph (Observability HLD §9).

    Not the same as any identifier already here. ``requestId`` is one transport attempt and
    ``interactionId`` is one learner action; a run is neither -- an interaction can involve several
    agents, and a retried request produces several runs. Without it, "which run made this model
    call" is unanswerable from the logs of a busy process.

    Defaulted rather than required so a state constructed directly still carries one. An absent
    identifier reads as missing, which is recoverable; a shared or reused one reads as evidence that
    two things were the same execution, which is not.
    """

    # -- budgets ----------------------------------------------------------------------------------
    token_budget: int = 0
    cost_budget_usd: Decimal = Decimal("0.000000")
    """The route's hard ceiling, read from the gateway.

    Doc 02 §4 declares no cost constant of its own, so this is never a local number.
    """

    cost_spent_usd: Decimal = Decimal("0.000000")
    input_tokens: int = 0
    cached_input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: int = 0
    """Sum of governed model-call durations, not full HTTP or agent/request latency."""

    # -- counters, checked against ceilings -------------------------------------------------------
    node_execution_count: int = 0
    repair_cycle_count: int = 0
    model_call_count: int = 0

    # -- accumulated work -------------------------------------------------------------------------
    tool_results: list[dict[str, Any]] = field(default_factory=list)
    """Untrusted data (Doc 02 §5). A tool result is evidence of what a tool said, nothing more."""

    validation_errors: list[str] = field(default_factory=list)
    final_proposal: dict[str, Any] | None = None

    trace: list[str] = field(default_factory=list)
    """Node names in execution order. The cheapest possible answer to "what did this run do"."""

    @property
    def prompt_version(self) -> str:
        """The revision that produced the messages for this run."""
        return self.prompt.version

    @property
    def prompt_template_id(self) -> PromptTemplateId:
        """Which prompt ran. Recorded alongside the version, because the version alone does not say.

        Two of the assessment agent's prompts share a version; without the template id a recorded
        ``ASSESSMENT_PROMPT_V1`` cannot distinguish generating an item from evaluating a response.
        """
        return self.prompt.template_id

    # -- bounded advancement ----------------------------------------------------------------------

    def enter_node(self, node: str) -> None:
        """Records a node execution and enforces the node-execution ceiling.

        Called on entry rather than exit, so a node that hangs or throws has still been counted.
        Counting on success would let a failing node loop forever without ever incrementing.
        """
        if self.node_execution_count >= self.ceilings.max_node_executions:
            raise CeilingExceeded(
                "node execution", self.ceilings.max_node_executions, self.node_execution_count + 1
            )
        self.node_execution_count += 1
        self.trace.append(node)

    def record_repair(self) -> None:
        if self.repair_cycle_count >= self.ceilings.max_repair_cycles:
            raise CeilingExceeded(
                "repair cycle", self.ceilings.max_repair_cycles, self.repair_cycle_count + 1
            )
        self.repair_cycle_count += 1

    def ensure_model_call_permitted(self) -> None:
        """Checks the model-call ceiling before any provider dispatch can occur."""
        if self.model_call_count >= self.ceilings.max_model_calls:
            raise CeilingExceeded(
                "model call", self.ceilings.max_model_calls, self.model_call_count + 1
            )

    def record_model_call(
        self,
        cost_usd: Decimal,
        *,
        input_tokens: int = 0,
        cached_input_tokens: int = 0,
        output_tokens: int = 0,
        latency_ms: int = 0,
    ) -> None:
        """Counts a model call and the money it cost.

        The gateway refuses anything over the route's per-call or request-level ceiling before
        dispatch. This post-response check remains a defensive invariant for provider-reported
        usage that is unexpectedly larger than the pre-dispatch projection. Usage is accumulated
        here so the proposal reports the complete bounded graph run, including repair calls.

        ``latency_ms`` is the sum of model-call durations returned by the gateway, including repair
        calls; it does not represent full end-to-end request latency.
        """
        self.ensure_model_call_permitted()
        self.model_call_count += 1
        self.cost_spent_usd += cost_usd
        self.input_tokens += input_tokens
        self.cached_input_tokens += cached_input_tokens
        self.output_tokens += output_tokens
        self.latency_ms += latency_ms
        if self.cost_budget_usd > 0 and self.cost_spent_usd > self.cost_budget_usd:
            raise CeilingExceeded(
                "request cost",
                int(self.cost_budget_usd * 1_000_000),
                int(self.cost_spent_usd * 1_000_000),
            )

    def check_deadline(self) -> None:
        self.deadline.raise_if_expired()

    @property
    def node_executions_remaining(self) -> int:
        return max(0, self.ceilings.max_node_executions - self.node_execution_count)
