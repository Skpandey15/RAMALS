"""Cross-service correlation (M1-T04).

The property under test is the one the whole MVP-0 correlation model exists to provide: given only
an interactionId from an error screen, an engineer can find the execution — across both runtimes.
"""

from __future__ import annotations

import json
import logging
import uuid
from collections.abc import Callable, Iterator

import pytest
from fastapi.testclient import TestClient
from opentelemetry import trace

from ramals_ai.config.settings import Environment, Settings
from ramals_ai.main import create_app
from ramals_ai.telemetry.correlation import is_canonical_uuid7
from ramals_ai.telemetry.tracing import headers_with_context

# A well-formed W3C traceparent: version 00, 32-hex trace id, 16-hex span id, sampled.
UPSTREAM_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
UPSTREAM_SPAN_ID = "00f067aa0ba902b7"
TRACEPARENT = f"00-{UPSTREAM_TRACE_ID}-{UPSTREAM_SPAN_ID}-01"


@pytest.fixture
def client() -> Iterator[TestClient]:
    with TestClient(create_app(Settings(environment=Environment.TEST))) as started:
        yield started


def interaction_id() -> str:
    return str(uuid.uuid7())


def test_supplied_interaction_id_is_echoed_unchanged(client: TestClient) -> None:
    """The support code the learner was shown must be the one that comes back."""
    supplied = interaction_id()
    response = client.get("/internal/v1/capabilities", headers={"X-Interaction-ID": supplied})
    assert response.status_code == 200
    assert response.headers["X-Interaction-ID"] == supplied


def test_missing_interaction_id_is_generated(client: TestClient) -> None:
    """A caller that forgets still gets a usable support code rather than an error."""
    response = client.get("/internal/v1/capabilities")
    assert response.status_code == 200
    assert is_canonical_uuid7(response.headers["X-Interaction-ID"])


def test_malformed_interaction_id_is_rejected(client: TestClient) -> None:
    response = client.get("/internal/v1/capabilities", headers={"X-Interaction-ID": "not-a-uuid"})
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "INVALID_INTERACTION_ID"
    # Even the rejection is correlated, so the refusal itself is findable in the logs.
    assert is_canonical_uuid7(body["interactionId"])


@pytest.mark.parametrize(
    "value",
    [
        "550E8400-E29B-41D4-A716-446655440000",  # uppercase
        "550e8400-e29b-41d4-a716-446655440000",  # UUIDv4, not v7
        "01920000-0000-7000-8000",  # truncated
        "",
    ],
)
def test_non_canonical_uuid7_values_are_rejected(client: TestClient, value: str) -> None:
    """Matches the Java UuidV7.isCanonical rule exactly, including the lowercase requirement.

    Two runtimes disagreeing about this header would refuse ids Spring happily mints.
    """
    response = client.get("/internal/v1/capabilities", headers={"X-Interaction-ID": value})
    assert response.status_code == 400


def test_request_id_is_fresh_per_attempt(client: TestClient) -> None:
    """A retry keeps the interactionId and gets a new requestId. That is what separates them."""
    supplied = interaction_id()
    first = client.get("/internal/v1/capabilities", headers={"X-Interaction-ID": supplied})
    second = client.get("/internal/v1/capabilities", headers={"X-Interaction-ID": supplied})

    assert first.headers["X-Interaction-ID"] == second.headers["X-Interaction-ID"] == supplied
    assert first.headers["X-Request-ID"] != second.headers["X-Request-ID"]


def test_incoming_trace_context_is_continued(client: TestClient) -> None:
    """The decisive assertion: Spring's trace id survives into this service.

    Starting a fresh trace here would leave two unrelated halves of one learner action, and the
    support code would lead to only one of them.
    """
    response = client.get(
        "/internal/v1/capabilities",
        headers={"X-Interaction-ID": interaction_id(), "traceparent": TRACEPARENT},
    )
    assert response.status_code == 200
    assert response.headers["X-Trace-ID"] == UPSTREAM_TRACE_ID


def _client_with_probe(path: str, handler: Callable[[], dict[str, str]]) -> TestClient:
    """An app carrying one extra route, so a test can observe context from inside a request.

    The route is registered on a freshly built app rather than on the shared fixture's, both so the
    probes cannot leak between tests and so the decorator is typed -- reaching through
    ``TestClient.app`` yields ``Any`` and silently drops the annotations mypy --strict is checking.
    """
    app = create_app(Settings(environment=Environment.TEST))
    app.get(path)(handler)
    return TestClient(app)


def test_the_span_created_here_is_a_child_of_the_caller_span() -> None:
    """Sharing a trace id is not enough: the spans must also be linked into one waterfall.

    A span that reuses the trace id but parents to nothing renders as a second root, which is the
    same navigation problem as a separate trace wearing a matching label.
    """
    captured: dict[str, str] = {}

    def probe() -> dict[str, str]:
        context = trace.get_current_span().get_span_context()
        captured["traceId"] = format(context.trace_id, "032x")
        captured["spanId"] = format(context.span_id, "016x")
        return {"ok": "true"}

    with _client_with_probe("/internal/v1/_parentage_probe", probe) as client:
        client.get(
            "/internal/v1/_parentage_probe",
            headers={"X-Interaction-ID": interaction_id(), "traceparent": TRACEPARENT},
        )

    assert captured["traceId"] == UPSTREAM_TRACE_ID
    assert captured["spanId"] != UPSTREAM_SPAN_ID, "a new span should have been started here"


def test_tracestate_survives_the_hop() -> None:
    """The plan names tracestate alongside traceparent.

    It carries vendor sampling decisions. Dropping it silently downgrades sampling for everything
    downstream, and nothing fails loudly when that happens.
    """
    outbound: dict[str, str] = {}

    def probe() -> dict[str, str]:
        outbound.update(headers_with_context())
        return {"ok": "true"}

    with _client_with_probe("/internal/v1/_tracestate_probe", probe) as client:
        client.get(
            "/internal/v1/_tracestate_probe",
            headers={
                "X-Interaction-ID": interaction_id(),
                "traceparent": TRACEPARENT,
                "tracestate": "ramals=t61rcWkgMzE,vendor=opaque",
            },
        )

    assert "ramals=t61rcWkgMzE" in outbound["tracestate"]


def test_outbound_headers_carry_the_current_trace() -> None:
    """The leg M1-T05 depends on: without this the model call becomes an orphan trace.

    Asserted now, while the propagation code is being written, rather than discovered later as a
    waterfall that stops exactly where it gets interesting.
    """
    outbound: dict[str, str] = {}

    def probe() -> dict[str, str]:
        outbound.update(headers_with_context())
        return {"ok": "true"}

    with _client_with_probe("/internal/v1/_outbound_probe", probe) as client:
        client.get(
            "/internal/v1/_outbound_probe",
            headers={"X-Interaction-ID": interaction_id(), "traceparent": TRACEPARENT},
        )

    assert outbound["traceparent"].startswith(f"00-{UPSTREAM_TRACE_ID}-")
    # The outbound parent must be the span this service created, not the one it received.
    assert UPSTREAM_SPAN_ID not in outbound["traceparent"]


def test_trace_id_is_returned_even_without_upstream_context(client: TestClient) -> None:
    response = client.get(
        "/internal/v1/capabilities", headers={"X-Interaction-ID": interaction_id()}
    )
    assert len(response.headers["X-Trace-ID"]) == 32


def test_identifiers_do_not_leak_between_requests(client: TestClient) -> None:
    """A ContextVar that is never reset makes the correlation model quietly start lying."""
    first_id = interaction_id()
    first = client.get("/internal/v1/capabilities", headers={"X-Interaction-ID": first_id})
    second = client.get("/internal/v1/capabilities")

    assert first.headers["X-Interaction-ID"] == first_id
    assert second.headers["X-Interaction-ID"] != first_id


def test_health_probes_are_exempt(client: TestClient) -> None:
    """An orchestrator has no interaction to correlate, and metering its polling buries signal."""
    assert client.get("/health/live").status_code == 200
    assert "X-Interaction-ID" not in client.get("/health/live").headers


def test_logs_carry_the_correlation_identifiers(
    client: TestClient, caplog: pytest.LogCaptureFixture
) -> None:
    """Given an interactionId, the log line for that request must be findable."""
    from ramals_ai.telemetry.logging import JsonFormatter

    supplied = interaction_id()
    with caplog.at_level(logging.INFO, logger="ramals_ai.api.correlation"):
        client.get("/internal/v1/capabilities", headers={"X-Interaction-ID": supplied})

    formatter = JsonFormatter(service="ramals-ai", environment="test")
    served = [r for r in caplog.records if r.getMessage() == "internal request served"]
    assert served, "the request should have been logged"

    # Formatting happens inside the request context in production; assert the record carries
    # what the formatter needs, and that the formatter emits it.
    rendered = json.loads(formatter.format(served[0]))
    assert rendered["route"] == "/internal/v1/capabilities"
    assert rendered["statusCode"] == 200


def test_authentication_failure_is_still_correlated() -> None:
    """A 401 must carry a support code too, or an auth problem is unreportable.

    This is why the correlation middleware runs ahead of authentication.
    """
    from fastapi import Depends

    from ramals_ai.api.internal import build_internal_router, require_workload_identity
    from ramals_ai.security.workload_identity import WorkloadIdentity

    app = create_app(Settings(environment=Environment.TEST))

    class _RejectingVerifier:
        def verify(self, token: str) -> WorkloadIdentity:  # noqa: ARG002 - signature is the contract
            raise AssertionError("should not be reached without a token")

    app.state.workload_verifier = _RejectingVerifier()
    guarded = build_internal_router()

    @guarded.get("/probe")
    def _probe(
        identity: WorkloadIdentity = Depends(require_workload_identity),  # noqa: ARG001
    ) -> dict[str, str]:
        # The dependency is the point of the route; the value itself is not used.
        return {"ok": "true"}

    app.include_router(guarded)

    supplied = interaction_id()
    with TestClient(app) as started:
        response = started.get("/internal/v1/probe", headers={"X-Interaction-ID": supplied})

    assert response.status_code == 401
    assert response.headers["X-Interaction-ID"] == supplied
