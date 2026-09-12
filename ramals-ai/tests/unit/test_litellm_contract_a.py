"""Contract A controls at the LiteLLM boundary, below the RAMALS gateway."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from ramals_ai.gateway.providers.base import Message, ProviderRequest
from ramals_ai.gateway.providers.litellm_adapter import LiteLLMProvider


class RecordingLiteLLM:
    def __init__(self) -> None:
        self.arguments: list[dict[str, Any]] = []

    def completion(self, **arguments: Any) -> SimpleNamespace:
        self.arguments.append(arguments)
        return SimpleNamespace(
            id="provider-message-1",
            choices=[SimpleNamespace(message=SimpleNamespace(content="provider response"))],
            usage=SimpleNamespace(prompt_tokens=10, completion_tokens=4),
            _hidden_params={"additional_headers": {"X-Request-ID": "provider-request-1"}},
        )


def request(*, single_submission: bool) -> ProviderRequest:
    return ProviderRequest(
        model="provider-model",
        messages=(Message(role="user", content="diagnose"),),
        max_output_tokens=100,
        timeout_seconds=5.0,
        request_id="wf-diag-contract-a",
        single_submission=single_submission,
    )


def provider_with(
    module: RecordingLiteLLM, *, langfuse_tracing_enabled: bool = False
) -> LiteLLMProvider:
    provider = LiteLLMProvider(
        api_key="test-key", langfuse_tracing_enabled=langfuse_tracing_enabled
    )
    provider._litellm = module  # noqa: SLF001 - isolates the provider boundary from the real SDK
    return provider


def test_contract_a_explicitly_disables_litellm_retries() -> None:
    module = RecordingLiteLLM()

    provider_with(module).complete(request(single_submission=True))

    assert module.arguments[0]["num_retries"] == 0


def test_standard_calls_preserve_the_existing_litellm_retry_configuration() -> None:
    module = RecordingLiteLLM()

    provider_with(module).complete(request(single_submission=False))

    assert "num_retries" not in module.arguments[0]


def test_provider_receipts_are_extracted_for_audit_without_becoming_replay_keys() -> None:
    module = RecordingLiteLLM()

    response = provider_with(module).complete(request(single_submission=True))

    assert response.provider_request_id == "provider-request-1"
    assert response.provider_message_id == "provider-message-1"


def test_no_langfuse_metadata_when_tracing_is_disabled() -> None:
    """The default, off configuration must be byte-for-byte the pre-existing call shape."""
    module = RecordingLiteLLM()

    provider_with(module, langfuse_tracing_enabled=False).complete(request(single_submission=False))

    assert "metadata" not in module.arguments[0]


def test_langfuse_metadata_carries_the_ramals_request_id_when_tracing_is_enabled() -> None:
    """Correlates the Langfuse trace back to the same request identity BusinessEventLogger's own
    structured logs already carry (M1-T04) -- never learner free-text, just the id."""
    module = RecordingLiteLLM()

    provider_with(module, langfuse_tracing_enabled=True).complete(request(single_submission=False))

    assert module.arguments[0]["metadata"] == {"trace_id": "wf-diag-contract-a"}


def test_no_langfuse_metadata_when_tracing_enabled_but_request_has_no_id() -> None:
    module = RecordingLiteLLM()
    unidentified_request = ProviderRequest(
        model="provider-model",
        messages=(Message(role="user", content="diagnose"),),
        max_output_tokens=100,
        timeout_seconds=5.0,
        request_id=None,
        single_submission=False,
    )

    provider_with(module, langfuse_tracing_enabled=True).complete(unidentified_request)

    assert "metadata" not in module.arguments[0]


@pytest.mark.parametrize("attribute", ["success_callback", "failure_callback"])
def test_configure_langfuse_tracing_enables_the_litellm_native_callback(attribute: str) -> None:
    """A small, separately-testable step (LiteLLMProvider._configure_langfuse_tracing) so this
    assertion needs no real `litellm` import -- CI's default unit-test job installs only the `dev`
    extra, never `provider`."""
    fake_litellm_module = SimpleNamespace()

    LiteLLMProvider._configure_langfuse_tracing(fake_litellm_module)  # noqa: SLF001

    assert getattr(fake_litellm_module, attribute) == ["langfuse_otel"]
