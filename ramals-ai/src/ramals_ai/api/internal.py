"""The authenticated internal surface.

Agent endpoints inherit this router's authentication automatically.
That ordering is deliberate: attaching the guard to the router now means a future endpoint is
protected by default and would have to be explicitly moved elsewhere to be exposed, rather than
protected only if somebody remembers to add a decorator.

`/capabilities` stays public per the contract — it reports what this build serves and carries no
learner data.
"""

from __future__ import annotations

import logging
from collections.abc import Callable

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from ramals_ai.assessment.agent import AssessmentAgent
from ramals_ai.contracts.generated import AIProposalEnvelope, AIRequestEnvelope
from ramals_ai.diagnostic.agent import DiagnosticAgent
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.graph.limits import CeilingExceeded
from ramals_ai.security.workload_identity import (
    WorkloadAuthenticationError,
    WorkloadIdentity,
    WorkloadTokenVerifier,
)
from ramals_ai.telemetry import correlation, tracing
from ramals_ai.tutor.agent import TutorAgent

logger = logging.getLogger(__name__)

AgentCall = Callable[..., AIProposalEnvelope]

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
    """Router for the authenticated, non-authoritative agent endpoints.

    Adaptation is intentionally absent. Its contract remains declared, but activating it belongs to
    M1-T11 after this boundary has been proven end to end.
    """
    router = APIRouter(
        prefix="/internal/v1",
        tags=["agents"],
        dependencies=[Depends(require_workload_identity)],
    )

    @router.post("/diagnostic/propose", response_model=AIProposalEnvelope)
    def diagnostic_propose(
        request: Request, envelope: AIRequestEnvelope
    ) -> AIProposalEnvelope | JSONResponse:
        agent: DiagnosticAgent = request.app.state.agents["diagnostic"]
        return _run(agent.propose, envelope)

    @router.post("/tutor/respond", response_model=AIProposalEnvelope)
    def tutor_respond(
        request: Request, envelope: AIRequestEnvelope
    ) -> AIProposalEnvelope | JSONResponse:
        agent: TutorAgent = request.app.state.agents["tutor"]
        return _run(agent.respond, envelope)

    @router.post("/assessment/propose", response_model=AIProposalEnvelope)
    def assessment_propose(
        request: Request, envelope: AIRequestEnvelope
    ) -> AIProposalEnvelope | JSONResponse:
        agent: AssessmentAgent = request.app.state.agents["assessment"]
        return _run(
            agent.propose,
            envelope,
            requested_difficulty=_requested_difficulty(envelope),
        )

    @router.post("/assessment/evaluate", response_model=AIProposalEnvelope)
    def assessment_evaluate(
        request: Request, envelope: AIRequestEnvelope
    ) -> AIProposalEnvelope | JSONResponse:
        agent: AssessmentAgent = request.app.state.agents["assessment"]
        return _run(agent.evaluate, envelope)

    return router


def _run(
    call: AgentCall, envelope: AIRequestEnvelope, **kwargs: object
) -> AIProposalEnvelope | JSONResponse:
    """Execute one agent with the caller's absolute deadline and normalize boundary failures."""
    deadline = Deadline.in_ms(envelope.constraints.deadlineMs)
    try:
        proposal = call(envelope, deadline=deadline, **kwargs)
        if proposal.validation is not None and not proposal.validation.schemaValid:
            return _problem(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "UNPROCESSABLE_PROPOSAL",
                "No valid proposal was produced within the bounded repair budget.",
            )
        return proposal
    except GatewayError as failure:
        code = failure.code.value
        response_status = (
            status.HTTP_504_GATEWAY_TIMEOUT
            if failure.code is GatewayErrorCode.DEADLINE_EXCEEDED
            else status.HTTP_503_SERVICE_UNAVAILABLE
        )
        return _problem(response_status, code, "The AI execution service is unavailable.")
    except CeilingExceeded:
        return _problem(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "UNPROCESSABLE_PROPOSAL",
            "No valid proposal was produced within the bounded repair budget.",
        )


def _requested_difficulty(envelope: AIRequestEnvelope) -> str:
    """Read the deterministic difficulty selected by Spring when it is carried by the capability."""
    requested = envelope.requestedCapability
    return (
        requested if requested in {"FOUNDATIONAL", "INTERMEDIATE", "ADVANCED"} else "FOUNDATIONAL"
    )


def _problem(code_status: int, code: str, detail: str) -> JSONResponse:
    return JSONResponse(
        status_code=code_status,
        media_type="application/problem+json",
        content={
            "type": "about:blank",
            "title": "AI request failed",
            "status": code_status,
            "code": code,
            "detail": detail,
            "interactionId": correlation.current_interaction_id(),
            "traceId": tracing.current_trace_id() or None,
        },
    )
