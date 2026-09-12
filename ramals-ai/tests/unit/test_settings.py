"""Configuration contract: a bad configuration must fail startup, loudly and specifically."""

from __future__ import annotations

import pytest

from ramals_ai.config.settings import (
    ConfigurationError,
    Environment,
    ModelRoute,
    Settings,
    get_settings,
)


def test_defaults_start_without_any_credential() -> None:
    """A fresh checkout and CI must run with no secrets at all."""
    settings = Settings()
    assert settings.ai_enabled is False
    assert settings.model_route is ModelRoute.CI_FAKE
    assert settings.provider_api_key is None


def test_live_route_without_credential_is_rejected() -> None:
    with pytest.raises(ValueError, match="RAMALS_AI_PROVIDER_API_KEY"):
        Settings(ai_enabled=True, model_route=ModelRoute.TUTOR_DEFAULT)


def test_live_route_with_credential_is_accepted() -> None:
    settings = Settings(
        ai_enabled=True, model_route=ModelRoute.TUTOR_DEFAULT, provider_api_key="test-key"
    )
    assert settings.model_route is ModelRoute.TUTOR_DEFAULT


def test_fake_route_is_refused_in_dev_with_ai_enabled() -> None:
    """Deterministic canned output must never be mistaken for a model in a shared environment."""
    with pytest.raises(ValueError, match="ci-fake"):
        Settings(environment=Environment.DEV, ai_enabled=True, model_route=ModelRoute.CI_FAKE)


def test_fake_route_is_allowed_in_dev_when_ai_is_disabled() -> None:
    settings = Settings(environment=Environment.DEV, ai_enabled=False)
    assert settings.model_route is ModelRoute.CI_FAKE


def test_unknown_setting_is_rejected() -> None:
    """A typo in an environment variable is a misconfiguration, not something to ignore."""
    with pytest.raises(ValueError, match="extra_inputs_are_not_permitted|Extra inputs"):
        Settings(modle_route="tutor-default")  # type: ignore[call-arg]


def test_timeout_bounds_are_enforced() -> None:
    with pytest.raises(ValueError, match="request_timeout_seconds"):
        Settings(request_timeout_seconds=0)
    with pytest.raises(ValueError, match="request_timeout_seconds"):
        Settings(request_timeout_seconds=120)


def test_api_key_is_not_shown_in_repr() -> None:
    """A credential must not reach a log line or crash dump through an accidental repr()."""
    settings = Settings(
        ai_enabled=True, model_route=ModelRoute.TUTOR_DEFAULT, provider_api_key="super-secret"
    )
    assert "super-secret" not in repr(settings)


def test_defaults_start_without_langfuse_tracing() -> None:
    """Tracing disabled requires no credential and no destination -- case 1."""
    settings = Settings()
    assert settings.langfuse_tracing_enabled is False
    assert settings.langfuse_public_key is None
    assert settings.langfuse_secret_key is None
    assert settings.langfuse_host is None


def test_langfuse_tracing_without_public_key_is_rejected() -> None:
    """Case 2: no public key at all (secret key and host also absent here, but the public key is
    the one this asserts on)."""
    with pytest.raises(ValueError, match="LANGFUSE_PUBLIC_KEY"):
        Settings(langfuse_tracing_enabled=True)


def test_langfuse_tracing_without_secret_key_is_rejected() -> None:
    """Case 3: public key and host present, secret key missing."""
    with pytest.raises(ValueError, match="LANGFUSE_SECRET_KEY"):
        Settings(
            langfuse_tracing_enabled=True,
            langfuse_public_key="pk-lf-test",
            langfuse_host="http://localhost:3000",
        )


def test_langfuse_tracing_with_keys_but_no_host_is_rejected() -> None:
    """Case 4: both keys present, LANGFUSE_HOST simply never set.

    This is the governance-critical case: without it, litellm's langfuse_otel integration would
    silently fall back to Langfuse's own cloud endpoint the moment both keys exist, turning an
    observability flag into an implicit export of full learner-facing content to a destination
    nobody configured.
    """
    with pytest.raises(ValueError, match="LANGFUSE_HOST"):
        Settings(
            langfuse_tracing_enabled=True,
            langfuse_public_key="pk-lf-test",
            langfuse_secret_key="sk-lf-test",
        )


def test_langfuse_tracing_with_blank_host_is_rejected() -> None:
    """Case 5: LANGFUSE_HOST set but empty/whitespace must be treated the same as absent."""
    with pytest.raises(ValueError, match="LANGFUSE_HOST"):
        Settings(
            langfuse_tracing_enabled=True,
            langfuse_public_key="pk-lf-test",
            langfuse_secret_key="sk-lf-test",
            langfuse_host="   ",
        )


def test_langfuse_tracing_with_explicit_destination_is_accepted() -> None:
    """Case 6: all three explicit -- the only configuration tracing may start with."""
    settings = Settings(
        langfuse_tracing_enabled=True,
        langfuse_public_key="pk-lf-test",
        langfuse_secret_key="sk-lf-test",
        langfuse_host="http://localhost:3000",
    )
    assert settings.langfuse_tracing_enabled is True
    assert settings.langfuse_host == "http://localhost:3000"


def test_langfuse_secret_key_is_not_shown_in_repr() -> None:
    """Case 8: the secret key must never reach a log line or crash dump through repr()."""
    settings = Settings(
        langfuse_tracing_enabled=True,
        langfuse_public_key="pk-lf-test",
        langfuse_secret_key="super-secret-lf-key",
        langfuse_host="http://localhost:3000",
    )
    assert "super-secret-lf-key" not in repr(settings)


def test_langfuse_credentials_are_read_from_the_unprefixed_environment_variables(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Case 7: LANGFUSE_PUBLIC_KEY/LANGFUSE_SECRET_KEY/LANGFUSE_HOST continue to bind correctly.

    Deliberately not RAMALS_AI_-prefixed: LiteLLM's own langfuse_otel integration reads exactly
    these three names directly from the environment, the same convention every Langfuse SDK uses.
    """
    monkeypatch.setenv("LANGFUSE_PUBLIC_KEY", "pk-lf-env")
    monkeypatch.setenv("LANGFUSE_SECRET_KEY", "sk-lf-env")
    monkeypatch.setenv("LANGFUSE_HOST", "http://langfuse.local:3000")

    settings = Settings()

    assert settings.langfuse_public_key == "pk-lf-env"
    assert settings.langfuse_secret_key == "sk-lf-env"
    assert settings.langfuse_host == "http://langfuse.local:3000"


def test_langfuse_tracing_enabled_via_environment_with_full_destination_is_accepted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The env-bound values from the test above must also satisfy the enabled-tracing validator,
    not just round-trip onto the settings object."""
    monkeypatch.setenv("RAMALS_AI_LANGFUSE_TRACING_ENABLED", "true")
    monkeypatch.setenv("LANGFUSE_PUBLIC_KEY", "pk-lf-env")
    monkeypatch.setenv("LANGFUSE_SECRET_KEY", "sk-lf-env")
    monkeypatch.setenv("LANGFUSE_HOST", "http://langfuse.local:3000")

    settings = Settings()

    assert settings.langfuse_tracing_enabled is True


def test_invalid_configuration_raises_explicit_startup_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The failure a developer sees must name the service and the problem."""
    get_settings.cache_clear()
    monkeypatch.setenv("RAMALS_AI_AI_ENABLED", "true")
    monkeypatch.setenv("RAMALS_AI_MODEL_ROUTE", "tutor-default")
    monkeypatch.delenv("RAMALS_AI_PROVIDER_API_KEY", raising=False)
    try:
        with pytest.raises(ConfigurationError, match="Invalid ramals-ai configuration"):
            get_settings()
    finally:
        get_settings.cache_clear()
