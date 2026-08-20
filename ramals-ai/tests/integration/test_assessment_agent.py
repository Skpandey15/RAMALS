"""Assessment Agent V1 end to end (M1-T10).

Runs on ``ci-fake`` with a scripted provider, so what is asserted here are properties of the
*system* rather than of a model: which trust level leaves the service, what provenance a candidate
carries, and what happens when the model returns something unusable. Those hold identically on a
real route, because none of them depends on what the model wrote.

What is deliberately not asserted here is item quality. Whether a generated item measures the
objective it claims to is the question M1-ADR-006 requires a human for, and a scripted fixture
cannot answer it.
"""

from __future__ import annotations

import json
import uuid
from typing import Any

from ramals_ai.assessment.agent import (
    EVALUATION_TRUST_LEVEL,
    PROPOSED_CONTENT_TRUST_LEVEL,
    AssessmentAgent,
)
from ramals_ai.assessment.prompt import ASSESSMENT_AGENT_VERSION, ASSESSMENT_PROMPT_VERSION
from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import AgentType, AIRequestEnvelope, TrustLevel
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider


class ScriptedProvider(FakeProvider):
    """Returns a prepared completion, so agent behaviour can be tested without a model."""

    def __init__(self, payload: str) -> None:
        super().__init__()
        self._payload = payload
        self.prompts: list[tuple[Message, ...]] = []

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        self.prompts.append(request.messages)
        return ProviderResponse(
            text=self._payload, input_tokens=200, output_tokens=120, cached_input_tokens=0
        )


def envelope() -> AIRequestEnvelope:
    return AIRequestEnvelope.model_validate(
        {
            "contractVersion": "1.0",
            "interactionId": str(uuid.uuid7()),
            "requestId": str(uuid.uuid4()),
            "learner": {"learnerRef": "opaque-learner-ref-001", "locale": "en-IN"},
            "learningContext": {
                "skillCode": "KAFKA_TOPIC",
                "masteryScore": "0.7200",
                "masteryStatus": "NEEDS_PRACTICE",
                "prerequisites": ["KAFKA_BROKER"],
            },
            "domainContext": {
                "domainCode": "KAFKA",
                "domainType": "TECHNOLOGY",
                "curriculumVersion": "v1",
            },
            "constraints": {"interactionClass": "ASSESSMENT_PROPOSAL", "deadlineMs": 10000},
            "requestedCapability": "PROPOSE_ITEM",
        }
    )


GOOD_ITEM = json.dumps(
    {
        "skillCode": "KAFKA_TOPIC",
        "objectiveCode": "TOPIC_DEFINE",
        "difficulty": "FOUNDATIONAL",
        "stem": "A Kafka topic is best described as:",
        "options": [
            "A durable, named, append-only stream of records",
            "A single mutable database row",
            "A transient in-memory cache",
        ],
        "answerKey": ["A durable, named, append-only stream of records"],
        "rationale": "A topic is an append-only log; the other options describe mutable stores.",
    }
)

GOOD_EVALUATION = json.dumps(
    {
        "skillCode": "KAFKA_TOPIC",
        "indicators": {
            "strong": "Explains why ordering is per-partition and not per-topic.",
            "partial": "Describes a topic as a stream but treats partitions as replicas.",
            "weak": "Treats a topic as a queue that is consumed and emptied.",
        },
        "misconceptions": ["A topic is a queue that drains as consumers read it."],
        "suggestedProbe": "Ask what happens to a record after every consumer has read it.",
    }
)

BROKEN_ITEM = json.dumps(
    {
        "skillCode": "KAFKA_TOPIC",
        "objectiveCode": "TOPIC_DEFINE",
        "difficulty": "FOUNDATIONAL",
        "stem": "A Kafka topic is best described as:",
        "options": ["A durable log", "A database row"],
        "answerKey": ["A partition"],
        "rationale": "The key names something that is not an option.",
    }
)


def agent_for(
    payload: str, *, route: ModelRoute = ModelRoute.CI_FAKE
) -> tuple[AssessmentAgent, ScriptedProvider, Deadline]:
    clock_value = {"now": 1000.0}

    def clock() -> float:
        return clock_value["now"]

    provider = ScriptedProvider(payload)
    gateway = LLMGateway(provider, clock=clock, sleep=lambda _s: None)
    return (
        AssessmentAgent(gateway, route=route),
        provider,
        Deadline.in_ms(10_000, clock=clock),
    )


# -- trust level (hard gate) -----------------------------------------------------------------------


def test_generated_content_is_always_unverified() -> None:
    """M1-ADR-006. Stated on the wire on every proposal, whatever the content -- a candidate that
    arrived looking finished is still a candidate."""
    agent, _provider, deadline = agent_for(GOOD_ITEM)
    proposal = agent.propose(
        envelope(),
        deadline=deadline,
        requested_difficulty="FOUNDATIONAL",
        objectives=("TOPIC_DEFINE",),
    )

    assert proposal.trustLevel is TrustLevel.UNVERIFIED
    assert proposal.agentType is AgentType.ASSESSMENT


def test_evaluation_is_always_formative_only() -> None:
    """M1-ADR-010. There is no parameter that could make it anything else."""
    agent, _provider, deadline = agent_for(GOOD_EVALUATION)
    proposal = agent.evaluate(envelope(), deadline=deadline)

    assert proposal.trustLevel is TrustLevel.FORMATIVE_ONLY


def test_a_broken_item_is_still_unverified_not_rejected_on_the_wire() -> None:
    """A failed generation does not become a trust verdict.

    REJECTED is a state Spring writes after its pipeline refuses content, with the stage recorded.
    This service returning it would be the AI plane deciding a trust state, which is precisely what
    M1-ADR-006 places on the other side of the boundary.
    """
    agent, _provider, deadline = agent_for(BROKEN_ITEM)
    proposal = agent.propose(
        envelope(),
        deadline=deadline,
        requested_difficulty="FOUNDATIONAL",
        objectives=("TOPIC_DEFINE",),
    )

    assert proposal.trustLevel is TrustLevel.UNVERIFIED
    # The refusal is reported as reason codes on an UNVERIFIED proposal, not as a trust state.
    assert proposal.reasonCodes


def test_the_agent_has_exactly_two_trust_levels_and_neither_is_verified() -> None:
    """Structural. Both are module constants with no parameter feeding them, so a promotion path
    cannot appear here without a visible change to a named constant."""
    assert PROPOSED_CONTENT_TRUST_LEVEL is TrustLevel.UNVERIFIED
    assert EVALUATION_TRUST_LEVEL is TrustLevel.FORMATIVE_ONLY
    assert TrustLevel.VERIFIED_CONTENT not in {
        PROPOSED_CONTENT_TRUST_LEVEL,
        EVALUATION_TRUST_LEVEL,
    }


# -- provenance ------------------------------------------------------------------------------------


def test_a_candidate_carries_the_versions_that_wrote_it() -> None:
    """An item outlives the request that produced it. Months later the question is whether a whole
    batch needs re-checking after a prompt change, and the item has to answer it on its own."""
    agent, _provider, deadline = agent_for(GOOD_ITEM, route=ModelRoute.ASSESSMENT_DEFAULT)
    proposal = agent.propose(
        envelope(),
        deadline=deadline,
        requested_difficulty="FOUNDATIONAL",
        objectives=("TOPIC_DEFINE",),
    )

    provenance: dict[str, Any] = proposal.proposal["provenance"]
    assert provenance["agentType"] == "ASSESSMENT"
    assert provenance["agentVersion"] == ASSESSMENT_AGENT_VERSION
    assert provenance["promptVersion"] == ASSESSMENT_PROMPT_VERSION
    assert provenance["modelRoute"] == ModelRoute.ASSESSMENT_DEFAULT.value
    assert provenance["trustLevel"] == "UNVERIFIED"


def test_provenance_survives_an_unusable_generation() -> None:
    """The case where provenance matters most: something went wrong and somebody has to work out
    which prompt version was responsible."""
    agent, _provider, deadline = agent_for("not json at all", route=ModelRoute.ASSESSMENT_DEFAULT)
    proposal = agent.propose(envelope(), deadline=deadline, requested_difficulty="FOUNDATIONAL")

    assert proposal.proposal["provenance"]["promptVersion"] == ASSESSMENT_PROMPT_VERSION
    assert proposal.proposal["provenance"]["trustLevel"] == "UNVERIFIED"


def test_generating_an_item_and_evaluating_a_response_are_different_prompts() -> None:
    """One agent, two templates, and a recorded identity that has to say which one ran.

    Both share ``ASSESSMENT_PROMPT_V1``, so the version alone cannot distinguish them -- which is
    why the identity is (template, version). Generating a bad item and giving bad feedback on a real
    learner's answer are different failures, and ``evaluation/baselines.json`` already treats them
    as different agents; without the template id, both would report the same provenance.
    """
    item_agent, _p1, d1 = agent_for(GOOD_ITEM, route=ModelRoute.CI_FAKE)
    item = item_agent.propose(envelope(), deadline=d1, requested_difficulty="FOUNDATIONAL")

    evaluate_agent, _p2, d2 = agent_for(GOOD_EVALUATION, route=ModelRoute.CI_FAKE)
    evaluation = evaluate_agent.evaluate(envelope(), deadline=d2)

    assert item.promptVersion == ASSESSMENT_PROMPT_VERSION
    assert evaluation.promptVersion == ASSESSMENT_PROMPT_VERSION
    assert (
        item.proposal["provenance"]["promptTemplateId"]
        != evaluation.proposal["provenance"]["promptTemplateId"]
    ), "a shared version makes the template id the only thing that tells them apart"


def test_evaluation_provenance_records_formative_only() -> None:
    agent, _provider, deadline = agent_for(GOOD_EVALUATION)
    proposal = agent.evaluate(envelope(), deadline=deadline)

    assert proposal.proposal["provenance"]["trustLevel"] == "FORMATIVE_ONLY"


# -- unusable output -------------------------------------------------------------------------------


def test_an_unusable_item_becomes_an_explicitly_empty_payload() -> None:
    """Not raw model text. Spring's next act is to store what it receives, and text that looks like
    an item and is not one is the thing a reviewer would have to catch by reading."""
    agent, _provider, deadline = agent_for("Here, have a lovely question about Kafka!")
    proposal = agent.propose(envelope(), deadline=deadline, requested_difficulty="FOUNDATIONAL")

    assert proposal.proposal["stem"] == ""
    assert proposal.proposal["options"] == []
    assert proposal.proposal["answerKey"] == []
    assert proposal.validation is not None
    assert proposal.validation.schemaValid is False


def test_an_unusable_item_reports_why() -> None:
    """An empty payload with no reason is indistinguishable from a working generation that had
    nothing to say."""
    agent, _provider, deadline = agent_for(BROKEN_ITEM)
    proposal = agent.propose(
        envelope(),
        deadline=deadline,
        requested_difficulty="FOUNDATIONAL",
        objectives=("TOPIC_DEFINE",),
    )

    codes = [
        code.root if hasattr(code, "root") else str(code) for code in proposal.reasonCodes or []
    ]
    assert "STRUCTURAL_ANSWER_KEY_NOT_IN_OPTIONS" in codes


def test_an_unusable_evaluation_becomes_an_explicitly_empty_payload() -> None:
    agent, _provider, deadline = agent_for(json.dumps({"score": 0.9}))
    proposal = agent.evaluate(envelope(), deadline=deadline)

    assert proposal.proposal["indicators"] == {}
    assert proposal.proposal["suggestedProbe"] == ""
    assert proposal.trustLevel is TrustLevel.FORMATIVE_ONLY


# -- the prompt the model actually received --------------------------------------------------------


def test_the_item_prompt_offers_the_objectives_it_was_given() -> None:
    agent, provider, deadline = agent_for(GOOD_ITEM)
    agent.propose(
        envelope(),
        deadline=deadline,
        requested_difficulty="FOUNDATIONAL",
        objectives=("TOPIC_DEFINE", "TOPIC_PARTITION"),
    )

    rendered = "\n".join(message.content for message in provider.prompts[0])
    assert "TOPIC_PARTITION" in rendered


def test_propose_and_evaluate_do_not_share_a_prompt() -> None:
    """Different authority, different prompt. A single prompt with a mode flag is a flag that can be
    passed wrongly."""
    agent, provider, deadline = agent_for(GOOD_ITEM)
    agent.propose(envelope(), deadline=deadline, requested_difficulty="FOUNDATIONAL")
    agent.evaluate(envelope(), deadline=deadline)

    first = provider.prompts[0][0].content
    second = provider.prompts[1][0].content
    assert first != second
    assert "candidate multiple-choice assessment item" in first
    assert "not scoring anyone" in second
