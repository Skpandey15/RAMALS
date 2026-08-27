"""The Contract B capability boundary: defaults to unsupported, and refuses without calling out.

Every test here is about a refusal. That is the whole of this increment -- no adapter can serve
Contract B yet, and the property worth protecting is that none of them *pretends* to.

The rule under test is M2-ADR-016 §4: an adapter that cannot honour Contract B fails, and never
falls through to a synchronous Contract A call. A degraded execution would leave a row that looks
recoverable and is not, which is worse than the refusal it replaced.
"""

from __future__ import annotations

import pytest

from ramals_ai.gateway.durable import require_durable_execution_support
from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.providers.base import (
    DURABLE_EXECUTION_UNSUPPORTED,
    DurableExecutionCapability,
    Message,
    ProviderRequest,
    ProviderResponse,
    resolve_durable_capability,
)
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.gateway.providers.litellm_adapter import LiteLLMProvider


class _ProviderCallInvokedError(AssertionError):
    """Raised if a refusal path ever reaches the provider. Never expected to escape a test."""


class _ExplodingAdapter:
    """A Contract A adapter that fails loudly if anything tries to use it for Contract B.

    Declares no ``durable_capability``, which is the realistic shape of an adapter written by
    someone who has never read M2-ADR-016 -- and the case the default must handle correctly.
    """

    name = "exploding"

    def __init__(self) -> None:
        self.complete_calls = 0
        self.token_count_calls = 0

    def count_input_tokens(self, model: str, messages: tuple[Message, ...]) -> int:  # noqa: ARG002
        self.token_count_calls += 1
        raise _ProviderCallInvokedError("token counting was reached on a refusal path")

    def complete(self, request: ProviderRequest) -> ProviderResponse:  # noqa: ARG002
        self.complete_calls += 1
        raise _ProviderCallInvokedError("complete() was reached on a Contract B refusal path")


class _DeclaredUnsupportedAdapter:
    """Declares unsupported explicitly, the way LiteLLM does."""

    name = "declared-unsupported"

    def durable_capability(self) -> DurableExecutionCapability:
        return DurableExecutionCapability.unsupported(
            "no replay-safe admission", result_retrieval=True, result_retention_days=29
        )


class _SupportedAdapter:
    """A hypothetical future adapter. Exists only to prove the guard can say yes.

    Nothing in the service returns this today, and nothing should until the M2-ADR-017 §6
    prerequisites are satisfied.
    """

    name = "hypothetical"

    def durable_capability(self) -> DurableExecutionCapability:
        return DurableExecutionCapability(
            supported=True,
            replay_safe_admission=True,
            durable_execution_id=True,
            status_lookup=True,
            result_retrieval=True,
            cancellation=True,
            result_retention_days=29,
        )


class _LyingAdapter:
    """Returns something that is not a capability. Must not be believed."""

    name = "lying"

    def durable_capability(self) -> object:
        return True


# -- 1. the default is unsupported ---------------------------------------------------------------


def test_adapter_declaring_no_capability_is_unsupported() -> None:
    assert resolve_durable_capability(_ExplodingAdapter()) is DURABLE_EXECUTION_UNSUPPORTED
    assert resolve_durable_capability(_ExplodingAdapter()).supported is False


def test_the_deterministic_ci_fake_route_is_unsupported() -> None:
    # FakeProvider is deliberately left undeclared, so it exercises the default rather than a
    # second copy of the declaration.
    assert resolve_durable_capability(FakeProvider()).supported is False


def test_an_object_that_is_not_an_adapter_at_all_is_unsupported() -> None:
    assert resolve_durable_capability(object()).supported is False


def test_a_capability_that_is_not_a_capability_is_not_believed() -> None:
    # A truthy non-capability must not be read as support. Protocol conformance is not a claim.
    assert resolve_durable_capability(_LyingAdapter()).supported is False


# -- 2. LiteLLM reports unsupported --------------------------------------------------------------


def test_litellm_reports_contract_b_unsupported() -> None:
    capability = LiteLLMProvider().durable_capability()
    assert capability.supported is False
    assert capability.replay_safe_admission is False
    assert capability.status_lookup is False
    assert capability.result_retrieval is False
    assert capability.reason


def test_litellm_capability_does_not_require_the_provider_sdk() -> None:
    """The probe must not import litellm.

    A capability question that needs the optional ``provider`` extra would make refusing cost a
    dependency, on the one path whose purpose is to refuse before anything is loaded.
    """
    provider = LiteLLMProvider()
    assert provider.durable_capability().supported is False
    # The lazy-import slot is still empty: nothing touched the SDK.
    assert provider._litellm is None  # noqa: SLF001 - asserting the laziness is the point


# -- 3. admission fails closed with CONTRACT_B_UNSUPPORTED ---------------------------------------


@pytest.mark.parametrize(
    "adapter",
    [_ExplodingAdapter(), _DeclaredUnsupportedAdapter(), FakeProvider(), LiteLLMProvider()],
    ids=["undeclared", "declared-unsupported", "ci-fake", "litellm"],
)
def test_contract_b_admission_is_refused_on_an_unsupported_adapter(adapter: object) -> None:
    with pytest.raises(GatewayError) as raised:
        require_durable_execution_support(adapter)
    assert raised.value.code is GatewayErrorCode.CONTRACT_B_UNSUPPORTED


def test_the_refusal_names_the_adapter_and_the_reason() -> None:
    with pytest.raises(GatewayError) as raised:
        require_durable_execution_support(_DeclaredUnsupportedAdapter())
    assert "declared-unsupported" in raised.value.detail
    assert "no replay-safe admission" in raised.value.detail


def test_a_supported_adapter_is_admitted() -> None:
    # The guard is a gate, not a wall: it must be able to say yes, or a future adapter could never
    # pass and the tests above would be proving nothing.
    capability = require_durable_execution_support(_SupportedAdapter())
    assert capability.supported is True


# -- 4. no provider call happens on the refusal path ---------------------------------------------


def test_refusal_never_invokes_complete() -> None:
    adapter = _ExplodingAdapter()
    with pytest.raises(GatewayError):
        require_durable_execution_support(adapter)
    assert adapter.complete_calls == 0
    assert adapter.token_count_calls == 0


def test_refusal_costs_no_provider_interaction_at_all() -> None:
    """Not just ``complete`` -- nothing on the adapter is touched except the capability probe.

    A refusal that counted tokens would still cost a tokenizer load, and on a real provider path a
    capability probe that reached the network could fail and turn "unsupported" into "unknown".
    """
    touched: list[str] = []

    class _RecordingAdapter:
        name = "recording"

        def __getattr__(self, item: str) -> object:
            touched.append(item)
            raise AttributeError(item)

    with pytest.raises(GatewayError):
        require_durable_execution_support(_RecordingAdapter())
    assert touched == ["durable_capability"]


# -- 5. no fallback, no degradation to Contract A ------------------------------------------------


def test_contract_b_unsupported_is_never_retryable_or_fallback_eligible() -> None:
    """The two flags that would permit degradation.

    ``fallback_eligible`` is the dangerous one: every route that exists is a Contract A route, so a
    fallback-eligible Contract B refusal is a silent downgrade with extra steps.
    """
    error = GatewayError(GatewayErrorCode.CONTRACT_B_UNSUPPORTED, "any reason")
    assert error.retryable is False
    assert error.fallback_eligible is False


def test_the_refusal_is_a_gateway_error_the_caller_must_handle() -> None:
    # Raised rather than returned, because a returned capability can be ignored and the failure
    # mode worth engineering against is a caller that forgets to check.
    with pytest.raises(GatewayError):
        require_durable_execution_support(FakeProvider())


def test_exactly_one_adapter_supports_contract_b_and_it_is_the_durable_one() -> None:
    """The standing assertion. Changing it is a decision, not a refactor.

    It previously read "no adapter supports Contract B" and was the deliberate signal that adding
    one is gated. That signal fired when the Anthropic Batches adapter arrived, so the assertion is
    updated rather than deleted: the Contract A adapters must still refuse, and the set of adapters
    that do not must not grow quietly.
    """
    from ramals_ai.gateway.providers.anthropic_batches_adapter import AnthropicBatchesProvider

    for contract_a in (FakeProvider(), LiteLLMProvider()):
        assert resolve_durable_capability(contract_a).supported is False
    assert resolve_durable_capability(AnthropicBatchesProvider()).supported is True


def test_the_durable_adapter_still_refuses_to_claim_replay_safe_admission() -> None:
    """Supported is not the same as safe, and this is the row that says so.

    An adapter could pass every other capability row and still be wrong about this one -- and this
    is the row whose overstatement M2-ADR-016 was written to prevent.
    """
    from ramals_ai.gateway.providers.anthropic_batches_adapter import AnthropicBatchesProvider

    capability = resolve_durable_capability(AnthropicBatchesProvider())
    assert capability.supported is True
    assert capability.replay_safe_admission is False
