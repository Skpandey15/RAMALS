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

    # T15 crash qualification fixtures are an explicitly opt-in test mode. They are kept in the
    # settings object so an unexpected environment variable cannot silently alter a normal local or
    # shared process, and the default route remains the original deterministic canned completion.
    qualification_fixtures: bool = False
    qualification_provider_pause_enabled: bool = False
    qualification_provider_pause_request_id: str | None = None
    qualification_provider_pause_ms: int = Field(default=0, ge=0, le=600_000)

    # --- rollback pins (M1-ADR-008) -------------------------------------------------------------
    # Withdrawing a bad prompt or model is the fastest remedy available for a quality regression, so
    # it must not cost a service deployment. These pins repoint a route at a revision this image
    # already ships; they cannot introduce one. Both are JSON, and both are validated at startup
    # against what the build can actually produce -- a pin naming something unbuildable stops the
    # process rather than being ignored.
    #
    #   RAMALS_AI_PROMPT_PINS='{"tutor-default": {"TUTOR_EXPLAIN": "TUTOR_PROMPT_V1"}}'
    #   RAMALS_AI_MODEL_PINS='{"tutor-default": "claude-sonnet-5"}'
    prompt_pins: dict[str, dict[str, str]] = Field(default_factory=dict)
    model_pins: dict[str, str] = Field(default_factory=dict)

    # --- Contract B durable execution (M2-ADR-016, M2-ADR-017) ----------------------------------
    # Off by default and separately from ai_enabled, because the two answer different questions:
    # ai_enabled says whether this service may call a model at all, this says whether the durable
    # provider surface exists. Leaving it off means the Contract B endpoints are absent rather than
    # present-and-refusing, which is the stronger position while the path is unqualified.
    #
    # Turning it on requires a real credential for the same reason a live route does: the Message
    # Batches API is the only durable path M2-ADR-016 accepts, and there is no fake that could prove
    # recovery against a provider that is not there.
    durable_execution_enabled: bool = False

    # --- workload identity (M1-ADR-003) ---------------------------------------------------------
    # Spring authenticates as itself with a Keycloak client-credentials token carrying the
    # `ramals-ai` audience. A learner token carries `ramals-api` and is rejected here.
    workload_auth_enabled: bool = True
    oidc_issuer: str = "http://keycloak:8080/realms/ramals"
    oidc_audience: str = "ramals-ai"
    expected_workload_client_id: str = "ramals-core-workload"
    jwks_cache_seconds: int = Field(default=300, gt=0, le=3600)

    # --- LLM observability (Langfuse, via LiteLLM's native langfuse_otel callback) ---------------
    # Off by default: with it off, litellm's callback list is never touched, and a build without the
    # `provider` extra (litellm not installed) is completely unaffected -- the same "absent means
    # safely off" discipline every other flag in this file holds to.
    #
    # The three fields below are read from LANGFUSE_PUBLIC_KEY/LANGFUSE_SECRET_KEY/LANGFUSE_HOST --
    # deliberately NOT the RAMALS_AI_ prefix every other setting in this file uses, because
    # LiteLLM's own langfuse_otel integration (litellm.integrations.langfuse.langfuse_otel) reads
    # exactly these three names directly from the environment. Duplicating them under a RAMALS
    # prefix would just be a second place for the same secret to go stale.
    #
    # LANGFUSE_HOST is required, not optional, when tracing is enabled -- see the validator below.
    # Tracing sends full, unredacted prompts and completions (README.md records this as a deliberate
    # policy decision); leaving the destination unset would let litellm's langfuse_otel integration
    # fall back to Langfuse's own cloud endpoint the moment both keys are set, turning an
    # observability flag into an implicit external export of learner-facing content nobody chose.
    langfuse_tracing_enabled: bool = False
    langfuse_public_key: str | None = Field(
        default=None, repr=False, validation_alias="LANGFUSE_PUBLIC_KEY"
    )
    langfuse_secret_key: str | None = Field(
        default=None, repr=False, validation_alias="LANGFUSE_SECRET_KEY"
    )
    langfuse_host: str | None = Field(default=None, validation_alias="LANGFUSE_HOST")

    @model_validator(mode="after")
    def _require_langfuse_destination_when_enabled(self) -> Settings:
        """Tracing that silently has no explicit destination is worse than none.

        All three of LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY and LANGFUSE_HOST must be set and
        non-blank -- LANGFUSE_HOST included. Without that, an operator who sets only the two keys
        would have litellm's langfuse_otel integration silently pick Langfuse's own cloud default,
        exporting full learner-facing prompts/completions to a destination nobody configured. This
        never substitutes a default (localhost or otherwise) for a missing host; it only refuses.
        """
        required = (self.langfuse_public_key, self.langfuse_secret_key, self.langfuse_host)
        all_present = all(value and value.strip() for value in required)
        if self.langfuse_tracing_enabled and not all_present:
            raise ValueError(
                "RAMALS_AI_LANGFUSE_TRACING_ENABLED requires LANGFUSE_PUBLIC_KEY, "
                "LANGFUSE_SECRET_KEY and LANGFUSE_HOST all set and non-blank (the same names every "
                "Langfuse SDK reads, not RAMALS_AI_-prefixed) -- LANGFUSE_HOST must be explicit "
                "because tracing sends full learner-facing prompts/completions and must never fall "
                "back to Langfuse's cloud default"
            )
        return self

    # --- MCP-3: outgoing Java MCP client (M2-ADR-031) --------------------------------------------
    # The reverse direction from workload_auth_enabled above: here ramals-ai is the *caller*,
    # authenticating to Java's MCP transport as ramals-ai-workload, audience ramals-mcp -- a
    # dedicated identity, never M1-ADR-003's own ramals-core-workload/aud=ramals-ai (that credential
    # authenticates Java calling *this* service, the opposite direction; this process never holds
    # its secret). Off by default: with mcp_enabled=false the deterministic/AI service still starts
    # and serves every existing path, and only an actual MCP-read call fails, clearly, at invocation
    # time -- the same "absent means safely off, not a startup failure" discipline ai_enabled/
    # durable_execution_enabled already hold this service to.
    mcp_enabled: bool = False
    mcp_base_url: str = ""
    """Java MCP server base URL, e.g. http://learning-platform:8080 -- the /mcp path is joined by
    the client, never duplicated in this setting."""
    mcp_workload_token_url: str = ""
    """The Keycloak client-credentials token endpoint -- explicit, not derived from oidc_issuer,
    mirroring WorkloadTokenProvider's own RAMALS_AI_WORKLOAD_TOKEN_URL convention on the Java
    side."""
    mcp_workload_client_id: str = "ramals-ai-workload"
    mcp_workload_client_secret: str | None = Field(default=None, repr=False)
    mcp_workload_audience: str = "ramals-mcp"

    @model_validator(mode="after")
    def _require_mcp_config_when_enabled(self) -> Settings:
        """An MCP client with nowhere to call, authenticate, or connect is a misconfiguration, not a
        degraded mode -- discovering it on the first learner-facing MCP read would surface as an
        opaque connection error long after deployment."""
        if self.mcp_enabled and not (
            self.mcp_base_url.strip()
            and self.mcp_workload_token_url.strip()
            and self.mcp_workload_client_secret
        ):
            raise ValueError(
                "RAMALS_AI_MCP_ENABLED requires RAMALS_AI_MCP_BASE_URL, "
                "RAMALS_AI_MCP_WORKLOAD_TOKEN_URL, and RAMALS_AI_MCP_WORKLOAD_CLIENT_SECRET"
            )
        return self

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
        if self.durable_execution_enabled and not self.provider_api_key:
            raise ValueError(
                "RAMALS_AI_DURABLE_EXECUTION_ENABLED requires RAMALS_AI_PROVIDER_API_KEY; "
                "Contract B has no deterministic fake, because a fake cannot prove recovery"
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
