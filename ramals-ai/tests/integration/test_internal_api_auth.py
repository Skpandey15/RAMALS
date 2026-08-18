"""Enforcement of workload identity on the internal API surface."""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from ramals_ai.api.internal import require_workload_identity
from ramals_ai.config.settings import Environment, Settings
from ramals_ai.main import create_app
from ramals_ai.security.workload_identity import (
    WorkloadAuthenticationError,
    WorkloadIdentity,
)


class _AcceptingVerifier:
    def verify(self, token: str) -> WorkloadIdentity:
        if token != "good-token":
            raise WorkloadAuthenticationError("token rejected")
        return WorkloadIdentity(
            subject="service-account-ramals-core-workload",
            client_id="ramals-core-workload",
            expires_at=0,
        )


def _app_with(verifier: object | None, environment: Environment = Environment.TEST) -> FastAPI:
    """Builds the real app and mounts one probe route on the guarded router.

    The probe remains useful for testing the dependency in isolation. Agent boundary behavior is
    covered by the dedicated internal API tests.
    """
    app = create_app(Settings(environment=environment))
    app.state.workload_verifier = verifier

    from ramals_ai.api.internal import build_internal_router

    probe = build_internal_router()

    @probe.get("/probe")
    def _probe(identity: WorkloadIdentity = Depends(require_workload_identity)) -> dict[str, str]:
        return {"clientId": identity.client_id}

    app.include_router(probe)
    return app


@pytest.fixture
def client() -> Iterator[TestClient]:
    with TestClient(_app_with(_AcceptingVerifier())) as started:
        yield started


def test_valid_workload_token_reaches_the_endpoint(client: TestClient) -> None:
    response = client.get("/internal/v1/probe", headers={"Authorization": "Bearer good-token"})
    assert response.status_code == 200
    assert response.json()["clientId"] == "ramals-core-workload"


def test_request_without_a_token_is_rejected(client: TestClient) -> None:
    response = client.get("/internal/v1/probe")
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "WORKLOAD_AUTHENTICATION_REQUIRED"


def test_request_with_a_rejected_token_is_refused(client: TestClient) -> None:
    response = client.get("/internal/v1/probe", headers={"Authorization": "Bearer learner-token"})
    assert response.status_code == 401


def test_every_rejection_looks_identical_to_the_caller(client: TestClient) -> None:
    """A boundary that explains *why* it refused is an oracle for probing it.

    Missing token, wrong audience and unknown client must be indistinguishable from outside.
    """
    missing = client.get("/internal/v1/probe")
    rejected = client.get("/internal/v1/probe", headers={"Authorization": "Bearer nope"})

    assert missing.status_code == rejected.status_code == 401
    assert missing.json() == rejected.json()


def test_rejection_never_echoes_the_supplied_token(client: TestClient) -> None:
    response = client.get(
        "/internal/v1/probe", headers={"Authorization": "Bearer super-secret-token"}
    )
    assert "super-secret-token" not in response.text


def test_capabilities_stays_public(client: TestClient) -> None:
    """Per the contract: operational, no learner data, no token required."""
    response = client.get("/internal/v1/capabilities")
    assert response.status_code == 200
    assert response.json()["authority"] == "NON_AUTHORITATIVE"


def test_health_endpoints_stay_public(client: TestClient) -> None:
    """An orchestrator probing health must not need a workload credential to do it."""
    assert client.get("/health/live").status_code == 200
    assert client.get("/health/ready").status_code == 200


def test_capabilities_is_served_at_the_contract_path(client: TestClient) -> None:
    """The contract declares /internal/v1/capabilities; the unprefixed path must not linger."""
    assert client.get("/capabilities").status_code == 404


def test_local_profile_without_auth_still_serves() -> None:
    """A developer running locally with authentication off must not be blocked by the guard."""
    app = _app_with(None, environment=Environment.LOCAL)
    with TestClient(app) as local:
        assert local.get("/internal/v1/probe").status_code == 200


def test_workload_auth_cannot_be_disabled_outside_local() -> None:
    """Configuration, not code review, is what prevents an unauthenticated shared deployment."""
    with pytest.raises(ValueError, match="cannot be disabled"):
        Settings(environment=Environment.DEV, workload_auth_enabled=False)
