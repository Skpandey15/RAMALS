"""Contract A controls at the LiteLLM boundary, below the RAMALS gateway."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

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


def provider_with(module: RecordingLiteLLM) -> LiteLLMProvider:
    provider = LiteLLMProvider(api_key="test-key")
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
