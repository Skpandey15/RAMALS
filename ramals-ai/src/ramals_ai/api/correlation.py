"""Correlation middleware: accept, validate, continue, echo.

Runs before everything else, including authentication, so a rejected request still carries a support
code. A learner told "something went wrong" with no reference number is a support ticket nobody can
answer.

Behaviour mirrors the Java ``InteractionIdFilter`` deliberately:

* a **missing** interactionId is generated — the caller gets a usable support code either way;
* an **invalid** one is rejected with 400, in every profile.

The plan permits repairing instead of rejecting outside shared environments. Rejecting everywhere is
the stricter reading and, more importantly, the consistent one: two runtimes disagreeing about the
same header is a fault that surfaces only in production.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Awaitable, Callable

from fastapi import Request, Response
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

from ramals_ai.telemetry import correlation as ids
from ramals_ai.telemetry import tracing

logger = logging.getLogger(__name__)

# Health probes are exempt: an orchestrator has no interaction to correlate, and metering its
# polling as "missing correlation" would bury a real signal under constant noise.
_EXEMPT_PATHS = ("/health/live", "/health/ready")


class CorrelationMiddleware(BaseHTTPMiddleware):
    """Binds correlation identifiers and continues the caller's trace."""

    async def dispatch(
        self, request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        if request.url.path in _EXEMPT_PATHS:
            return await call_next(request)

        headers = {key.lower(): value for key, value in request.headers.items()}
        supplied = headers.get(ids.INTERACTION_ID_HEADER.lower())
        route = request.url.path

        if supplied is None:
            tracing.correlation_missing.add(1, {"route": route})
            interaction_id = ids.new_interaction_id()
        elif not ids.is_canonical_uuid7(supplied):
            tracing.correlation_invalid.add(1, {"route": route})
            # Generated so the rejection itself is still correlated and findable in the logs.
            return self._reject(ids.new_interaction_id(), route)
        else:
            interaction_id = supplied

        if ids.TRACEPARENT_HEADER not in headers:
            # Not fatal, but it means this request's spans start a new trace and the Spring half of
            # the story is unreachable from them.
            tracing.trace_context_missing.add(1, {"route": route})

        request_id = ids.new_request_id()
        tokens = ids.bind(interaction_id, request_id)

        parent = tracing.context_from_headers(headers)
        started = time.perf_counter()
        try:
            with tracing.tracer().start_as_current_span(
                f"{request.method} {route}",
                context=parent,  # type: ignore[arg-type]
            ) as span:
                span.set_attribute("http.request.method", request.method)
                span.set_attribute("url.path", route)
                span.set_attribute("ramals.interaction_id", interaction_id)
                span.set_attribute("ramals.request_id", request_id)

                response = await call_next(request)

                span.set_attribute("http.response.status_code", response.status_code)
                self._apply_headers(response, interaction_id, request_id)
                logger.info(
                    "internal request served",
                    extra={
                        "operation": "http.request",
                        "route": route,
                        "statusCode": response.status_code,
                        "durationMs": round((time.perf_counter() - started) * 1000, 3),
                    },
                )
                return response
        finally:
            # Without this the next request on the same context inherits these identifiers, and the
            # correlation model quietly starts lying.
            ids.reset(tokens)

    @staticmethod
    def _apply_headers(response: Response, interaction_id: str, request_id: str) -> None:
        response.headers[ids.INTERACTION_ID_HEADER] = interaction_id
        response.headers[ids.REQUEST_ID_HEADER] = request_id
        trace_id = tracing.current_trace_id()
        if trace_id:
            response.headers[ids.TRACE_ID_HEADER] = trace_id

    def _reject(self, interaction_id: str, route: str) -> Response:
        logger.warning(
            "rejected a malformed interaction identifier",
            extra={
                "operation": "correlation.validate",
                "errorCode": "INVALID_INTERACTION_ID",
                "route": route,
            },
        )
        response = JSONResponse(
            status_code=400,
            media_type="application/problem+json",
            content={
                "type": "about:blank",
                "title": "Invalid interaction identifier",
                "status": 400,
                "code": "INVALID_INTERACTION_ID",
                "detail": "X-Interaction-ID must be a canonical lowercase UUIDv7.",
                "interactionId": interaction_id,
                "traceId": tracing.current_trace_id(),
            },
        )
        self._apply_headers(response, interaction_id, ids.new_request_id())
        return response
