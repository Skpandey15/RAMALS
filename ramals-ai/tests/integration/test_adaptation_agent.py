"""Adaptation Agent V1 minimization, validation and bounded execution tests."""

from __future__ import annotations

import json
import uuid

from ramals_ai.adaptation.agent import AdaptationAgent
from ramals_ai.adaptation.minimizer import minimize
from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import AIRequestEnvelope, TrustLevel
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider


class ScriptedProvider(FakeProvider):
    def __init__(self, payload: str) -> None:
        super().__init__()
        self.payload = payload
        self.prompts: list[tuple[Message, ...]] = []

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        self.prompts.append(request.messages)
        return ProviderResponse(
            text=self.payload,
            input_tokens=100,
            output_tokens=40,
            cached_input_tokens=0,
        )


def envelope() -> AIRequestEnvelope:
    return AIRequestEnvelope.model_validate(
        {
            "contractVersion": "1.0",
            "interactionId": str(uuid.uuid7()),
            "requestId": str(uuid.uuid4()),
            "learner": {"learnerRef": "must-not-leak", "locale": "en-IN"},
            "learningContext": {
                "skillCode": "KAFKA_PARTITIONING",
                "masteryScore": "0.7200",
                "evidenceConfidence": "0.6800",
                "masteryStatus": "NEEDS_PRACTICE",
                "prerequisites": ["KAFKA_TOPIC"],
            },
            "domainContext": {
                "domainCode": "KAFKA",
                "domainType": "TECHNOLOGY",
                "curriculumVersion": "v1",
            },
            "learningGoalContext": {
                "goalType": "LEARNING_DOMAIN",
                "goalCode": "KAFKA",
                "goalVersion": "v1",
            },
            "constraints": {"interactionClass": "INTERACTIVE_AI", "deadlineMs": 8000},
            "requestedCapability": "NEXT_ACTION",
        }
    )


GOOD_OUTPUT = json.dumps(
    {
        "skillCode": "KAFKA_PARTITIONING",
        "recommendedAction": "PRACTICE",
        "rationale": "Practice the skill before advancing.",
    }
)


def test_adaptation_proposal_is_non_authoritative() -> None:
    provider = ScriptedProvider(GOOD_OUTPUT)
    agent = AdaptationAgent(LLMGateway(provider), route=ModelRoute.CI_FAKE)

    proposal = agent.propose(envelope(), deadline=Deadline.in_ms(8000))

    assert proposal.trustLevel is TrustLevel.NON_AUTHORITATIVE
    assert proposal.agentType.value == "ADAPTATION"
    assert proposal.proposal["recommendedAction"] == "PRACTICE"


def test_adaptation_prompt_contains_exactly_minimized_context() -> None:
    request = envelope()
    minimized = minimize(request)

    assert minimized == {
        "skillCode": "KAFKA_PARTITIONING",
        "masteryScore": "0.7200",
        "evidenceConfidence": "0.6800",
        "masteryStatus": "NEEDS_PRACTICE",
        "prerequisites": ["KAFKA_TOPIC"],
        "domain": {
            "domainCode": "KAFKA",
            "domainType": "TECHNOLOGY",
            "curriculumVersion": "v1",
        },
        "learningGoal": {
            "goalType": "LEARNING_DOMAIN",
            "goalCode": "KAFKA",
            "goalVersion": "v1",
        },
        "requestedCapability": "NEXT_ACTION",
    }

    provider = ScriptedProvider(GOOD_OUTPUT)
    AdaptationAgent(LLMGateway(provider), route=ModelRoute.CI_FAKE).propose(
        request, deadline=Deadline.in_ms(8000)
    )
    rendered = provider.prompts[-1][1].content
    assert json.dumps(dict(minimized), sort_keys=True, ensure_ascii=False) in rendered
    assert "must-not-leak" not in rendered
    assert "requestId" not in rendered
