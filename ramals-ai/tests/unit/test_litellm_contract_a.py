"""Contract A controls at the LiteLLM boundary, below the RAMALS gateway."""

from __future__ import annotations

import sys
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


@pytest.mark.parametrize("attribute", ["success_callback", "failure_callback"])
def test_configure_langfuse_tracing_preserves_callbacks_already_registered(attribute: str) -> None:
    """Assigning a fresh one-element list would silently drop whatever another integration (or a
    future RAMALS one) already registered on this process-wide LiteLLM list."""
    fake_litellm_module = SimpleNamespace(success_callback=["existing-success"])
    fake_litellm_module.failure_callback = ["existing-failure"]

    LiteLLMProvider._configure_langfuse_tracing(fake_litellm_module)  # noqa: SLF001

    existing_entry = "existing-success" if attribute == "success_callback" else "existing-failure"
    assert getattr(fake_litellm_module, attribute) == [existing_entry, "langfuse_otel"]


@pytest.mark.parametrize("attribute", ["success_callback", "failure_callback"])
def test_configure_langfuse_tracing_does_not_duplicate_an_existing_entry(attribute: str) -> None:
    """Langfuse_otel already present (a prior call, or pre-set by the caller) must not become two
    entries -- LiteLLM would otherwise invoke the same callback twice per request."""
    fake_litellm_module = SimpleNamespace(success_callback=["langfuse_otel"])
    fake_litellm_module.failure_callback = ["langfuse_otel"]

    LiteLLMProvider._configure_langfuse_tracing(fake_litellm_module)  # noqa: SLF001

    assert getattr(fake_litellm_module, attribute) == ["langfuse_otel"]


def test_configure_langfuse_tracing_is_idempotent_across_repeated_calls() -> None:
    """A process can hold more than one LiteLLMProvider instance; each one's _module() calls this,
    so calling it twice on the same module object must not accumulate duplicates."""
    fake_litellm_module = SimpleNamespace(success_callback=[], failure_callback=[])

    LiteLLMProvider._configure_langfuse_tracing(fake_litellm_module)  # noqa: SLF001
    LiteLLMProvider._configure_langfuse_tracing(fake_litellm_module)  # noqa: SLF001

    assert fake_litellm_module.success_callback == ["langfuse_otel"]
    assert fake_litellm_module.failure_callback == ["langfuse_otel"]


@pytest.mark.parametrize("attribute", ["success_callback", "failure_callback"])
def test_configure_langfuse_tracing_handles_a_none_callback_value(attribute: str) -> None:
    """LiteLLM (or a test double) may hold None rather than an empty list; this must not raise."""
    fake_litellm_module = SimpleNamespace(success_callback=None, failure_callback=None)

    LiteLLMProvider._configure_langfuse_tracing(fake_litellm_module)  # noqa: SLF001

    assert getattr(fake_litellm_module, attribute) == ["langfuse_otel"]


def test_configure_langfuse_tracing_does_not_mutate_the_original_list_object() -> None:
    """Callers (or LiteLLM itself) may hold their own reference to the original list; appending to
    it in place would be a surprising action-at-a-distance for whoever holds that reference."""
    original_success = ["existing-success"]
    fake_litellm_module = SimpleNamespace(success_callback=original_success, failure_callback=[])

    LiteLLMProvider._configure_langfuse_tracing(fake_litellm_module)  # noqa: SLF001

    assert original_success == ["existing-success"]
    assert fake_litellm_module.success_callback == ["existing-success", "langfuse_otel"]


def test_module_preserves_existing_callbacks_when_tracing_is_enabled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """End-to-end through _module(), using a fake module injected into sys.modules so this never
    touches the real litellm package's own process-global callback lists (monkeypatch restores
    sys.modules afterwards, so no state leaks into other tests)."""
    fake_litellm_module = SimpleNamespace(
        success_callback=["existing-success"], failure_callback=["existing-failure"]
    )
    monkeypatch.setitem(sys.modules, "litellm", fake_litellm_module)

    provider = LiteLLMProvider(api_key="test-key", langfuse_tracing_enabled=True)
    provider._module()  # noqa: SLF001

    assert fake_litellm_module.success_callback == ["existing-success", "langfuse_otel"]
    assert fake_litellm_module.failure_callback == ["existing-failure", "langfuse_otel"]


def test_module_does_not_touch_callbacks_when_tracing_is_disabled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The default, off configuration must leave whatever callbacks the process already has -- its
    own or another integration's -- completely alone."""
    fake_litellm_module = SimpleNamespace(
        success_callback=["existing-success"], failure_callback=["existing-failure"]
    )
    monkeypatch.setitem(sys.modules, "litellm", fake_litellm_module)

    provider = LiteLLMProvider(api_key="test-key", langfuse_tracing_enabled=False)
    provider._module()  # noqa: SLF001

    assert fake_litellm_module.success_callback == ["existing-success"]
    assert fake_litellm_module.failure_callback == ["existing-failure"]


def test_langfuse_callback_registration_is_process_wide_not_per_instance(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Documents a real constraint of this design, not a bug: success_callback/failure_callback
    live on the litellm module itself, not on any LiteLLMProvider instance, so enabling tracing on
    one provider enables it for every provider sharing that module in the same process -- there is
    no per-instance isolation, because LiteLLM offers none at this layer. RAMALS's assumption is
    that ramals-ai runs at most one effective, live tracing configuration per process (main.py
    constructs exactly one LiteLLMProvider at startup), so this is not a live concern today."""
    fake_litellm_module = SimpleNamespace(success_callback=[], failure_callback=[])
    monkeypatch.setitem(sys.modules, "litellm", fake_litellm_module)

    tracing_disabled_provider = LiteLLMProvider(api_key="test-key", langfuse_tracing_enabled=False)
    tracing_enabled_provider = LiteLLMProvider(api_key="test-key", langfuse_tracing_enabled=True)

    tracing_disabled_provider._module()  # noqa: SLF001
    tracing_enabled_provider._module()  # noqa: SLF001

    # The module both instances share now carries langfuse_otel -- an instance constructed with
    # langfuse_tracing_enabled=False cannot opt itself out of a sibling instance's tracing.
    assert fake_litellm_module.success_callback == ["langfuse_otel"]
    assert fake_litellm_module.failure_callback == ["langfuse_otel"]
