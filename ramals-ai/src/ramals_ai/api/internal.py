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
import re
from collections.abc import Callable

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from ramals_ai.adaptation.agent import AdaptationAgent
from ramals_ai.assessment.agent import AssessmentAgent
from ramals_ai.contracts.generated import (
    AIProposalEnvelope,
    AIRequestEnvelope,
    DiagnosticAssessmentRequest,
)
from ramals_ai.diagnostic.agent import DiagnosticAgent
from ramals_ai.diagnostic_assessment.agent import (
    REQUIRED_SOURCES,
    DiagnosticAssessmentAgent,
)
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.graph.limits import CeilingExceeded
from ramals_ai.grounding.contracts import GroundedContext
from ramals_ai.security.workload_identity import (
    WorkloadAuthenticationError,
    WorkloadIdentity,
    WorkloadTokenVerifier,
)
from ramals_ai.telemetry import correlation, tracing
from ramals_ai.telemetry.logging import business_event
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

    Adaptation is activated by M1-T11 and follows the same authenticated proposal boundary.
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

    @router.post("/diagnostic-assessment/propose", response_model=AIProposalEnvelope)
    def diagnostic_assessment_propose(
        request: Request, payload: DiagnosticAssessmentRequest
    ) -> AIProposalEnvelope | JSONResponse:
        """M2-T09. Consumes the context Spring built; Spring's gate decides what it means.

        The transported context is re-validated here against the fail-closed consumer contract
        rather than trusted because it arrived over a typed boundary. The generated model proves the
        shape; ``GroundedContext`` proves the freshness, size and sensitivity rules, and those are
        the ones that fail closed.
        """
        agent: DiagnosticAssessmentAgent = request.app.state.agents["diagnostic_assessment"]
        try:
            context = GroundedContext.model_validate(
                payload.groundedContext.model_dump(mode="json")
            )
            # Checked before dispatch so a context missing a required source costs no provider call.
            # The agent checks again; this is the boundary refusing early, not instead.
            context.require_grounding(set(REQUIRED_SOURCES))
        except ValueError as refused:
            return _problem(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                _grounding_code(refused),
                "The supplied grounded context was refused.",
            )
        deadline = Deadline.in_ms(payload.constraints.deadlineMs)
        return _execute(
            lambda: agent.propose(
                context,
                interaction_id=payload.interactionId,
                request_id=payload.requestId,
                deadline=deadline,
                interaction_class=payload.constraints.interactionClass,
            )
        )

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
        try:
            requested_difficulty = _requested_difficulty(envelope)
        except InvalidPolicyInputError as failure:
            return _problem(
                status.HTTP_422_UNPROCESSABLE_ENTITY,
                "INVALID_POLICY_INPUT",
                str(failure),
            )
        return _run(agent.propose, envelope, requested_difficulty=requested_difficulty)

    @router.post("/assessment/evaluate", response_model=AIProposalEnvelope)
    def assessment_evaluate(
        request: Request, envelope: AIRequestEnvelope
    ) -> AIProposalEnvelope | JSONResponse:
        agent: AssessmentAgent = request.app.state.agents["assessment"]
        return _run(agent.evaluate, envelope)

    @router.post("/adaptation/propose", response_model=AIProposalEnvelope)
    def adaptation_propose(
        request: Request, envelope: AIRequestEnvelope
    ) -> AIProposalEnvelope | JSONResponse:
        agent: AdaptationAgent = request.app.state.agents["adaptation"]
        return _run(agent.propose, envelope)

    return router


def _run(
    call: AgentCall, envelope: AIRequestEnvelope, **kwargs: object
) -> AIProposalEnvelope | JSONResponse:
    """Execute one agent with the caller's absolute deadline and normalize boundary failures."""
    deadline = Deadline.in_ms(envelope.constraints.deadlineMs)
    return _execute(lambda: call(envelope, deadline=deadline, **kwargs))


def _execute(produce: Callable[[], AIProposalEnvelope]) -> AIProposalEnvelope | JSONResponse:
    """Runs one bounded agent call and normalizes every boundary failure to the fixed taxonomy."""
    try:
        proposal = produce()
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
    except InvalidPolicyInputError as failure:
        return _problem(
            status.HTTP_422_UNPROCESSABLE_ENTITY,
            "INVALID_POLICY_INPUT",
            str(failure),
        )
    except Exception as failure:  # noqa: BLE001 - authoritative API exception boundary
        business_event(
            logger,
            level=logging.ERROR,
            operation="http.request.failed",
            message="Unexpected AI request failure",
            fields={
                "errorCode": "UNEXPECTED_ERROR",
                "statusCode": status.HTTP_500_INTERNAL_SERVER_ERROR,
                "outcome": "FAILURE",
                "exceptionType": type(failure).__name__,
            },
            exception=failure,
        )
        return _problem(
            status.HTTP_500_INTERNAL_SERVER_ERROR,
            "UNEXPECTED_ERROR",
            "The AI execution service could not complete the request.",
            emit_event=False,
        )


def _requested_difficulty(envelope: AIRequestEnvelope) -> str:
    """Read the deterministic difficulty selected by Spring when it is carried by the capability."""
    requested = envelope.requestedCapability
    if requested not in {"FOUNDATIONAL", "INTERMEDIATE", "ADVANCED"}:
        raise InvalidPolicyInputError(
            "Spring must provide requestedCapability as FOUNDATIONAL, INTERMEDIATE, or ADVANCED."
        )
    return requested


def _grounding_code(refused: Exception) -> str:
    """The stable code a grounding refusal already carries, or a bounded fallback.

    ``GroundedContext`` raises codes such as GROUNDING_STALE and GROUNDING_REQUIRED_SOURCE_MISSING.
    Pydantic raises prose. Returning the code where there is one keeps the boundary's taxonomy the
    same as the contract's; falling back keeps a caller from receiving a validator's English.
    """
    # Searched rather than matched at line start: a code raised inside a pydantic validator arrives
    # embedded in the validator's prose ("Value error, GROUNDING_STALE [type=...]"), while
    # require_grounding raises it bare. Both must yield the same code.
    found = re.search(r"GROUNDING_[A-Z_]{3,50}", str(refused))
    return found.group(0) if found else "GROUNDING_CONTRACT_INVALID"


class InvalidPolicyInputError(ValueError):
    """A required deterministic policy input was absent or outside the contract policy."""


def _problem(code_status: int, code: str, detail: str, *, emit_event: bool = True) -> JSONResponse:
    if emit_event:
        business_event(
            logger,
            level=logging.WARNING,
            operation="http.request.rejected",
            message="AI request rejected",
            fields={
                "errorCode": code,
                "statusCode": code_status,
                "outcome": "REJECTED",
            },
        )
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
