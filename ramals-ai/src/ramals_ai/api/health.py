"""Liveness and readiness.

These answer different questions and must never be aliases of each other. Liveness asks "is this
process alive" — a false answer should get the container restarted. Readiness asks "should traffic
be routed here" — a false answer should take it out of rotation without killing it.

Collapsing them is a common and expensive mistake: a readiness probe that restarts pods during a
transient dependency blip turns a partial outage into a full one.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

from fastapi import APIRouter, Response, status
from pydantic import BaseModel


@dataclass
class ServiceState:
    """Mutable readiness state owned by the application lifespan."""

    ready: bool = False
    not_ready_reason: str | None = field(default="starting")

    def mark_ready(self) -> None:
        self.ready = True
        self.not_ready_reason = None

    def mark_not_ready(self, reason: str) -> None:
        self.ready = False
        self.not_ready_reason = reason


class LivenessResponse(BaseModel):
    status: Literal["UP"]


class ReadinessResponse(BaseModel):
    status: Literal["UP", "OUT_OF_SERVICE"]
    reason: str | None = None


def build_health_router(state: ServiceState) -> APIRouter:
    router = APIRouter(prefix="/health", tags=["operational"])

    @router.get("/live", response_model=LivenessResponse)
    def live() -> LivenessResponse:
        """Alive if this handler runs at all. Deliberately checks nothing else."""
        return LivenessResponse(status="UP")

    @router.get("/ready", response_model=ReadinessResponse)
    def ready(response: Response) -> ReadinessResponse:
        if state.ready:
            return ReadinessResponse(status="UP")
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return ReadinessResponse(status="OUT_OF_SERVICE", reason=state.not_ready_reason)

    return router
