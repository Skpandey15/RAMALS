"""MCP-3 review requirement #19: the delegated learner-context credential never enters model-visible
LangGraph state, and cannot leak through an accidental repr/log of the trusted context object."""

from __future__ import annotations

from ramals_ai.gateway.budget import Deadline
from ramals_ai.graph.state import AgentState
from ramals_ai.mcp.context import McpExecutionContext


def test_mcp_execution_context_repr_redacts_the_delegated_token() -> None:
    context = McpExecutionContext(
        delegated_context_token="extremely-secret-value",  # noqa: S106
        interaction_id="01973b3a-0000-7000-8000-000000000001",
        deadline=Deadline.in_ms(5_000),
    )
    assert "extremely-secret-value" not in repr(context)
    assert "REDACTED" in repr(context)


def test_agent_state_has_no_field_that_could_carry_a_delegated_context_or_workload_credential() -> (
    None
):
    """Test #19: AgentState (model-visible working memory) structurally has no field this credential
    could occupy -- mirrors the same field-enumeration proof AgentState's own module docstring
    describes for authority in general."""
    field_names = set(AgentState.__dataclass_fields__.keys())
    forbidden_substrings = ("delegated", "workload_token", "mcp_credential", "mcp_token")
    for field_name in field_names:
        lowered = field_name.lower()
        for forbidden in forbidden_substrings:
            assert forbidden not in lowered, (
                f"AgentState.{field_name} must not exist -- the delegated learner-context "
                "credential and workload token must never be model-visible/graph-state fields"
            )
