"""M2-T11 authenticated transport and deterministic-first boundary tests."""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ramals_ai.assessment_evaluation.agent import AssessmentEvaluationAgent
from ramals_ai.config.settings import Environment, ModelRoute, Settings
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.main import create_app
from ramals_ai.security.workload_identity import (
    WorkloadAuthenticationError,
    WorkloadIdentity,
)

PATH = "/internal/v1/assessment-evaluation/propose"
AUTH = {"Authorization": "Bearer good-token"}
ANSWER = "answer-evidence"
RUBRIC = "rubric-evidence"


class _Verifier:
    def verify(self, token: str) -> WorkloadIdentity:
        if token != "good-token":
            raise WorkloadAuthenticationError("WORKLOAD_TOKEN_INVALID")
        return WorkloadIdentity("ramals-core-workload", "ramals-core-workload", 0)


class _Provider(FakeProvider):
    def __init__(self) -> None:
        super().__init__()
        self.calls = 0

    def complete(self, _request: ProviderRequest) -> ProviderResponse:
        self.calls += 1
        return ProviderResponse(
            text=json.dumps(
                {
                    "dimensions": [
                        {
                            "dimensionId": "accuracy",
                            "score": 3,
                            "maxScore": 4,
                            "reason": "The answer makes the required technical connection.",
                            "evidenceIds": [ANSWER, RUBRIC],
                        }
                    ],
                    "feedback": "Explain the quorum threshold more precisely.",
                    "evidenceIds": [ANSWER],
                    "confidence": 0.8,
                }
            ),
            input_tokens=80,
            output_tokens=40,
            cached_input_tokens=0,
        )


@pytest.fixture
def provider() -> _Provider:
    return _Provider()


@pytest.fixture
def app(provider: _Provider) -> FastAPI:
    application = create_app(Settings(environment=Environment.TEST))
    application.state.workload_verifier = _Verifier()
    application.state.agents["assessment_evaluation"] = AssessmentEvaluationAgent(
        LLMGateway(provider), route=ModelRoute.CI_FAKE
    )
    return application


@pytest.fixture
def client(app: FastAPI) -> TestClient:
    return TestClient(app)


def _item(identifier: str, value: str, *, source_version: str, fact_type: str) -> dict[str, Any]:
    return {
        "evidenceId": identifier,
        "sourceType": "ASSESSMENT",
        "sourceVersion": source_version,
        "authority": "AUTHORITATIVE_FACT",
        "factType": fact_type,
        "value": value,
        "observedAt": datetime.now(UTC).isoformat(),
    }


def body() -> dict[str, Any]:
    now = datetime.now(UTC)
    return {
        "contractVersion": "1.0",
        "interactionId": "interaction-evaluation-1",
        "requestId": "request-evaluation-1",
        "constraints": {"interactionClass": "ASSESSMENT_PROPOSAL", "deadlineMs": 8000},
        "evaluationContext": {
            "responseType": "FREE_TEXT",
            "answerVersion": "answer-v1",
            "rubricVersion": "rubric-v1",
            "answerEvidenceId": ANSWER,
            "answerText": "A quorum prevents stale writes.",
            "rubricDimensions": [
                {
                    "dimensionId": "accuracy",
                    "maxScore": 4,
                    "criteria": "The technical mechanism is correct.",
                    "evidenceId": RUBRIC,
                }
            ],
        },
        "groundedContext": {
            "contractVersion": "1.0",
            "contextId": "context-evaluation-1",
            "learnerRef": "opaque-learner",
            "asOf": now.isoformat(),
            "expiresAt": (now + timedelta(minutes=10)).isoformat(),
            "retrievalPolicyVersion": "EVALUATION_POLICY_V1",
            "items": [
                _item(
                    ANSWER,
                    "answer-v1",
                    source_version="answer-v1",
                    fact_type="ANSWER_VERSION",
                ),
                _item(
                    RUBRIC,
                    "accuracy",
                    source_version="rubric-v1",
                    fact_type="RUBRIC_DIMENSION",
                ),
            ],
        },
    }


def test_operation_requires_workload_identity(client: TestClient) -> None:
    assert client.post(PATH, json=body()).status_code == 401


def test_valid_grounded_free_text_request_returns_non_authoritative_proposal(
    client: TestClient, provider: _Provider
) -> None:
    response = client.post(PATH, json=body(), headers=AUTH)

    assert response.status_code == 200
    assert response.json()["trustLevel"] == "NON_AUTHORITATIVE"
    assert response.json()["proposal"]["answerVersion"] == "answer-v1"
    assert provider.calls == 1


def test_mcq_is_rejected_by_contract_before_provider_dispatch(
    client: TestClient, provider: _Provider
) -> None:
    request = body()
    request["evaluationContext"]["responseType"] = "MCQ"

    response = client.post(PATH, json=request, headers=AUTH)

    assert response.status_code == 422
    assert provider.calls == 0


def test_unbound_rubric_is_rejected_before_provider_dispatch(
    client: TestClient, provider: _Provider
) -> None:
    request = body()
    request["groundedContext"]["items"] = request["groundedContext"]["items"][:1]

    response = client.post(PATH, json=request, headers=AUTH)

    assert response.status_code == 422
    assert response.json()["code"] == "EVALUATION_CONTEXT_EVIDENCE_NOT_GROUNDED"
    assert provider.calls == 0


def test_wrong_interaction_class_is_rejected_before_provider_dispatch(
    client: TestClient, provider: _Provider
) -> None:
    request = body()
    request["constraints"]["interactionClass"] = "FAST"

    response = client.post(PATH, json=request, headers=AUTH)

    assert response.status_code == 422
    assert response.json()["code"] == "EVALUATION_INTERACTION_CLASS_INVALID"
    assert provider.calls == 0
