"""The Anthropic Message Batches adapter, driven by a fake SDK.

No provider is contacted and no `anthropic` package is required to run these. The adapter's SDK
access goes through one seam (`_batches`), and every test replaces it with a stand-in shaped like
the real resource -- `create`/`retrieve`/`results`/`cancel`, returning objects with the fields the
v1.1.0 models actually declare.

The fake mirrors the SDK rather than the adapter's convenience: `processing_status` is one of
`in_progress`/`canceling`/`ended`, results stream as records carrying `custom_id` and a
discriminated `result`, and `MessageBatchSucceededResult` holds the message under `.message`. A
fake shaped like the code under test would agree with any mapping, including a wrong one.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

import pytest

from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.providers.anthropic_batches_adapter import (
    ANTHROPIC_BATCH_RESULT_RETENTION_DAYS,
    AnthropicBatchesProvider,
)
from ramals_ai.gateway.providers.base import (
    DurableSubmissionRequest,
    Message,
    resolve_durable_capability,
)

CUSTOM_ID = "wf-diag-01900000-0000-7000-8000-000000000001"
BATCH_ID = "msgbatch_01900000000000000000000001"


# -- a stand-in shaped like the v1.1.0 SDK -------------------------------------------------------


@dataclass
class _Counts:
    processing: int = 0
    succeeded: int = 0
    errored: int = 0
    canceled: int = 0
    expired: int = 0


@dataclass
class _Batch:
    id: str = BATCH_ID
    processing_status: str = "in_progress"
    request_counts: _Counts = field(default_factory=_Counts)
    results_url: str | None = None
    created_at: str = "2026-08-27T10:00:00Z"
    expires_at: str = "2026-08-28T10:00:00Z"
    ended_at: str | None = None
    cancel_initiated_at: str | None = None


@dataclass
class _TextBlock:
    text: str
    type: str = "text"


@dataclass
class _ThinkingBlock:
    thinking: str
    type: str = "thinking"


@dataclass
class _Usage:
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read_input_tokens: int = 0


@dataclass
class _Message:
    content: list[Any]
    usage: _Usage = field(default_factory=_Usage)
    id: str = "msg_0190000000000000000000000a"


@dataclass
class _Succeeded:
    message: _Message
    type: str = "succeeded"


@dataclass
class _Terminal:
    """The errored / canceled / expired variants, which carry no message."""

    type: str
    error: Any = None


@dataclass
class _Record:
    custom_id: str
    result: Any


class _FakeBatches:
    """Records what it was asked to do, so the tests can assert on the call, not just the result."""

    def __init__(
        self,
        *,
        batch: _Batch | None = None,
        records: list[_Record] | None = None,
        raises: Exception | None = None,
    ) -> None:
        self.batch = batch or _Batch()
        self.records = records or []
        self.raises = raises
        self.create_calls: list[dict[str, Any]] = []
        self.retrieve_calls: list[str] = []
        self.results_calls: list[str] = []
        self.cancel_calls: list[str] = []

    def create(self, *, requests: list[dict[str, Any]], **_: Any) -> _Batch:
        if self.raises is not None:
            raise self.raises
        self.create_calls.append({"requests": list(requests)})
        return self.batch

    def retrieve(self, batch_id: str, **_: Any) -> _Batch:
        if self.raises is not None:
            raise self.raises
        self.retrieve_calls.append(batch_id)
        return self.batch

    def results(self, batch_id: str, **_: Any) -> list[_Record]:
        if self.raises is not None:
            raise self.raises
        self.results_calls.append(batch_id)
        return list(self.records)

    def cancel(self, batch_id: str, **_: Any) -> _Batch:
        if self.raises is not None:
            raise self.raises
        self.cancel_calls.append(batch_id)
        return self.batch


def _provider(fake: _FakeBatches) -> AnthropicBatchesProvider:
    provider = AnthropicBatchesProvider(api_key="unused-in-tests")
    provider._batches = lambda: fake  # type: ignore[method-assign]  # noqa: SLF001
    return provider


def _request() -> DurableSubmissionRequest:
    return DurableSubmissionRequest(
        request_id=CUSTOM_ID,
        idempotency_key=CUSTOM_ID,
        request_digest="a" * 64,
        model="claude-sonnet-5",
        messages=(Message(role="user", content="diagnose this learner"),),
        max_output_tokens=1024,
    )


# -- capability declaration ----------------------------------------------------------------------


def test_capability_is_supported_with_the_one_row_that_fails_stated() -> None:
    capability = AnthropicBatchesProvider().durable_capability()
    assert capability.supported is True
    assert capability.durable_execution_id is True
    assert capability.status_lookup is True
    assert capability.result_retrieval is True
    assert capability.cancellation is True
    assert capability.result_retention_days == ANTHROPIC_BATCH_RESULT_RETENTION_DAYS == 29


def test_replay_safe_admission_is_reported_false() -> None:
    """The row Anthropic does not offer, and the one most tempting to overstate.

    Reporting it true would let the admission guard accept a path that cannot deduplicate a lost
    acknowledgement, which is the failure M2-ADR-016 exists to prevent.
    """
    assert AnthropicBatchesProvider().durable_capability().replay_safe_admission is False


def test_the_capability_probe_touches_no_sdk() -> None:
    provider = AnthropicBatchesProvider()
    assert provider.durable_capability().supported is True
    assert provider._client is None  # noqa: SLF001 - the laziness is the assertion


def test_the_adapter_resolves_as_contract_b_capable() -> None:
    assert resolve_durable_capability(AnthropicBatchesProvider()).supported is True


# -- submit ---------------------------------------------------------------------------------------


def test_submit_sends_one_request_keyed_by_the_idempotency_key() -> None:
    fake = _FakeBatches()
    submission = _provider(fake).submit(_request())

    sent = fake.create_calls[0]["requests"]
    assert len(sent) == 1, "one learner request per batch; a shared batch shares a cancellation"
    assert sent[0]["custom_id"] == CUSTOM_ID
    assert sent[0]["params"]["model"] == "claude-sonnet-5"
    assert sent[0]["params"]["max_tokens"] == 1024
    assert sent[0]["params"]["messages"] == [{"role": "user", "content": "diagnose this learner"}]
    assert submission.provider_execution_id == BATCH_ID
    assert submission.custom_id == CUSTOM_ID
    assert submission.expires_at == "2026-08-28T10:00:00Z"


def test_submit_does_not_resubmit_on_failure() -> None:
    """A failed create is one attempt, never two.

    On a provider with no idempotency key, a retry inside the adapter is a second logical execution
    for one request -- invisible to the gateway that is supposed to own that decision.
    """

    class RateLimitError(Exception):
        status_code = 429

    fake = _FakeBatches(raises=RateLimitError("slow down"))
    with pytest.raises(GatewayError) as raised:
        _provider(fake).submit(_request())
    assert raised.value.code is GatewayErrorCode.PROVIDER_RATE_LIMITED
    assert fake.create_calls == []


# -- status ---------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("processing_status", "expected_state"),
    [("in_progress", "RUNNING"), ("canceling", "CANCELLING"), ("ended", "RESULT_AVAILABLE")],
)
def test_status_maps_processing_status_and_keeps_the_native_value(
    processing_status: str, expected_state: str
) -> None:
    fake = _FakeBatches(batch=_Batch(processing_status=processing_status))
    status = _provider(fake).get_status(BATCH_ID)
    assert status.state == expected_state
    # The normalized state is a summary; the transition ledger is forensic evidence and keeps both.
    assert status.native_status == processing_status


def test_status_preserves_all_five_request_counts() -> None:
    """The counters an OpenAI-shaped normalization collapses into three.

    `expired` and `canceled` are distinct terminal facts: a batch that hit the 24-hour processing
    deadline did not error, and an operator told otherwise goes hunting a provider fault that never
    happened.
    """
    counts = _Counts(processing=0, succeeded=0, errored=0, canceled=0, expired=1)
    fake = _FakeBatches(batch=_Batch(processing_status="ended", request_counts=counts))
    observed = _provider(fake).get_status(BATCH_ID).counts
    assert observed is not None
    assert (observed.processing, observed.succeeded, observed.errored) == (0, 0, 0)
    assert (observed.canceled, observed.expired) == (0, 1)


def test_status_reports_result_availability_from_results_url() -> None:
    """The field LiteLLM's transform discards, which is why this adapter exists at all."""
    absent = _provider(_FakeBatches(batch=_Batch(results_url=None))).get_status(BATCH_ID)
    present = _provider(
        _FakeBatches(batch=_Batch(processing_status="ended", results_url="https://example/results"))
    ).get_status(BATCH_ID)
    assert absent.results_available is False
    assert present.results_available is True


def test_status_preserves_expiry_metadata_needed_for_reconciliation() -> None:
    fake = _FakeBatches(batch=_Batch(processing_status="ended", ended_at="2026-08-27T11:00:00Z"))
    status = _provider(fake).get_status(BATCH_ID)
    assert status.created_at == "2026-08-27T10:00:00Z"
    assert status.expires_at == "2026-08-28T10:00:00Z"
    assert status.ended_at == "2026-08-27T11:00:00Z"


# -- result correlation ---------------------------------------------------------------------------


def test_result_is_matched_by_custom_id_not_by_position() -> None:
    """Results may be returned out of request order, so position is not identity.

    The wrong-by-default implementation takes `records[0]` and passes every single-record test.
    This one puts the wanted record second.
    """
    fake = _FakeBatches(
        records=[
            _Record("some-other-request", _Succeeded(_Message([_TextBlock("wrong learner")]))),
            _Record(CUSTOM_ID, _Succeeded(_Message([_TextBlock("right learner")]))),
        ]
    )
    result = _provider(fake).get_result(BATCH_ID, CUSTOM_ID)
    assert result.custom_id == CUSTOM_ID
    assert result.text == "right learner"


def test_result_maps_usage_and_message_identity() -> None:
    message = _Message(
        [_TextBlock("a diagnosis")],
        usage=_Usage(input_tokens=11, output_tokens=22, cache_read_input_tokens=3),
    )
    fake = _FakeBatches(records=[_Record(CUSTOM_ID, _Succeeded(message))])
    result = _provider(fake).get_result(BATCH_ID, CUSTOM_ID)
    assert result.outcome == "succeeded"
    assert (result.input_tokens, result.output_tokens, result.cached_input_tokens) == (11, 22, 3)
    assert result.provider_message_id == "msg_0190000000000000000000000a"


@pytest.mark.parametrize("outcome", ["errored", "canceled", "expired"])
def test_non_success_outcomes_are_kept_apart_and_carry_no_text(outcome: str) -> None:
    fake = _FakeBatches(records=[_Record(CUSTOM_ID, _Terminal(type=outcome))])
    result = _provider(fake).get_result(BATCH_ID, CUSTOM_ID)
    assert result.outcome == outcome
    assert result.text is None


def test_a_missing_correlation_key_is_an_error_not_an_empty_result() -> None:
    """Returning an empty result here would read downstream as "the model said nothing"."""
    fake = _FakeBatches(records=[_Record("someone-else", _Succeeded(_Message([_TextBlock("x")])))])
    with pytest.raises(GatewayError) as raised:
        _provider(fake).get_result(BATCH_ID, CUSTOM_ID)
    assert raised.value.code is GatewayErrorCode.INVALID_STRUCTURED_OUTPUT


def test_thinking_blocks_never_reach_a_durable_result() -> None:
    """M2-ADR-017 prohibits persisting internal reasoning; this is where it is cheapest to honour.

    Enforced by never reading the block rather than by stripping it later: a redaction step runs
    after the content has already been carried somewhere.
    """
    message = _Message([_ThinkingBlock("the model's private reasoning"), _TextBlock("the answer")])
    fake = _FakeBatches(records=[_Record(CUSTOM_ID, _Succeeded(message))])
    result = _provider(fake).get_result(BATCH_ID, CUSTOM_ID)
    assert result.text == "the answer"
    assert "private reasoning" not in (result.text or "")


# -- cancellation ---------------------------------------------------------------------------------


def test_cancel_requests_the_batch_and_reports_the_native_state() -> None:
    fake = _FakeBatches(
        batch=_Batch(processing_status="canceling", cancel_initiated_at="2026-08-27T10:30:00Z")
    )
    status = _provider(fake).cancel(BATCH_ID)
    assert fake.cancel_calls == [BATCH_ID]
    assert status.state == "CANCELLING"
    assert status.native_status == "canceling"
    assert status.cancel_initiated_at == "2026-08-27T10:30:00Z"


# -- retry configuration --------------------------------------------------------------------------


def test_the_client_is_constructed_with_sdk_retries_disabled(monkeypatch: Any) -> None:
    """RAMALS owns retry policy, not the SDK.

    The SDK retries twice by default, below the gateway and invisible to it. On a provider with no
    idempotency key a retried batch create is a second logical execution for one request, so this
    asserts the constructor argument rather than trusting the comment next to it.
    """
    captured: dict[str, Any] = {}

    class _FakeClient:
        def __init__(self, **kwargs: Any) -> None:
            captured.update(kwargs)
            self.messages = type("_M", (), {"batches": _FakeBatches()})()

    module = type("_AnthropicModule", (), {"Anthropic": _FakeClient})
    monkeypatch.setitem(__import__("sys").modules, "anthropic", module)

    provider = AnthropicBatchesProvider(api_key="k")
    provider.ensure_available()
    assert captured["max_retries"] == 0
    assert captured["api_key"] == "k"


def test_a_missing_sdk_is_a_configuration_failure_not_a_transient_one(monkeypatch: Any) -> None:
    import builtins

    real_import = builtins.__import__

    def _no_anthropic(name: str, *args: Any, **kwargs: Any) -> Any:
        if name == "anthropic":
            raise ImportError("no anthropic")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", _no_anthropic)
    monkeypatch.delitem(__import__("sys").modules, "anthropic", raising=False)

    with pytest.raises(GatewayError) as raised:
        AnthropicBatchesProvider().ensure_available()
    assert raised.value.code is GatewayErrorCode.ROUTE_NOT_CONFIGURED
    assert raised.value.retryable is False


# -- failure taxonomy -----------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("exception_name", "status_code", "expected"),
    [
        ("RateLimitError", 429, GatewayErrorCode.PROVIDER_RATE_LIMITED),
        ("APITimeoutError", None, GatewayErrorCode.PROVIDER_TIMEOUT),
        ("AuthenticationError", 401, GatewayErrorCode.PROVIDER_AUTH_ERROR),
        ("BadRequestError", 400, GatewayErrorCode.PROVIDER_INVALID_REQUEST),
        ("InternalServerError", 500, GatewayErrorCode.PROVIDER_UNAVAILABLE),
    ],
)
def test_sdk_exceptions_map_onto_the_shared_taxonomy(
    exception_name: str, status_code: int | None, expected: GatewayErrorCode
) -> None:
    """One taxonomy for both adapters. Two copies would drift and disagree about a rate limit."""
    failure = type(exception_name, (Exception,), {"status_code": status_code})("boom")
    fake = _FakeBatches(raises=failure)
    with pytest.raises(GatewayError) as raised:
        _provider(fake).get_status(BATCH_ID)
    assert raised.value.code is expected


def test_the_provider_message_is_not_echoed_into_the_error() -> None:
    """Provider errors routinely echo the request, which here is a minimized learner context."""
    failure = type("BadRequestError", (Exception,), {"status_code": 400})(
        "invalid request: learner context said something private"
    )
    fake = _FakeBatches(raises=failure)
    with pytest.raises(GatewayError) as raised:
        _provider(fake).get_status(BATCH_ID)
    assert "private" not in str(raised.value)
