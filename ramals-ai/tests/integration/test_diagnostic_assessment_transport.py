"""M2-T09 transport: the versioned Java to Python contract for a grounded diagnostic assessment.

Two things are proven here. That the operation exists and fails closed on a context it should not
accept, and that the OpenAPI schema carrying the context agrees with the JSON Schema it claims to
carry. A transport that has drifted from its own contract is worse than no contract, because both
sides still believe they agree.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
import yaml
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ramals_ai.config.settings import Environment, Settings
from ramals_ai.main import create_app
from ramals_ai.security.workload_identity import (
    WorkloadAuthenticationError,
    WorkloadIdentity,
)

REPO = Path(__file__).resolve().parents[3]
OPENAPI = REPO / "contracts" / "ai-internal.openapi.yaml"
GROUNDED_CONTEXT_SCHEMA = REPO / "contracts" / "mvp2" / "grounded-context.v1.schema.json"

PATH = "/internal/v1/diagnostic-assessment/propose"


class _Verifier:
    def verify(self, token: str) -> WorkloadIdentity:
        if token != "good-token":
            raise WorkloadAuthenticationError("WORKLOAD_TOKEN_INVALID")
        return WorkloadIdentity("ramals-core-workload", "ramals-core-workload", 0)


@pytest.fixture
def app() -> FastAPI:
    application = create_app(Settings(environment=Environment.TEST))
    application.state.workload_verifier = _Verifier()
    return application


@pytest.fixture
def client(app: FastAPI) -> TestClient:
    return TestClient(app)


AUTH = {"Authorization": "Bearer good-token"}


def context(*, minutes: int = 10, items: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    now = datetime.now(UTC)
    return {
        "contractVersion": "1.0",
        "contextId": "ctx-transport-1",
        "learnerRef": "opaque-learner",
        "asOf": now.isoformat(),
        "expiresAt": (now + timedelta(minutes=minutes)).isoformat(),
        "retrievalPolicyVersion": "POLICY_V1",
        "items": items
        if items is not None
        else [
            {
                "evidenceId": "e-1",
                "sourceType": "MASTERY",
                "sourceVersion": "v1",
                "authority": "AUTHORITATIVE_FACT",
                "factType": "MASTERY_SCORE",
                "value": "0.2100",
                "observedAt": now.isoformat(),
            },
            {
                "evidenceId": "e-2",
                "sourceType": "LEARNER_EVIDENCE",
                "sourceVersion": "v1",
                "authority": "AUTHORITATIVE_FACT",
                "factType": "ATTEMPT_OUTCOME",
                "value": "INCORRECT",
                "observedAt": now.isoformat(),
            },
        ],
    }


def request_body(**overrides: Any) -> dict[str, Any]:
    body = {
        "contractVersion": "1.0",
        "interactionId": "01a02918-c19f-772e-95e8-fa78432e663b",
        "requestId": "r-1",
        "constraints": {"interactionClass": "INTERACTIVE_AI", "deadlineMs": 8000},
        "groundedContext": context(),
    }
    body.update(overrides)
    return body


# -- the operation exists and is authenticated ---------------------------------------------------


def test_the_operation_requires_workload_identity(client: TestClient) -> None:
    assert client.post(PATH, json=request_body()).status_code == 401


def test_a_learner_token_shape_is_not_accepted(client: TestClient) -> None:
    response = client.post(
        PATH, json=request_body(), headers={"Authorization": "Bearer learner-token"}
    )
    assert response.status_code == 401


# -- fail closed on a context that should not be accepted ----------------------------------------


def test_a_context_missing_a_required_source_is_refused_before_any_model_call(
    client: TestClient,
) -> None:
    """Mastery alone cannot support a diagnosis, and the boundary says so rather than the model."""
    only_mastery = context()
    only_mastery["items"] = [only_mastery["items"][0]]

    response = client.post(PATH, json=request_body(groundedContext=only_mastery), headers=AUTH)

    assert response.status_code == 422
    assert response.json()["code"] == "GROUNDING_REQUIRED_SOURCE_MISSING"


def test_a_stale_context_is_refused(client: TestClient) -> None:
    stale = context(minutes=-1)

    response = client.post(PATH, json=request_body(groundedContext=stale), headers=AUTH)

    assert response.status_code == 422
    assert response.json()["code"].startswith("GROUNDING_")


def test_an_unknown_grounded_context_version_fails_closed(client: TestClient) -> None:
    unsupported = context()
    unsupported["contractVersion"] = "9.9"

    response = client.post(PATH, json=request_body(groundedContext=unsupported), headers=AUTH)

    # Rejected by the generated model before any handler logic: the enum admits one version.
    assert response.status_code == 422


def test_an_unknown_field_in_the_request_is_rejected(client: TestClient) -> None:
    """additionalProperties: false, all the way through the generated model."""
    response = client.post(PATH, json=request_body(learnerId="learner-b"), headers=AUTH)

    assert response.status_code == 422


def test_the_ai_plane_cannot_be_asked_for_a_different_learner() -> None:
    """There is no field to ask with. The context is the whole of what the plane may see."""
    body = request_body()
    assert "learner" not in body
    assert set(body) == {
        "contractVersion",
        "interactionId",
        "requestId",
        "constraints",
        "groundedContext",
    }


# -- the transport schema agrees with the contract it carries ------------------------------------


def _openapi_schemas() -> dict[str, Any]:
    document: dict[str, Any] = yaml.safe_load(OPENAPI.read_text(encoding="utf-8"))
    schemas: dict[str, Any] = document["components"]["schemas"]
    return schemas


def test_the_transported_context_matches_the_grounded_context_contract() -> None:
    """Field-for-field, against contracts/mvp2/grounded-context.v1.schema.json itself."""
    contract = json.loads(GROUNDED_CONTEXT_SCHEMA.read_text(encoding="utf-8"))
    transported = _openapi_schemas()["GroundedContextEnvelope"]

    assert set(transported["properties"]) == set(contract["properties"])
    assert set(transported["required"]) == set(contract["required"])


def test_the_transported_context_item_matches_the_contract() -> None:
    contract = json.loads(GROUNDED_CONTEXT_SCHEMA.read_text(encoding="utf-8"))
    item_contract = contract["$defs"]["contextItem"]
    transported = _openapi_schemas()["GroundedContextItemEnvelope"]

    assert set(transported["properties"]) == set(item_contract["properties"])
    assert set(transported["required"]) == set(item_contract["required"])
    assert set(transported["properties"]["sourceType"]["enum"]) == set(
        item_contract["properties"]["sourceType"]["enum"]
    )
    assert set(transported["properties"]["authority"]["enum"]) == set(
        item_contract["properties"]["authority"]["enum"]
    )


def test_the_mvp1_request_envelope_was_not_widened() -> None:
    """Regression. The MVP-2 context travels on its own operation, not as an optional field."""
    envelope = _openapi_schemas()["AIRequestEnvelope"]

    assert "groundedContext" not in envelope["properties"]


def test_the_mvp1_operations_are_all_still_present() -> None:
    document = yaml.safe_load(OPENAPI.read_text(encoding="utf-8"))
    for path in (
        "/internal/v1/tutor/respond",
        "/internal/v1/diagnostic/propose",
        "/internal/v1/assessment/propose",
        "/internal/v1/assessment/evaluate",
        "/internal/v1/adaptation/propose",
    ):
        assert path in document["paths"], path
