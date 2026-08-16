"""Configuration for the AI execution plane.

Startup fails loudly on a bad configuration rather than degrading into a service that looks healthy
and cannot do its job. Everything is environment-driven; nothing sensitive has a default.
"""

from __future__ import annotations

from enum import StrEnum
from functools import lru_cache

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Environment(StrEnum):
    """Deployment profile. Controls how strict the boundary rules are."""

    LOCAL = "local"
    DEV = "dev"
    TEST = "test"


class ModelRoute(StrEnum):
    """Model routes governed by Doc 04. Only `CI_FAKE` is available until M1-T05."""

    CI_FAKE = "ci-fake"
    TUTOR_DEFAULT = "tutor-default"
    DIAGNOSTIC_DEFAULT = "diagnostic-default"
    ASSESSMENT_DEFAULT = "assessment-default"
    ADAPTATION_DEFAULT = "adaptation-default"


class ConfigurationError(RuntimeError):
    """Raised when the service cannot start with the configuration it was given."""


class Settings(BaseSettings):
    """Validated runtime configuration."""

    model_config = SettingsConfigDict(
        env_prefix="RAMALS_AI_",
        env_file=None,
        extra="forbid",
        frozen=True,
    )

    environment: Environment = Environment.LOCAL
    service_name: str = "ramals-ai"
    log_level: str = "INFO"

    # AI execution is off until M1-T05 wires a real gateway. With it off the service starts, serves
    # health and capabilities, and needs no provider credential — which is what lets CI and a fresh
    # developer checkout run without secrets.
    ai_enabled: bool = False
    model_route: ModelRoute = ModelRoute.CI_FAKE
    provider_api_key: str | None = Field(default=None, repr=False)

    request_timeout_seconds: float = Field(default=12.0, gt=0, le=60)

    @model_validator(mode="after")
    def _reject_live_route_without_credential(self) -> Settings:
        """A live model route with no credential is a misconfiguration, not a degraded mode.

        Discovering it on the first learner request would surface as an opaque provider error long
        after deployment. Fail at startup instead.
        """
        if (
            self.ai_enabled
            and self.model_route is not ModelRoute.CI_FAKE
            and not self.provider_api_key
        ):
            raise ValueError(
                f"model route '{self.model_route}' requires RAMALS_AI_PROVIDER_API_KEY; "
                "set RAMALS_AI_MODEL_ROUTE=ci-fake for local and CI runs"
            )
        return self

    @model_validator(mode="after")
    def _reject_fake_route_outside_test(self) -> Settings:
        """The deterministic fake must never be mistaken for a model in a shared environment."""
        if (
            self.environment is Environment.DEV
            and self.ai_enabled
            and self.model_route is ModelRoute.CI_FAKE
        ):
            raise ValueError(
                "the ci-fake model route cannot serve the dev environment with AI enabled; "
                "it returns deterministic canned output and is for tests only"
            )
        return self


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Loads settings once, converting validation failure into an explicit startup error."""
    try:
        return Settings()
    except ValueError as failure:  # pydantic ValidationError is a ValueError
        raise ConfigurationError(f"Invalid ramals-ai configuration: {failure}") from failure
