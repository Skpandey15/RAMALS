"""The authenticated internal surface.

Agent endpoints are added to this router from M1-T07 and inherit its authentication automatically.
That ordering is deliberate: attaching the guard to the router now means a future endpoint is
protected by default and would have to be explicitly moved elsewhere to be exposed, rather than
protected only if somebody remembers to add a decorator.

`/capabilities` stays public per the contract — it reports what this build serves and carries no
learner data.
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from ramals_ai.security.workload_identity import (
    WorkloadAuthenticationError,
    WorkloadIdentity,
    WorkloadTokenVerifier,
)

logger = logging.getLogger(__name__)

# auto_error=False so a missing header produces our problem document rather than FastAPI's default,
# keeping every rejection on this boundary identical in shape.
_bearer = HTTPBearer(auto_error=False)


def require_workload_identity(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> WorkloadIdentity:
    """Authenticates the caller as the RAMALS core workload, or refuses.

    Every failure returns the same status and code. Distinguishing "no token" from "wrong audience"
    from "unknown client" would tell a prober exactly which knob to turn next.
    """
    verifier: WorkloadTokenVerifier | None = getattr(request.app.state, "workload_verifier", None)
    if verifier is None:
        # Only reachable in a local profile with authentication switched off; settings validation
        # refuses that combination anywhere else.
        return WorkloadIdentity(subject="local-development", client_id="local", expires_at=0)

    if credentials is None or not credentials.credentials:
        raise _unauthorized(request, "missing bearer token")

    try:
        return verifier.verify(credentials.credentials)
    except WorkloadAuthenticationError as failure:
        raise _unauthorized(request, str(failure)) from failure


def _unauthorized(request: Request, reason: str) -> HTTPException:
    # The reason is logged, never returned. Support can correlate by interactionId.
    logger.warning(
        "internal AI request rejected",
        extra={
            "operation": "workload.authenticate",
            "errorCode": "WORKLOAD_AUTHENTICATION_REQUIRED",
            "reason": reason,
            "route": request.url.path,
        },
    )
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail={
            "type": "about:blank",
            "title": "Unauthorized",
            "status": status.HTTP_401_UNAUTHORIZED,
            "code": "WORKLOAD_AUTHENTICATION_REQUIRED",
            "detail": "This API is reachable only by the RAMALS core workload.",
        },
        headers={"WWW-Authenticate": "Bearer"},
    )


def build_internal_router() -> APIRouter:
    """Router for authenticated agent endpoints. Empty until M1-T07 adds the agents."""
    return APIRouter(
        prefix="/internal/v1",
        tags=["agents"],
        dependencies=[Depends(require_workload_identity)],
    )
