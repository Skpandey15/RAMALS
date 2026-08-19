"""Authenticated S0-06 HTTP boundary tests.

The agent implementations are replaced with small proposal-producing doubles here so these tests
exercise routing, authentication, contract serialization, correlation and authority guarantees
without making the result depend on model output.
"""

from __future__ import annotations

import logging
from collections.abc import Iterator

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ramals_ai.config.settings import Environment, Settings
from ramals_ai.contracts.generated import (
    AgentType,
    AIProposalEnvelope,
    AIRequestEnvelope,
    ContractVersion,
    TrustLevel,
    Validation,
)
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.main import create_app
from ramals_ai.security.workload_identity import WorkloadAuthenticationError, WorkloadIdentity


class _Verifier:
    def verify(self, token: str) -> WorkloadIdentity:
        if token != "good-token":
            raise WorkloadAuthenticationError("rejected")
        return WorkloadIdentity("ramals-core", "ramals-core-workload", 0)


class _Agent:
    def __init__(
        self,
        agent_type: AgentType,
        trust_level: TrustLevel,
        evaluate_trust_level: TrustLevel | None = None,
    ) -> None:
        self._agent_type = agent_type
        self._trust_level = trust_level
        self._evaluate_trust_level = evaluate_trust_level

    def _proposal(
        self, envelope: AIRequestEnvelope, trust_level: TrustLevel | None = None
    ) -> AIProposalEnvelope:
        return AIProposalEnvelope(
            contractVersion=ContractVersion("1.0"),
            proposalId=envelope.interactionId,
            agentType=self._agent_type,
            agentVersion="test-agent-v1",
            promptVersion="test-prompt-v1",
            modelRoute="ci-fake",
            trustLevel=trust_level or self._trust_level,
            proposal={"test": True},
            validation=Validation(schemaValid=True, semanticValid=True, repairAttempts=0),
        )

    def propose(self, envelope: AIRequestEnvelope, **_: object) -> AIProposalEnvelope:
        return self._proposal(envelope)

    def respond(self, envelope: AIRequestEnvelope, **_: object) -> AIProposalEnvelope:
        return self._proposal(envelope)

    def evaluate(self, envelope: AIRequestEnvelope, **_: object) -> AIProposalEnvelope:
        return self._proposal(envelope, self._evaluate_trust_level)


class _FailingAgent:
    def __init__(self, failure: Exception) -> None:
        self.failure = failure
        self.deadline: Deadline | None = None

    def respond(
        self, _envelope: AIRequestEnvelope, *, deadline: Deadline, **_: object
    ) -> AIProposalEnvelope:
        self.deadline = deadline
        raise self.failure


@pytest.fixture
def app() -> FastAPI:
    app = create_app(Settings(environment=Environment.TEST))
    app.state.workload_verifier = _Verifier()
    app.state.agents = {
        "diagnostic": _Agent(AgentType.DIAGNOSTIC, TrustLevel.NON_AUTHORITATIVE),
        "tutor": _Agent(AgentType.TUTOR, TrustLevel.NON_AUTHORITATIVE),
        "assessment": _Agent(
            AgentType.ASSESSMENT,
            TrustLevel.UNVERIFIED,
            evaluate_trust_level=TrustLevel.FORMATIVE_ONLY,
        ),
        "adaptation": _Agent(AgentType.ADAPTATION, TrustLevel.NON_AUTHORITATIVE),
    }
    return app


@pytest.fixture
def client(app: FastAPI) -> Iterator[TestClient]:
    with TestClient(app) as started:
        yield started


@pytest.fixture
def request_body() -> dict[str, object]:
    return {
        "contractVersion": "1.0",
        "interactionId": "01920000-0000-7000-8000-0000000000a2",
        "requestId": "01920000-0000-7000-8000-0000000000b2",
        "learner": {"learnerRef": "opaque-learner"},
        "constraints": {
            "interactionClass": "INTERACTIVE_AI",
            "deadlineMs": 8000,
            "maxOutputTokens": 700,
            "allowedTools": [],
        },
        "requestedCapability": "EXPLAIN",
    }


@pytest.mark.parametrize(
    ("path", "agent_type", "trust_level"),
    [
        ("/internal/v1/diagnostic/propose", "DIAGNOSTIC", "NON_AUTHORITATIVE"),
        ("/internal/v1/tutor/respond", "TUTOR", "NON_AUTHORITATIVE"),
        ("/internal/v1/assessment/propose", "ASSESSMENT", "UNVERIFIED"),
        ("/internal/v1/assessment/evaluate", "ASSESSMENT", "FORMATIVE_ONLY"),
        ("/internal/v1/adaptation/propose", "ADAPTATION", "NON_AUTHORITATIVE"),
    ],
)
def test_activated_endpoints_return_contract_proposals(
    client: TestClient,
    request_body: dict[str, object],
    path: str,
    agent_type: str,
    trust_level: str,
) -> None:
    request_payload = dict(request_body)
    if path == "/internal/v1/assessment/propose":
        request_payload["requestedCapability"] = "FOUNDATIONAL"
    response = client.post(
        path,
        json=request_payload,
        headers={
            "Authorization": "Bearer good-token",
            "X-Interaction-ID": request_payload["interactionId"],
        },
    )

    assert response.status_code == 200
    response_body = response.json()
    assert response_body["agentType"] == agent_type
    assert response_body["trustLevel"] == trust_level
    assert response.headers["X-Interaction-ID"] == request_payload["interactionId"]
    assert response.headers["X-Request-ID"]
    assert response.headers["X-Trace-ID"]


def test_internal_routes_require_workload_auth(
    client: TestClient, request_body: dict[str, object]
) -> None:
    response = client.post("/internal/v1/tutor/respond", json=request_body)
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "WORKLOAD_AUTHENTICATION_REQUIRED"


def test_assessment_rejects_missing_deterministic_difficulty(
    client: TestClient, request_body: dict[str, object]
) -> None:
    response = client.post(
        "/internal/v1/assessment/propose",
        json=request_body,
        headers={"Authorization": "Bearer good-token"},
    )
    assert response.status_code == 422
    assert response.json()["code"] == "INVALID_POLICY_INPUT"


def test_boundary_passes_one_absolute_deadline_to_the_agent(
    app: FastAPI, client: TestClient, request_body: dict[str, object]
) -> None:
    agent = _FailingAgent(GatewayError(GatewayErrorCode.PROVIDER_UNAVAILABLE, "test"))
    app.state.agents["tutor"] = agent

    response = client.post(
        "/internal/v1/tutor/respond",
        json=request_body,
        headers={"Authorization": "Bearer good-token"},
    )

    assert response.status_code == 503
    assert response.json()["code"] == "PROVIDER_UNAVAILABLE"
    assert agent.deadline is not None
    assert 0 < agent.deadline.remaining_ms() <= 8000


def test_deadline_failure_is_a_gateway_timeout(
    app: FastAPI, client: TestClient, request_body: dict[str, object]
) -> None:
    app.state.agents["tutor"] = _FailingAgent(
        GatewayError(GatewayErrorCode.DEADLINE_EXCEEDED, "test")
    )

    response = client.post(
        "/internal/v1/tutor/respond",
        json=request_body,
        headers={"Authorization": "Bearer good-token"},
    )

    assert response.status_code == 504
    assert response.json()["code"] == "DEADLINE_EXCEEDED"


def test_unexpected_agent_failure_is_structured_error_with_stack_trace(
    app: FastAPI,
    client: TestClient,
    request_body: dict[str, object],
    caplog: pytest.LogCaptureFixture,
) -> None:
    app.state.agents["tutor"] = _FailingAgent(RuntimeError("provider detail must not be returned"))

    with caplog.at_level(logging.ERROR, logger="ramals_ai.api.internal"):
        response = client.post(
            "/internal/v1/tutor/respond",
            json=request_body,
            headers={"Authorization": "Bearer good-token"},
        )

    assert response.status_code == 500
    assert response.json()["code"] == "UNEXPECTED_ERROR"
    event = next(
        record
        for record in caplog.records
        if record.getMessage() == "Unexpected AI request failure"
    )
    assert event.exc_info is not None
    assert event.__dict__["errorCode"] == "UNEXPECTED_ERROR"
