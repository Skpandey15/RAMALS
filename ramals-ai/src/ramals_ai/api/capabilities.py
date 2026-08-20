"""Capability advertisement.

Operational only. It reports what this build can do so an operator or the Spring core can see which
agents and routes are live without reading configuration off the host. It confers no authority and
returns no learner data.
"""

from __future__ import annotations

from fastapi import APIRouter
from pydantic import BaseModel

from ramals_ai import __version__
from ramals_ai.config.settings import Settings
from ramals_ai.contracts.generated import AgentType
from ramals_ai.gateway.routes import RouteRegistry

CONTRACT_VERSION = "1.0"


class Capabilities(BaseModel):
    contractVersion: str  # noqa: N815 - wire contract is camelCase, matching MVP-0 and Doc 03
    service: str
    version: str
    environment: str
    aiEnabled: bool  # noqa: N815
    modelRoute: str  # noqa: N815

    routeTableVersion: str  # noqa: N815
    """Which route configuration this process is serving, pins included.

    Reported because a rollback is only verifiable if the running service says what it is running.
    The alternative -- inferring it from the manifest that was deployed -- describes what somebody
    intended, which is the same as the truth right up until the moment it matters.

    It names prompt revisions and, after a model rollback, a model identifier. That is acceptable
    on an internal-plane endpoint Spring calls, and is what the observability HLD means by
    preferring identities and safe metadata over the artifacts themselves.
    """

    agents: list[str]
    authority: str


def build_capabilities_router(settings: Settings, registry: RouteRegistry) -> APIRouter:
    # Path fixed by the contract. Public: it reports what this build serves, no learner data.
    router = APIRouter(prefix="/internal/v1", tags=["operational"])

    @router.get("/capabilities", response_model=Capabilities)
    def capabilities() -> Capabilities:
        return Capabilities(
            contractVersion=CONTRACT_VERSION,
            service=settings.service_name,
            version=__version__,
            environment=settings.environment.value,
            aiEnabled=settings.ai_enabled,
            modelRoute=settings.model_route.value,
            routeTableVersion=registry.version,
            agents=[
                AgentType.DIAGNOSTIC,
                AgentType.TUTOR,
                AgentType.ASSESSMENT,
                AgentType.ADAPTATION,
            ],
            # Stated on the wire so no caller can mistake this service for a decision-maker.
            authority="NON_AUTHORITATIVE",
        )

    return router
