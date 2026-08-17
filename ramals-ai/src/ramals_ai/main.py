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
from ramals_ai.api.capabilities import build_capabilities_router
from ramals_ai.api.correlation import CorrelationMiddleware
from ramals_ai.api.health import ServiceState, build_health_router
from ramals_ai.api.internal import build_internal_router
from ramals_ai.config.settings import Settings, get_settings
from ramals_ai.security.workload_identity import build_verifier
from ramals_ai.telemetry.logging import configure_logging
from ramals_ai.telemetry.tracing import configure_tracing

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
    app.include_router(build_capabilities_router(resolved))
    # Agent endpoints join this router in M1-T07 and inherit its authentication, so a new endpoint
    # is protected by default rather than only when someone remembers to protect it.
    app.include_router(build_internal_router())
    app.state.service_state = state
    app.state.settings = resolved
    app.state.workload_verifier = build_verifier(resolved)
    return app


app = create_app
"""Import target for ASGI servers is `ramals_ai.main:create_app` with `--factory`.

Deliberately not a module-level instance: constructing the app at import time would resolve
configuration during collection, so a configuration error would surface as an import failure with a
confusing traceback instead of an explicit startup error.
"""
