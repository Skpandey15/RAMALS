"""Application factory for the RAMALS AI execution plane.

This service proposes; it never decides. It holds no learner authority, opens no connection to the
authoritative database, and runs no schema migration. Those are properties of the deployment as much
as of the code, and both are asserted by tests.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from ramals_ai import __version__
from ramals_ai.adaptation.agent import AdaptationAgent
from ramals_ai.api.capabilities import build_capabilities_router
from ramals_ai.api.correlation import CorrelationMiddleware
from ramals_ai.api.health import ServiceState, build_health_router
from ramals_ai.api.internal import build_internal_router
from ramals_ai.assessment.agent import AssessmentAgent
from ramals_ai.assessment_evaluation.agent import AssessmentEvaluationAgent
from ramals_ai.config.settings import ConfigurationError, ModelRoute, Settings, get_settings
from ramals_ai.diagnostic.agent import DiagnosticAgent
from ramals_ai.diagnostic_assessment.agent import DiagnosticAssessmentAgent
from ramals_ai.gateway.errors import GatewayError
from ramals_ai.gateway.gateway import LLMGateway
from ramals_ai.gateway.providers.base import ProviderAdapter
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.gateway.providers.litellm_adapter import LiteLLMProvider
from ramals_ai.gateway.routes import (
    RouteRegistry,
    pins_from_config,
    registry_from_pins,
    unbuildable_pointers,
)
from ramals_ai.prompting.register import default_prompt_register
from ramals_ai.prompting.templates import PromptRegister
from ramals_ai.security.workload_identity import build_verifier
from ramals_ai.telemetry.logging import configure_logging
from ramals_ai.telemetry.tracing import configure_tracing
from ramals_ai.tutor.agent import TutorAgent

logger = logging.getLogger(__name__)


def create_app(settings: Settings | None = None) -> FastAPI:
    """Builds the application.

    Settings are injectable so tests exercise real configurations rather than patching a global.
    """
    resolved = settings or get_settings()
    configure_logging(
        service=resolved.service_name,
        environment=resolved.environment.value,
        level=resolved.log_level,
    )

    configure_tracing(resolved)
    state = ServiceState()
    # One register for the process: the routes are validated against it at startup, and the agents
    # build from the same object. Letting the agents assemble their own would mean the thing that
    # was checked and the thing that runs are only equal by coincidence.
    prompts = default_prompt_register()
    gateway = LLMGateway(_adapter_for(resolved), registry=_route_registry(resolved, prompts))

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        # Readiness flips only once startup has actually completed, so a probe during boot correctly
        # reports OUT_OF_SERVICE while liveness already reports UP.
        logger.info(
            "ramals-ai starting",
            extra={
                "operation": "startup",
                "version": __version__,
                "aiEnabled": resolved.ai_enabled,
                "modelRoute": resolved.model_route.value,
                # Logged at startup so an operator verifying a rollback can read which route
                # configuration this process is actually running, rather than inferring it.
                "routeTableVersion": gateway.registry.version,
                # Every pointer, not just the default route's. An operator verifying a rollback
                # needs to read which prompt revision is live, and a single version cannot say --
                # this route may serve several templates.
                "promptVersions": ",".join(
                    f"{template.value}={version}"
                    for template, version in sorted(
                        gateway.registry.resolve(resolved.model_route).prompt_versions.items(),
                        key=lambda item: item[0].value,
                    )
                ),
            },
        )
        state.mark_ready()
        try:
            yield
        finally:
            # Stop taking traffic before the process goes away, so in-flight work drains rather
            # than being cut off at an arbitrary point.
            state.mark_not_ready("shutting down")
            logger.info("ramals-ai stopping", extra={"operation": "shutdown"})

    app = FastAPI(
        title="RAMALS AI execution plane",
        version=__version__,
        summary="Non-authoritative agent proposals for the RAMALS deterministic core.",
        lifespan=lifespan,
    )
    # First in the chain, ahead of authentication: a rejected request must still carry a support
    # code, or the learner is told something went wrong with nothing to quote.
    app.add_middleware(CorrelationMiddleware)
    app.include_router(build_health_router(state))
    app.include_router(build_capabilities_router(resolved, gateway.registry))
    # Agent endpoints inherit this router's authentication, so a new endpoint is protected by
    # default rather than only when someone remembers to protect it.
    app.include_router(build_internal_router())
    app.state.service_state = state
    app.state.settings = resolved
    app.state.workload_verifier = build_verifier(resolved)
    # Agents take the gateway from here rather than constructing one, so every model call in the
    # service shares one set of budgets.
    app.state.gateway = gateway
    app.state.prompts = prompts
    app.state.agents = {
        "diagnostic": DiagnosticAgent(gateway, prompts=prompts),
        "diagnostic_assessment": DiagnosticAssessmentAgent(gateway, prompts=prompts),
        "tutor": TutorAgent(gateway, prompts=prompts),
        "assessment": AssessmentAgent(gateway, prompts=prompts),
        "assessment_evaluation": AssessmentEvaluationAgent(gateway, prompts=prompts),
        "adaptation": AdaptationAgent(gateway, prompts=prompts),
    }
    return app


def _adapter_for(settings: Settings) -> ProviderAdapter:
    """Chooses the provider for this configuration.

    ``ci-fake`` is a real route, not a test double, so CI and a fresh checkout exercise the same
    gateway path a provider call takes -- without a credential and without a bill.
    """
    if settings.model_route is ModelRoute.CI_FAKE:
        return FakeProvider()

    provider = LiteLLMProvider(api_key=settings.provider_api_key)
    # Checked at startup for the same reason the credential is (see Settings): a live route whose
    # SDK is absent is a misconfiguration, not a degraded mode, and it is invisible until a learner
    # triggers it. The image installs the 'provider' extra precisely so this passes.
    try:
        provider.ensure_available()
    except GatewayError as unavailable:
        raise ConfigurationError(
            f"model route '{settings.model_route}' needs the provider SDK, which this build does "
            "not ship; install ramals-ai with the 'provider' extra, or set "
            "RAMALS_AI_MODEL_ROUTE=ci-fake"
        ) from unavailable
    return provider


app = create_app
"""Import target for ASGI servers is `ramals_ai.main:create_app` with `--factory`.

Deliberately not a module-level instance: constructing the app at import time would resolve
configuration during collection, so a configuration error would surface as an import failure with a
confusing traceback instead of an explicit startup error.
"""


def _route_registry(settings: Settings, prompts: PromptRegister) -> RouteRegistry:
    """The route table this process will serve, with any rollback pins applied.

    Resolved at startup and failing there, which is the same rule the rest of this module follows: a
    service that starts while silently ignoring a rollback looks exactly like one that applied it,
    and the difference only shows up in the outputs somebody was trying to stop producing.
    """
    prompt_pins, model_pins = pins_from_config(settings.prompt_pins, settings.model_pins)
    registry = registry_from_pins(prompts, prompt_pins=prompt_pins, model_pins=model_pins)

    unbuildable = unbuildable_pointers(registry, prompts)
    if unbuildable:
        # Checked even with no pins configured, because the route table names prompt versions as
        # literal strings and cannot import the prompt modules to check itself.
        raise ConfigurationError(
            "route table points at prompt revisions this build cannot produce: "
            + ", ".join(unbuildable)
        )
    return registry
