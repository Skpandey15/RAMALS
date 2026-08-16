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
