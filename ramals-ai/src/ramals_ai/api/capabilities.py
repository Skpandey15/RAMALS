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

CONTRACT_VERSION = "1.0"


class Capabilities(BaseModel):
    contractVersion: str  # noqa: N815 - wire contract is camelCase, matching MVP-0 and Doc 03
    service: str
    version: str
    environment: str
    aiEnabled: bool  # noqa: N815
    modelRoute: str  # noqa: N815
    agents: list[str]
    authority: str


def build_capabilities_router(settings: Settings) -> APIRouter:
    router = APIRouter(tags=["operational"])

    @router.get("/capabilities", response_model=Capabilities)
    def capabilities() -> Capabilities:
        return Capabilities(
            contractVersion=CONTRACT_VERSION,
            service=settings.service_name,
            version=__version__,
            environment=settings.environment.value,
            aiEnabled=settings.ai_enabled,
            modelRoute=settings.model_route.value,
            # Agents arrive from M1-T07. An empty list is the honest answer, not a placeholder.
            agents=[],
            # Stated on the wire so no caller can mistake this service for a decision-maker.
            authority="NON_AUTHORITATIVE",
        )

    return router
