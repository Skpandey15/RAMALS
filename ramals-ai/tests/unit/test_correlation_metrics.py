"""Correlation failures must be observable as metrics, not only as log lines.

A missing or malformed interactionId is a caller contract violation. It degrades diagnosability for
every request that follows, and it is invisible unless something counts it — a log line nobody
queries is not an alert.

These assert on recorded measurements read back through an in-memory reader, not on the fact that a
method was called.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable, Iterator

import pytest
from fastapi.testclient import TestClient

from ramals_ai.config.settings import Environment, Settings
from ramals_ai.main import create_app

MISSING = "ramals.ai.correlation.missing"
INVALID = "ramals.ai.correlation.invalid"
NO_TRACE_CONTEXT = "ramals.ai.trace.context.missing"
TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


@pytest.fixture
def client() -> Iterator[TestClient]:
    with TestClient(create_app(Settings(environment=Environment.TEST))) as started:
        yield started


def test_missing_interaction_id_increments_a_counter(
    client: TestClient, counter_delta: Callable[[str], int]
) -> None:
    counter_delta.snapshot(MISSING)  # type: ignore[attr-defined]
    client.get("/internal/v1/capabilities")
    assert counter_delta(MISSING) == 1


def test_malformed_interaction_id_increments_a_counter(
    client: TestClient, counter_delta: Callable[[str], int]
) -> None:
    counter_delta.snapshot(INVALID)  # type: ignore[attr-defined]
    response = client.get("/internal/v1/capabilities", headers={"X-Interaction-ID": "nonsense"})
    assert response.status_code == 400
    assert counter_delta(INVALID) == 1


def test_absent_trace_context_increments_a_counter(
    client: TestClient, counter_delta: Callable[[str], int]
) -> None:
    """Without W3C context this request's spans start a new trace, orphaning the Spring half."""
    counter_delta.snapshot(NO_TRACE_CONTEXT)  # type: ignore[attr-defined]
    client.get("/internal/v1/capabilities", headers={"X-Interaction-ID": str(uuid.uuid7())})
    assert counter_delta(NO_TRACE_CONTEXT) == 1


def test_a_well_formed_request_increments_nothing(
    client: TestClient, counter_delta: Callable[[str], int]
) -> None:
    """The counters must mean something: a correct caller must not move them."""
    for name in (MISSING, INVALID, NO_TRACE_CONTEXT):
        counter_delta.snapshot(name)  # type: ignore[attr-defined]

    client.get(
        "/internal/v1/capabilities",
        headers={"X-Interaction-ID": str(uuid.uuid7()), "traceparent": TRACEPARENT},
    )

    assert counter_delta(MISSING) == 0
    assert counter_delta(INVALID) == 0
    assert counter_delta(NO_TRACE_CONTEXT) == 0
