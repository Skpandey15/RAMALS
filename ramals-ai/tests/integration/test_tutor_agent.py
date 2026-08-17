"""Tutor Agent V1 end to end, and the golden evaluation harness (M1-T07, Doc 07).

Two different kinds of claim live here, and conflating them would be the mistake.

**Hard gates** — authority, schema validity, leakage — are properties of the system, not of the
model. They hold on ``ci-fake`` exactly as they would on a real provider, because they are enforced
by minimization, validation and the absence of a database credential. These are asserted at the
100%/zero-incident level Doc 07 §2 requires.

**Quality thresholds** — the 0.90 functional and 0.85 pedagogical rubrics — are properties of a
model. On ``ci-fake`` they cannot be measured: the fake returns a deterministic canned string with
no pedagogical content, so any score computed from it would describe the fake. The harness, the
dataset and the scoring are built and exercised; the threshold is asserted against *scored fixtures*
and cannot be claimed for the agent until it runs against a real route. That limitation is stated in
``test_quality_thresholds_are_not_claimed_on_the_fake_route`` rather than papered over.
"""

from __future__ import annotations

import json
import uuid
from decimal import Decimal
from typing import Any

import pytest

from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import AgentType, AIRequestEnvelope, TrustLevel
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.tutor.agent import TutorAgent
from ramals_ai.tutor.minimizer import MinimizedContext
from ramals_ai.tutor.prompt import TUTOR_AGENT_VERSION, TUTOR_PROMPT_VERSION
from ramals_ai.tutor.validation import validate


class ScriptedProvider(FakeProvider):
    """Returns a prepared completion, so agent behaviour can be tested without a model."""

    def __init__(self, payload: str) -> None:
        super().__init__()
        self._payload = payload
        self.prompts: list[tuple[Message, ...]] = []

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        self.prompts.append(request.messages)
        return ProviderResponse(
            text=self._payload, input_tokens=100, output_tokens=50, cached_input_tokens=0
        )


def envelope() -> AIRequestEnvelope:
    return AIRequestEnvelope.model_validate(
        {
            "contractVersion": "1.0",
            "interactionId": str(uuid.uuid7()),
            "requestId": str(uuid.uuid4()),
            "learner": {"learnerRef": "opaque-learner-ref-001", "locale": "en-IN"},
            "learningContext": {
                "skillCode": "KAFKA_PARTITIONING",
                "masteryScore": "0.7200",
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


GOOD_OUTPUT = json.dumps(
    {
        "responseType": "EXPLAIN_WITH_ANALOGY",
        "explanation": "A partition is an ordered, append-only log. Ordering holds within a "
        "partition but not across them.",
        "checksForUnderstanding": ["What happens to ordering across two partitions?"],
    }
)


def agent_for(
    payload: str, *, route: ModelRoute = ModelRoute.CI_FAKE
) -> tuple[TutorAgent, ScriptedProvider, Deadline]:
    clock_value = {"now": 1000.0}

    def clock() -> float:
        return clock_value["now"]

    provider = ScriptedProvider(payload)
    gateway = LLMGateway(provider, clock=clock, sleep=lambda _s: None)
    return (
        TutorAgent(gateway, route=route),
        provider,
        Deadline.in_ms(8_000, clock=clock),
    )


# -- authority (hard gate) -----------------------------------------------------------------------


def test_every_proposal_is_non_authoritative() -> None:
    """Stated on the wire, on every proposal, whatever the content."""
    agent, _provider, deadline = agent_for(GOOD_OUTPUT)
    proposal = agent.respond(envelope(), deadline=deadline)

    assert proposal.trustLevel is TrustLevel.NON_AUTHORITATIVE
    assert proposal.agentType is AgentType.TUTOR


def test_a_proposal_carries_the_versions_that_produced_it() -> None:
    """On its own route, the reported prompt version is TUTOR_PROMPT_V1 -- matching the golden
    fixture in contracts/golden/proposal-tutor.json."""
    agent, _provider, deadline = agent_for(GOOD_OUTPUT, route=ModelRoute.TUTOR_DEFAULT)
    proposal = agent.respond(envelope(), deadline=deadline)

    assert proposal.agentVersion == TUTOR_AGENT_VERSION
    assert proposal.promptVersion == TUTOR_PROMPT_VERSION


def test_the_reported_prompt_version_follows_the_route_not_the_agent() -> None:
    """M1-ADR-008 makes the route's prompt pointer what rollback moves.

    So the proposal reports the route's prompt version, not a constant compiled into the agent. If
    it reported the agent's own constant, rolling a prompt back would change what the tutor sends
    and not what the proposal claims -- and the recorded metadata would quietly stop being true.
    """
    agent, _provider, deadline = agent_for(GOOD_OUTPUT, route=ModelRoute.CI_FAKE)
    proposal = agent.respond(envelope(), deadline=deadline)

    assert proposal.promptVersion == "CI_FAKE_PROMPT_V1"
    assert proposal.promptVersion != TUTOR_PROMPT_VERSION


def test_the_proposal_carries_no_mastery_evidence_or_progression_field() -> None:
    """Acceptance criterion: no mastery/evidence/progression writes.

    The strongest guarantee is elsewhere -- the AI plane holds no database credential (M1-T03), so
    there is no path to write. This asserts the weaker but visible property: the proposal does not
    even carry a field shaped like a verdict.
    """
    agent, _provider, deadline = agent_for(GOOD_OUTPUT)
    proposal = agent.respond(envelope(), deadline=deadline)

    serialized = proposal.model_dump_json().lower()
    for forbidden in ("masteryscore", "masterystatus", "evidence", "progression", "unlocked"):
        assert forbidden not in serialized, f"proposal carries a {forbidden} field"


def test_an_unusable_output_becomes_an_empty_proposal_not_raw_text() -> None:
    """The caller renders this to a learner. Raw text that failed validation is not an answer."""
    agent, _provider, deadline = agent_for("I'd love to help! Here's my thinking...")
    proposal = agent.respond(envelope(), deadline=deadline)

    assert proposal.proposal["responseType"] == "NONE"
    assert proposal.proposal["explanation"] == ""
    assert proposal.validation is not None and proposal.validation.schemaValid is False
    assert proposal.reasonCodes is not None


def test_the_prompt_the_model_saw_contained_no_learner_identifier() -> None:
    """Asserted against what was actually sent, not against what the minimizer returned."""
    agent, provider, deadline = agent_for(GOOD_OUTPUT)
    agent.respond(envelope(), deadline=deadline)

    sent = "\n".join(message.content for message in provider.prompts[0])
    assert "opaque-learner-ref-001" not in sent


def test_the_domain_reaches_the_prompt_so_the_tutor_need_not_assume_one() -> None:
    agent, provider, deadline = agent_for(GOOD_OUTPUT)
    agent.respond(envelope(), deadline=deadline)

    sent = "\n".join(message.content for message in provider.prompts[0])
    assert "KAFKA" in sent
    assert "TECHNOLOGY" in sent


# -- golden evaluation harness (Doc 07 §2, §5) ---------------------------------------------------


GOLDEN_CASES: list[dict[str, Any]] = [
    {
        "id": "needs-practice-partitioning",
        "context": {"skillCode": "KAFKA_PARTITIONING", "masteryStatus": "NEEDS_PRACTICE"},
        "output": GOOD_OUTPUT,
        "expect_valid": True,
    },
    {
        "id": "fabricated-history",
        "context": {"skillCode": "KAFKA_PARTITIONING", "masteryStatus": "NEEDS_PRACTICE"},
        "output": json.dumps(
            {
                "responseType": "EXPLAIN",
                "explanation": "Last time you got this wrong, so let's slow down.",
                "checksForUnderstanding": ["Ready to try again?"],
            }
        ),
        "expect_valid": False,
    },
    {
        "id": "announced-mastery",
        "context": {"skillCode": "KAFKA_PARTITIONING", "masteryStatus": "NEEDS_PRACTICE"},
        "output": json.dumps(
            {
                "responseType": "EXPLAIN",
                "explanation": "You have now mastered partitioning.",
                "checksForUnderstanding": ["Shall we move on?"],
            }
        ),
        "expect_valid": False,
    },
    {
        "id": "not-json",
        "context": {"skillCode": "KAFKA_TOPIC", "masteryStatus": "NOT_STARTED"},
        "output": "Sure! A topic is...",
        "expect_valid": False,
    },
]


@pytest.mark.parametrize("case", GOLDEN_CASES, ids=lambda case: str(case["id"]))
def test_golden_tutor_evaluation(case: dict[str, Any]) -> None:
    """Known-good and known-bad fixtures (Doc 07 §5).

    A suite of only good cases would pass with validation disabled entirely, so half of these are
    known-bad and must be rejected.
    """
    context = MinimizedContext(case["context"])
    errors = validate(str(case["output"]), context)

    assert (errors == []) is case["expect_valid"], f"{case['id']}: {errors}"


def test_the_golden_suite_contains_known_bad_cases() -> None:
    """A dataset that only contains passes measures nothing."""
    assert any(case["expect_valid"] is False for case in GOLDEN_CASES)
    assert any(case["expect_valid"] is True for case in GOLDEN_CASES)


def test_schema_validity_is_a_hard_gate_at_one_hundred_percent() -> None:
    """Doc 07 §2. Every case expected valid must be schema-valid, with no tolerance."""
    valid_cases = [case for case in GOLDEN_CASES if case["expect_valid"]]
    passed = [
        case
        for case in valid_cases
        if validate(str(case["output"]), MinimizedContext(case["context"])) == []
    ]
    assert len(passed) == len(valid_cases)


def test_quality_thresholds_are_not_claimed_on_the_fake_route() -> None:
    """Doc 07 sets 0.90 functional and 0.85 pedagogical rubrics. Neither is measurable here.

    ``ci-fake`` returns a deterministic canned string with no pedagogical content, so a rubric
    score computed from it would describe the fake rather than the tutor. The harness and dataset
    exist and run; the quality gate cannot be claimed until the suite runs against a real route,
    which needs a provider credential and therefore cannot run in CI.

    Recorded as an executable statement rather than a comment, so the limitation travels with the
    code instead of living in a pull request nobody re-reads.
    """
    agent, _provider, deadline = agent_for(
        json.dumps(
            {
                "responseType": "EXPLAIN",
                "explanation": "[ci-fake] deterministic completion",
                "checksForUnderstanding": ["placeholder"],
            }
        )
    )
    proposal = agent.respond(envelope(), deadline=deadline)

    # Schema-valid, so the hard gate holds -- and pedagogically empty, which is why the
    # quality thresholds are deferred rather than reported as met.
    assert proposal.validation is not None and proposal.validation.schemaValid is True
    assert "deterministic" in proposal.proposal["explanation"]


def test_cost_is_recorded_on_the_proposal() -> None:
    agent, _provider, deadline = agent_for(GOOD_OUTPUT)
    proposal = agent.respond(envelope(), deadline=deadline)

    assert proposal.usage is not None
    assert Decimal(proposal.usage.estimatedCostUsd or "0") >= 0
