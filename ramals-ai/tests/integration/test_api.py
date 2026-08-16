"""API surface: health separation and capability advertisement."""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from ramals_ai.config.settings import Environment, ModelRoute, Settings
from ramals_ai.main import create_app


@pytest.fixture
def client() -> Iterator[TestClient]:
    app = create_app(Settings(environment=Environment.TEST))
    with TestClient(app) as started:
        yield started


def test_liveness_is_up_once_the_process_serves(client: TestClient) -> None:
    response = client.get("/health/live")
    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_readiness_is_up_after_startup(client: TestClient) -> None:
    response = client.get("/health/ready")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_readiness_is_out_of_service_before_startup_completes() -> None:
    """The distinction that matters: alive but not yet accepting traffic.

    Without entering the lifespan the app is constructed but not started, which is exactly the
    window a readiness probe exists to cover. Liveness must still answer UP — restarting a container
    that is merely still booting turns a slow start into a crash loop.
    """
    app = create_app(Settings(environment=Environment.TEST))
    unstarted = TestClient(app)

    assert unstarted.get("/health/live").status_code == 200

    readiness = unstarted.get("/health/ready")
    assert readiness.status_code == 503
    assert readiness.json()["status"] == "OUT_OF_SERVICE"
    assert readiness.json()["reason"] == "starting"


def test_readiness_drops_on_shutdown() -> None:
    app = create_app(Settings(environment=Environment.TEST))
    with TestClient(app) as started:
        assert started.get("/health/ready").status_code == 200

    # Outside the context manager the lifespan has exited; the app must no longer claim readiness.
    assert app.state.service_state.ready is False


def test_capabilities_reports_this_build(client: TestClient) -> None:
    body = client.get("/internal/v1/capabilities").json()
    assert body["contractVersion"] == "1.0"
    assert body["service"] == "ramals-ai"
    assert body["environment"] == "test"


def test_capabilities_declares_no_authority(client: TestClient) -> None:
    """A caller must be able to see on the wire that this service decides nothing."""
    assert client.get("/internal/v1/capabilities").json()["authority"] == "NON_AUTHORITATIVE"


def test_capabilities_advertises_no_agents_yet(client: TestClient) -> None:
    """Agents arrive in M1-T07; claiming one now would be a lie a caller could act on."""
    assert client.get("/internal/v1/capabilities").json()["agents"] == []


def test_capabilities_never_leaks_the_provider_credential() -> None:
    app = create_app(
        Settings(
            environment=Environment.TEST,
            ai_enabled=True,
            model_route=ModelRoute.TUTOR_DEFAULT,
            provider_api_key="super-secret",
        )
    )
    with TestClient(app) as started:
        assert "super-secret" not in started.get("/internal/v1/capabilities").text


def test_agent_endpoints_are_not_served_yet(client: TestClient) -> None:
    """Declared in the contract, implemented from M1-T07. Until then the service does not pretend
    to offer them, and /internal/v1/capabilities reports an empty agent list to match."""
    assert client.get("/internal/v1/tutor/respond").status_code == 404
