"""The Contract B provider surface: it forwards, it refuses, and it remembers nothing.

Three properties are worth testing here and the rest is FastAPI's business.

1. **Admission goes through the capability gate on every call.** M2-ADR-016 §4 requires an adapter
   that cannot honour Contract B to fail rather than fall through to a synchronous call, and a gate
   checked once at startup is a gate that stops applying the moment configuration changes.
2. **Submission is forwarded exactly once.** The route must not retry on its own; a retry after an
   acknowledgement that was sent but not received is how one logical request becomes two provider
   executions.
3. **Results are correlated by ``custom_id``, never by position.** That is the difference between a
   correlated retrieval and a plausible one, and it decides whose diagnosis lands on whose record.

The service is deliberately stateless, so there is nothing else here to test: no queue to drain, no
cache to invalidate, no reconciliation to get wrong.
"""

from __future__ import annotations

from typing import Any

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ramals_ai.api.durable import build_durable_router
from ramals_ai.gateway.providers.base import (
    DURABLE_EXECUTION_UNSUPPORTED,
    DurableExecutionCapability,
    DurableResult,
    DurableStatus,
    DurableSubmission,
)


class _StubAdapter:
    """A durable adapter that records what it was asked, and nothing else."""

    name = "stub"

    def __init__(self, *, supported: bool = True) -> None:
        self._supported = supported
        self.submissions: list[Any] = []
        self.result_lookups: list[tuple[str, str | None]] = []

    def durable_capability(self) -> DurableExecutionCapability:
        if not self._supported:
            return DURABLE_EXECUTION_UNSUPPORTED
        return DurableExecutionCapability(
            supported=True,
            durable_execution_id=True,
            status_lookup=True,
            result_retrieval=True,
            result_retention_days=29,
        )

    def submit(self, request: Any) -> DurableSubmission:
        self.submissions.append(request)
        return DurableSubmission(
            provider_execution_id="msgbatch_stub00000001",
            state="ACCEPTED",
            custom_id=request.idempotency_key,
        )

    def get_status(self, provider_execution_id: str) -> DurableStatus:
        return DurableStatus(
            provider_execution_id=provider_execution_id,
            state="RUNNING",
            native_status="in_progress",
        )

    def get_result(self, provider_execution_id: str, custom_id: str | None = None) -> DurableResult:
        self.result_lookups.append((provider_execution_id, custom_id))
        return DurableResult(
            provider_execution_id=provider_execution_id,
            outcome="succeeded",
            custom_id=custom_id,
            text='{"contractVersion":"1.0"}',
            input_tokens=16,
            output_tokens=4,
        )


def _client(adapter: Any) -> TestClient:
    app = FastAPI()
    app.state.durable_adapter = adapter
    # Workload authentication is exercised by the internal-router tests; disabling the verifier here
    # keeps these tests about the durable contract rather than re-testing the same guard.
    app.state.workload_verifier = None
    app.include_router(build_durable_router())
    return TestClient(app)


def _submission() -> dict[str, Any]:
    return {
        "request_id": "req-durable-000001",
        "idempotency_key": "idem-req-durable-000001",
        "request_digest": "a" * 64,
        "model": "claude-sonnet-5",
        "max_output_tokens": 1024,
        "messages": [{"role": "user", "content": "diagnose this learner"}],
    }


def test_submit_forwards_once_and_returns_the_handle() -> None:
    adapter = _StubAdapter()
    response = _client(adapter).post("/internal/v1/durable/executions", json=_submission())

    assert response.status_code == 201
    assert response.json()["provider_execution_id"] == "msgbatch_stub00000001"
    # One call, not two. The route holds no durable state, so it cannot decide a retry is safe.
    assert len(adapter.submissions) == 1


def test_submit_carries_the_server_derived_idempotency_key() -> None:
    adapter = _StubAdapter()
    _client(adapter).post("/internal/v1/durable/executions", json=_submission())

    # Carried because the adapter's contract takes one -- not because the provider honours it.
    # M2-ADR-016 records that this path offers no documented replay-safe admission.
    assert adapter.submissions[0].idempotency_key == "idem-req-durable-000001"
    assert adapter.submissions[0].request_id == "req-durable-000001"


def test_an_unsupported_adapter_is_refused_before_any_provider_call() -> None:
    adapter = _StubAdapter(supported=False)
    response = _client(adapter).post("/internal/v1/durable/executions", json=_submission())

    # A refusal costs no tokens and no network round trip, which is the point of checking a declared
    # capability rather than attempting the call and seeing what happens.
    assert response.status_code >= 400
    assert adapter.submissions == []


def test_status_is_read_by_identity() -> None:
    response = _client(_StubAdapter()).get("/internal/v1/durable/executions/msgbatch_x")

    assert response.status_code == 200
    body = response.json()
    assert body["provider_execution_id"] == "msgbatch_x"
    # The provider's own string survives alongside the normalized one: the transition ledger is
    # forensic evidence, and a normalized status is a lossy summary of it.
    assert body["native_status"] == "in_progress"


def test_result_is_correlated_by_custom_id() -> None:
    adapter = _StubAdapter()
    response = _client(adapter).get(
        "/internal/v1/durable/executions/msgbatch_x/result", params={"custom_id": "custom-42"}
    )

    assert response.status_code == 200
    assert adapter.result_lookups == [("msgbatch_x", "custom-42")]


def test_result_without_a_custom_id_passes_none_rather_than_guessing() -> None:
    adapter = _StubAdapter()
    _client(adapter).get("/internal/v1/durable/executions/msgbatch_x/result")

    # None, never a positional default. A provider whose execution maps one-to-one needs no key; one
    # that carries several must be given the caller's own, and inventing an index here would be the
    # bug this parameter exists to prevent.
    assert adapter.result_lookups == [("msgbatch_x", None)]


def test_no_durable_adapter_configured_is_a_refusal_not_a_crash() -> None:
    app = FastAPI()
    app.state.durable_adapter = None
    app.state.workload_verifier = None
    app.include_router(build_durable_router())

    response = TestClient(app).post("/internal/v1/durable/executions", json=_submission())

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "CONTRACT_B_UNSUPPORTED"


@pytest.mark.parametrize(
    "field,value",
    [
        ("request_digest", "too-short"),
        ("max_output_tokens", 0),
        ("messages", []),
    ],
)
def test_a_malformed_submission_never_reaches_the_provider(field: str, value: Any) -> None:
    adapter = _StubAdapter()
    payload = _submission() | {field: value}

    response = _client(adapter).post("/internal/v1/durable/executions", json=payload)

    assert response.status_code == 422
    # Validation happens before the adapter is touched, so a bad request costs nothing.
    assert adapter.submissions == []


# -- submission disposition (residual S2) ----------------------------------------------------------
#
# The caller decides between a definite FAILED and an ambiguous INDETERMINATE on the marker these
# tests assert, and nothing else. Getting it wrong in the optimistic direction records "no provider
# execution exists" while one is running and billing -- and licenses a resubmission that duplicates
# it. So the rule is: a deliberate, parsed rejection may say NOT_CREATED; everything else may not.


class _FailingAdapter(_StubAdapter):
    """Fails the submission with a chosen gateway code."""

    def __init__(self, code: Any) -> None:
        super().__init__()
        self._code = code

    def submit(self, request: Any) -> Any:  # noqa: ARG002 - the failure precedes any use of it
        from ramals_ai.gateway.errors import GatewayError

        raise GatewayError(self._code, "scripted submission failure")


def _disposition(response: Any) -> str | None:
    detail = response.json().get("detail")
    return detail.get("submission") if isinstance(detail, dict) else None


@pytest.mark.parametrize(
    "code_name",
    [
        "CONTRACT_B_UNSUPPORTED",
        "ROUTE_NOT_CONFIGURED",
        "PROVIDER_INVALID_REQUEST",
        "PROVIDER_AUTH_ERROR",
        "PROVIDER_RATE_LIMITED",
        "TOKEN_CEILING_EXCEEDED",
        "COST_CEILING_EXCEEDED",
        "DEADLINE_EXCEEDED",
    ],
)
def test_a_parsed_rejection_rules_provider_creation_out(code_name: str) -> None:
    from ramals_ai.gateway.errors import GatewayErrorCode

    adapter = _FailingAdapter(getattr(GatewayErrorCode, code_name))
    response = _client(adapter).post("/internal/v1/durable/executions", json=_submission())

    # Each of these was decided by something that read the request and said no, so no batch exists
    # and the caller may safely record a definite failure.
    assert _disposition(response) == "NOT_CREATED"


@pytest.mark.parametrize("code_name", ["PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE"])
def test_a_transport_failure_never_rules_provider_creation_out(code_name: str) -> None:
    from ramals_ai.gateway.errors import GatewayErrorCode

    adapter = _FailingAdapter(getattr(GatewayErrorCode, code_name))
    response = _client(adapter).post("/internal/v1/durable/executions", json=_submission())

    # A timeout is the absence of an answer, never an answer; a reset can happen after the bytes
    # were sent. Neither can prove the provider created nothing.
    assert _disposition(response) == "MAY_EXIST"


def test_an_unclassified_submission_failure_is_may_exist() -> None:
    class _Exploding(_StubAdapter):
        def submit(self, request: Any) -> Any:  # noqa: ARG002 - never reaches the request
            raise RuntimeError("a failure nobody classified")

    response = _client(_Exploding()).post("/internal/v1/durable/executions", json=_submission())

    # The S2 case exactly: something failed inside the submission path and cannot prove where. It
    # must not reach the caller as a bare 500 they have to interpret.
    assert response.status_code == 502
    assert _disposition(response) == "MAY_EXIST"


def test_the_capability_gate_marks_its_refusal_as_not_created() -> None:
    adapter = _StubAdapter(supported=False)
    response = _client(adapter).post("/internal/v1/durable/executions", json=_submission())

    # The gate runs before the adapter is called at all, so this one really is provable -- and the
    # marker has to be present even though the refusal comes from our own code rather than a
    # GatewayError, because the caller reads the marker and never the status.
    assert response.status_code >= 400
    assert adapter.submissions == []
    assert _disposition(response) == "NOT_CREATED"


def test_every_gateway_code_has_a_disposition_and_unknown_ones_fail_closed() -> None:
    from ramals_ai.api.durable import _submission_disposition
    from ramals_ai.gateway.errors import GatewayErrorCode

    # A code added later must default to MAY_EXIST until someone decides otherwise, rather than
    # silently inheriting "safe to retry".
    for code in GatewayErrorCode:
        assert _submission_disposition(code) in {"NOT_CREATED", "MAY_EXIST"}
    assert _submission_disposition(GatewayErrorCode.INVALID_STRUCTURED_OUTPUT) == "MAY_EXIST"


def test_a_logging_failure_never_loses_an_acknowledged_execution(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import ramals_ai.api.durable as durable

    def _explode(*_args: Any, **_kwargs: Any) -> None:
        raise RuntimeError("the log sink is down")

    monkeypatch.setattr(durable, "business_event", _explode)
    response = _client(_StubAdapter()).post("/internal/v1/durable/executions", json=_submission())

    # The batch exists by this point. Losing its identity to a logging fault would manufacture the
    # exact orphan this endpoint exists to avoid.
    assert response.status_code == 201
    assert response.json()["provider_execution_id"] == "msgbatch_stub00000001"
