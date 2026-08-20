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
    """Model routes governed by the MVP-1 routing policy."""

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
    service_version: str = "0.1.0"
    log_level: str = "INFO"

    # AI execution remains off by default. With it off the service starts, serves health and
    # capabilities, and needs no provider credential, allowing CI and fresh checkouts to run safely.
    ai_enabled: bool = False
    model_route: ModelRoute = ModelRoute.CI_FAKE
    provider_api_key: str | None = Field(default=None, repr=False)

    request_timeout_seconds: float = Field(default=12.0, gt=0, le=60)

    # --- workload identity (M1-ADR-003) ---------------------------------------------------------
    # Spring authenticates as itself with a Keycloak client-credentials token carrying the
    # `ramals-ai` audience. A learner token carries `ramals-api` and is rejected here.
    workload_auth_enabled: bool = True
    oidc_issuer: str = "http://keycloak:8080/realms/ramals"
    oidc_audience: str = "ramals-ai"
    expected_workload_client_id: str = "ramals-core-workload"
    jwks_cache_seconds: int = Field(default=300, gt=0, le=3600)

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
    def _require_issuer_when_workload_auth_is_enabled(self) -> Settings:
        """Authentication that silently has nowhere to verify against is worse than none."""
        if self.workload_auth_enabled and not self.oidc_issuer.strip():
            raise ValueError(
                "workload_auth_enabled requires RAMALS_AI_OIDC_ISSUER; "
                "set RAMALS_AI_WORKLOAD_AUTH_ENABLED=false only for local runs"
            )
        return self

    @model_validator(mode="after")
    def _reject_disabled_workload_auth_outside_local(self) -> Settings:
        """The internal API must never be reachable unauthenticated in a shared environment."""
        if self.environment is not Environment.LOCAL and not self.workload_auth_enabled:
            raise ValueError(
                f"workload authentication cannot be disabled in {self.environment}; it is the "
                "only thing separating the core workload from a replayed learner token"
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
