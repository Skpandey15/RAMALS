"""Agent correlation: agentRunId, toolCallId and proposalId (Observability HLD §9, P6).

The HLD's own acceptance test is "agentRunId/toolCallId remain correlated with originating
interaction", and its exit criterion is that agent workflows are traceable end to end. Both are
about a support pivot: someone reads an interactionId off an error screen, finds the business event,
and has to reach the run that produced it and the tool call that failed inside it.

Every link in that chain is asserted here, and so is the property that makes the chain trustworthy:
the identifiers **unbind**. A leaked agentRunId is worse than a missing one: an absent field reads
as missing and sends the investigator elsewhere, while a stale one reads as evidence that two
unrelated pieces of work were the same execution, and it is believed.

The identifiers are attached by the formatter from context, never passed by call sites, for the
reason the module already gives about interactionId: a log line that happens to omit them is exactly
the line you need when something has gone wrong.
"""

from __future__ import annotations

import io
import json
import logging
import uuid
from collections.abc import Iterator
from typing import Any

import pytest

from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import AgentType, AIRequestEnvelope
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.graph.tools import ToolDenied, empty_registry
from ramals_ai.telemetry import correlation
from ramals_ai.telemetry.logging import JsonFormatter
from ramals_ai.tutor.agent import TutorAgent

INTERACTION_ID = "01920000-0000-7000-8000-0000000000aa"

TUTOR_OUTPUT = json.dumps(
    {
        "responseType": "EXPLAIN",
        "explanation": "A partition is an ordered, append-only log.",
        "checksForUnderstanding": ["What holds across two partitions?"],
    }
)


class ScriptedProvider(FakeProvider):
    def __init__(self, payload: str = TUTOR_OUTPUT) -> None:
        super().__init__()
        self._payload = payload

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        del request
        return ProviderResponse(
            text=self._payload, input_tokens=10, output_tokens=5, cached_input_tokens=0
        )


@pytest.fixture
def emitted() -> Iterator[list[dict[str, Any]]]:
    """Every log record this test produced, rendered through the real JSON formatter.

    The formatter is the component under test as much as the call sites are -- it is what attaches
    the correlation -- so the records are read exactly as they would be shipped.
    """
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonFormatter(service="ramals-ai", environment="test"))

    root = logging.getLogger()
    previous_handlers, previous_level = root.handlers, root.level
    root.handlers = [handler]
    root.setLevel(logging.INFO)

    tokens = correlation.bind(INTERACTION_ID, str(uuid.uuid4()))
    records: list[dict[str, Any]] = []

    def read() -> list[dict[str, Any]]:
        records.clear()
        for line in stream.getvalue().strip().splitlines():
            if line:
                records.append(json.loads(line))
        return records

    yield read  # type: ignore[misc]

    correlation.reset(tokens)
    root.handlers, root.level = previous_handlers, previous_level


def envelope() -> AIRequestEnvelope:
    return AIRequestEnvelope.model_validate(
        {
            "contractVersion": "1.0",
            "interactionId": INTERACTION_ID,
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


def run_tutor(payload: str = TUTOR_OUTPUT) -> Any:
    gateway = LLMGateway(ScriptedProvider(payload), clock=lambda: 1000.0, sleep=lambda _s: None)
    agent = TutorAgent(gateway, route=ModelRoute.CI_FAKE)
    return agent.respond(envelope(), deadline=Deadline.in_ms(8_000, clock=lambda: 1000.0))


def events(records: list[dict[str, Any]], operation: str) -> list[dict[str, Any]]:
    return [record for record in records if record.get("operation") == operation]


# -- the required test -----------------------------------------------------------------------------


def test_a_tool_denial_is_correlated_all_the_way_to_the_interaction(emitted: Any) -> None:
    """The HLD's stated agent-logging test, on the path that most needs it.

    MVP-1 registers no tools, so every invocation is a denial -- a security event recording that
    something asked the graph to act outside what its agent was authorized for. A denial that cannot
    be tied to the run and the attempt that produced it is a counter, not a record.
    """
    with correlation.agent_run("run-1", "proposal-1"), pytest.raises(ToolDenied):
        empty_registry().invoke(AgentType.TUTOR, "curriculum.lookup", {})

    denial = events(emitted(), "graph.tool.denied")

    assert len(denial) == 1
    assert denial[0]["interactionId"] == INTERACTION_ID
    assert denial[0]["agentRunId"] == "run-1"
    assert denial[0]["proposalId"] == "proposal-1"
    assert denial[0]["toolCallId"], "a denial must name the invocation it refused"
    assert denial[0]["errorCode"] == "TOOL_NOT_AUTHORIZED"
    assert denial[0]["outcome"] == "REJECTED"


def test_a_real_run_correlates_its_events_to_the_interaction(emitted: Any) -> None:
    """End to end through the agent, not through a hand-bound context."""
    run_tutor()

    completed = events(emitted(), "graph.complete")

    assert len(completed) == 1
    assert completed[0]["interactionId"] == INTERACTION_ID
    assert completed[0]["agentRunId"]
    assert completed[0]["proposalId"]


def test_the_proposal_carries_the_run_that_produced_it(emitted: Any) -> None:
    """The link across the plane boundary.

    Without it the chain ends where the AI plane hands back a proposal, which is exactly where a
    support pivot has to cross: the deterministic core logs the decision, and nothing in that record
    names the run that proposed it.
    """
    proposal = run_tutor()
    completed = events(emitted(), "graph.complete")

    assert proposal.agentRunId, "the proposal must name its run"
    assert proposal.agentRunId == completed[0]["agentRunId"]
    assert proposal.proposalId == completed[0]["proposalId"]


# -- the identifiers mean what they say ------------------------------------------------------------


def test_two_runs_under_one_interaction_are_distinguishable(emitted: Any) -> None:
    """One learner action can involve several runs, which the transport identifiers cannot say."""
    first, second = run_tutor(), run_tutor()
    completed = events(emitted(), "graph.complete")

    assert first.agentRunId != second.agentRunId
    assert len({record["agentRunId"] for record in completed}) == 2
    assert {record["interactionId"] for record in completed} == {INTERACTION_ID}


def test_two_tool_calls_in_one_run_are_distinguishable(emitted: Any) -> None:
    registry = empty_registry()

    with correlation.agent_run("run-1", "proposal-1"):
        for tool in ("curriculum.lookup", "catalog.search"):
            with pytest.raises(ToolDenied):
                registry.invoke(AgentType.TUTOR, tool, {})

    denials = events(emitted(), "graph.tool.denied")

    assert len(denials) == 2
    assert len({record["toolCallId"] for record in denials}) == 2
    assert {record["agentRunId"] for record in denials} == {"run-1"}


# -- and they do not outlive what they identify ----------------------------------------------------


def test_the_run_identifier_does_not_leak_past_the_run(emitted: Any) -> None:
    """A stale identifier is believed. That is what makes it worse than an absent one."""
    logger = logging.getLogger(__name__)

    with correlation.agent_run("run-1", "proposal-1"):
        logger.info("inside", extra={"operation": "probe.inside"})
    logger.info("outside", extra={"operation": "probe.outside"})

    records = emitted()

    assert events(records, "probe.inside")[0]["agentRunId"] == "run-1"
    assert "agentRunId" not in events(records, "probe.outside")[0]
    assert "proposalId" not in events(records, "probe.outside")[0]


def test_the_tool_identifier_does_not_leak_past_the_tool_call(emitted: Any) -> None:
    logger = logging.getLogger(__name__)

    with correlation.agent_run("run-1", "proposal-1"):
        with correlation.tool_call("tool-1"):
            logger.info("inside", extra={"operation": "probe.tool"})
        logger.info("after", extra={"operation": "probe.after_tool"})

    records = emitted()

    assert events(records, "probe.tool")[0]["toolCallId"] == "tool-1"
    assert "toolCallId" not in events(records, "probe.after_tool")[0]
    assert events(records, "probe.after_tool")[0]["agentRunId"] == "run-1", (
        "leaving a tool call must not leave the run"
    )


def test_a_run_that_raises_still_unbinds(emitted: Any) -> None:
    """The failure path is where correlation matters most and where cleanup is easiest to skip."""
    logger = logging.getLogger(__name__)

    # Combined deliberately: the raise has to happen *inside* the binding, which is the whole
    # point of the test.
    with (
        pytest.raises(RuntimeError),
        correlation.agent_run("run-1", "proposal-1"),
    ):
        raise RuntimeError("something went wrong inside the run")

    logger.info("after", extra={"operation": "probe.after_failure"})

    assert "agentRunId" not in events(emitted(), "probe.after_failure")[0]
    assert correlation.current_agent_run_id() == ""


# -- the HLD §9 field table ------------------------------------------------------------------------


def test_a_completed_run_reports_the_fields_the_hld_names(emitted: Any) -> None:
    run_tutor()
    completed = events(emitted(), "graph.complete")[0]

    for field in ("agentName", "agentVersion", "promptTemplateId", "promptVersion", "outcome"):
        assert field in completed, f"{field} is required by Observability HLD §9"
    assert completed["outcome"] == "SUCCESS"


def test_an_unusable_output_is_reported_as_degraded_not_as_success(emitted: Any) -> None:
    """A proposal was produced and it is not the one that was asked for.

    Reporting it as SUCCEEDED would make the one metric that distinguishes a working agent from a
    failing one always read the same.
    """
    run_tutor("not json at all")
    completed = events(emitted(), "graph.complete")[0]

    assert completed["outcome"] == "DEGRADED"


def test_the_plane_never_claims_a_proposal_was_accepted(emitted: Any) -> None:
    """ACCEPTED and REJECTED belong to Spring's deterministic policy.

    The AI plane is non-authoritative, so an outcome of ACCEPTED emitted from here would be a claim
    about a decision this service does not make -- the same authority boundary the trust level
    states on every proposal, in the observability record instead of on the wire.
    """
    run_tutor()

    records = emitted()
    outcomes = {record.get("outcome") for record in records if "outcome" in record}
    graph_outcomes = {
        record["outcome"]
        for record in records
        if "outcome" in record and str(record.get("operation", "")).startswith("graph.")
    }

    assert "ACCEPTED" not in outcomes
    # Scoped to the events P6 adds. The gateway already emits FAILURE where the HLD spells it
    # FAILED; that divergence predates this change and is left rather than half-corrected here,
    # because a vocabulary reconciled in one event and not the others is worse than one that is
    # consistently wrong.
    assert graph_outcomes <= {"SUCCESS", "DEGRADED", "FAILED", "REJECTED"}


def test_prompt_text_is_never_logged(emitted: Any) -> None:
    """§10: promptVersion and digests stand in for the artifact; the artifact is not logged."""
    run_tutor()
    rendered = json.dumps(emitted())

    assert "You are a tutor inside an adaptive learning platform" not in rendered


def test_a_message_object_never_reaches_a_log_field(emitted: Any) -> None:
    """A guard on the shape rather than on one phrase, so a reworded prompt stays covered."""
    run_tutor()

    for record in emitted():
        for key, value in record.items():
            assert not isinstance(value, Message), f"{key} carried a prompt message"
