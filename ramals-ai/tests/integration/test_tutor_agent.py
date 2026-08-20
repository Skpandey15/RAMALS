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

from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import AgentType, AIRequestEnvelope, TrustLevel
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.tutor.agent import TutorAgent
from ramals_ai.tutor.prompt import TUTOR_AGENT_VERSION, TUTOR_PROMPT_VERSION


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


# -- golden evaluation harness ---------------------------------------------------------------------
#
# The tutor golden cases used to live here as a Python literal. They now live in
# evaluation/datasets/tutor.json and are exercised by tests/integration/test_evaluation_gates.py,
# for the two reasons M1-ADR-009 depends on: a recorded result must be able to name the dataset
# version it scored against, and a dataset change must be reviewable independently of a model
# change. Neither is possible while the data is a literal inside a test file.
#
# What stays here is the statement that quality thresholds are not claimed on this route, because it
# is a fact about *this* agent test rather than about the dataset.


def test_quality_thresholds_are_not_claimed_on_the_fake_route() -> None:
    """Doc 07 sets 0.90 functional and 0.85 pedagogical rubrics. Neither is measurable here.

    ``ci-fake`` returns a deterministic canned string with no pedagogical content, so a rubric score
    computed from it would describe the fake rather than the tutor. The harness and datasets exist
    and run; the quality gate cannot be claimed until the suite runs against a real route, which
    needs a provider credential and therefore cannot run in CI.

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
    assert proposal.usage.inputTokens == 100
    assert proposal.usage.cachedInputTokens == 0
    assert proposal.usage.outputTokens == 50
    assert proposal.usage.latencyMs is not None and proposal.usage.latencyMs >= 0
    assert Decimal(proposal.usage.estimatedCostUsd or "0") >= 0
