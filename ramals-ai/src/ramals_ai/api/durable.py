"""The Contract B provider surface: submit, status, result. Nothing else.

Three endpoints, and each one is a single call through to the adapter. That thinness is the design
rather than an unfinished version of one. M2-ADR-017 §1 makes RAMALS-AI a **stateless durable
provider adapter** -- it *"submits, reports status, and returns results. It remembers nothing
between calls. Every durable fact it produces is handed back across the API boundary and written by
Spring."* Anything this module remembered would be a second writer of state the platform is
accountable for, and a second place a recovery worker could disagree with the ledger.

So there is no queue here, no retry loop, no cache and no reconciliation. A caller that dies between
two of these calls loses nothing that this service was holding, because it was holding nothing.

**Admission goes through the capability gate, every time.** Not once at startup: an adapter is
resolved per request, and M2-ADR-016 §4 requires that one which cannot honour Contract B *fails*
rather than falling through to a synchronous call. Checking here means the refusal costs no network
call and no tokens.

**These endpoints do not activate Contract B.** No public route reaches them; the platform's own
lifecycle service is the only caller, and it is disabled until crash/recovery qualification.
"""

from __future__ import annotations

import logging
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import asdict
from typing import Annotated, Any

from fastapi import APIRouter, Body, Depends, HTTPException, Query, Request, status
from pydantic import BaseModel, Field

from ramals_ai.api.internal import require_workload_identity
from ramals_ai.gateway.durable import require_durable_execution_support
from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.providers.base import (
    DurableSubmissionRequest,
    Message,
)
from ramals_ai.telemetry.logging import business_event

logger = logging.getLogger(__name__)


class DurableMessage(BaseModel):
    role: str = Field(min_length=1, max_length=32)
    content: str = Field(min_length=1)


class DurableSubmitRequest(BaseModel):
    """One durable submission, as the platform describes it.

    ``idempotency_key`` is carried because the adapter's contract takes one, and is **not** a claim
    that the provider honours it. M2-ADR-016 records that Anthropic's Message Batches API offers no
    documented replay-safe admission; the key is a correlation value RAMALS derives and matches on,
    not a guarantee against a duplicate provider execution.
    """

    request_id: str = Field(min_length=1, max_length=64)
    idempotency_key: str = Field(min_length=1, max_length=128)
    request_digest: str = Field(min_length=64, max_length=64)
    model: str = Field(min_length=1, max_length=128)
    max_output_tokens: int = Field(gt=0, le=64_000)
    messages: list[DurableMessage] = Field(min_length=1)


# Provider failures, as HTTP. Every code the durable surface can raise appears here, because the
# caller branches on the status: a rate limit must slow it down, an outage may be retried on the
# same cadence, and a misconfiguration must not be retried at all. Before this existed a 429 left
# as an unhandled 500 -- so the one failure that means "you are asking too quickly" arrived looking
# exactly like "this service is broken", and was retried just as fast (M2-ADR-020 section 7).
_HTTP_STATUS_BY_CODE: dict[GatewayErrorCode, int] = {
    GatewayErrorCode.PROVIDER_RATE_LIMITED: status.HTTP_429_TOO_MANY_REQUESTS,
    GatewayErrorCode.PROVIDER_UNAVAILABLE: status.HTTP_503_SERVICE_UNAVAILABLE,
    GatewayErrorCode.PROVIDER_TIMEOUT: status.HTTP_504_GATEWAY_TIMEOUT,
    GatewayErrorCode.PROVIDER_AUTH_ERROR: status.HTTP_502_BAD_GATEWAY,
    GatewayErrorCode.PROVIDER_INVALID_REQUEST: status.HTTP_502_BAD_GATEWAY,
    GatewayErrorCode.INVALID_STRUCTURED_OUTPUT: status.HTTP_502_BAD_GATEWAY,
    GatewayErrorCode.ROUTE_NOT_CONFIGURED: status.HTTP_503_SERVICE_UNAVAILABLE,
    GatewayErrorCode.CONTRACT_B_UNSUPPORTED: status.HTTP_503_SERVICE_UNAVAILABLE,
    GatewayErrorCode.TOKEN_CEILING_EXCEEDED: status.HTTP_403_FORBIDDEN,
    GatewayErrorCode.COST_CEILING_EXCEEDED: status.HTTP_403_FORBIDDEN,
    GatewayErrorCode.DEADLINE_EXCEEDED: status.HTTP_504_GATEWAY_TIMEOUT,
}


# Whether a failed submission can have left a provider execution behind. The caller decides between
# a definite FAILED and an ambiguous INDETERMINATE on this and nothing else, so it is stated
# explicitly here rather than inferred from a status code on the other side of the wire.
#
# A status cannot carry this. The same 500 means "the SDK was missing" and "the connection dropped
# after create was sent", and those are opposite answers. Saying which one it was is knowledge only
# this process has, and losing it at the boundary is what made a 5xx-after-create look retryable.
SUBMISSION_NOT_CREATED = "NOT_CREATED"
"""No provider execution exists. Safe for the caller to record a definite failure."""

SUBMISSION_MAY_EXIST = "MAY_EXIST"
"""A provider execution may exist and cannot be named. The caller must fail closed."""

# Only a *deliberate, parsed rejection* rules creation out. Everything here was decided by something
# that read the request and said no -- our own capability gate, our own governance ceilings, or the
# provider answering with a classified refusal before any batch could be created.
_CREATION_RULED_OUT: frozenset[GatewayErrorCode] = frozenset(
    {
        # Refused before the adapter was reached at all.
        GatewayErrorCode.CONTRACT_B_UNSUPPORTED,
        GatewayErrorCode.ROUTE_NOT_CONFIGURED,
        # Refused by our own governance, before any provider call.
        GatewayErrorCode.TOKEN_CEILING_EXCEEDED,
        GatewayErrorCode.COST_CEILING_EXCEEDED,
        GatewayErrorCode.DEADLINE_EXCEEDED,
        # The provider parsed the request and rejected it. A 400, a 401/403 or a 429 on batch
        # creation is an answer, and an answer means nothing was created.
        GatewayErrorCode.PROVIDER_INVALID_REQUEST,
        GatewayErrorCode.PROVIDER_AUTH_ERROR,
        GatewayErrorCode.PROVIDER_RATE_LIMITED,
    }
)

# Deliberately absent, and each for a reason:
#   PROVIDER_TIMEOUT     -- the request may have arrived and been acted on; a timeout is the absence
#                           of an answer, never an answer.
#   PROVIDER_UNAVAILABLE -- covers connection resets and the provider's own 5xx. A reset can happen
#                           after the bytes were sent, and a provider 500 can follow work it already
#                           began. Neither rules creation out.
# Anything not listed in _CREATION_RULED_OUT is treated as MAY_EXIST, so a code added later fails
# closed until someone decides otherwise.


def _submission_disposition(code: GatewayErrorCode) -> str:
    return SUBMISSION_NOT_CREATED if code in _CREATION_RULED_OUT else SUBMISSION_MAY_EXIST


def _mark_submission(failure: HTTPException, disposition: str) -> None:
    """Adds the disposition to an HTTPException raised before the adapter was reached.

    ``detail`` is typed ``str`` upstream but is a dict everywhere this service raises one, so it is
    read through ``Any``. A detail that is genuinely a string is left alone rather than rewritten --
    the caller treats a missing marker as MAY_EXIST, which is the safe reading.
    """
    detail: Any = failure.detail
    if isinstance(detail, dict):
        detail["submission"] = disposition


@contextmanager
def _provider_failures_as_http() -> Iterator[None]:
    """Turns a classified provider failure into the status that says the same thing.

    An unmapped code becomes 502 rather than 500: a code this build does not recognise is still a
    statement about the provider, and 500 would send an operator looking for a fault in this
    service.

    ``Retry-After`` is echoed when the provider supplied one. It is a number of seconds and carries
    no request content, and it is the only part of a provider error response this boundary passes
    on -- the caller has no other way to learn when the provider will serve again.
    """
    try:
        yield
    except GatewayError as failure:
        headers = None
        if failure.retry_after_ms is not None:
            headers = {"Retry-After": str(max(1, round(failure.retry_after_ms / 1000)))}
        raise HTTPException(
            status_code=_HTTP_STATUS_BY_CODE.get(failure.code, status.HTTP_502_BAD_GATEWAY),
            detail={"code": failure.code.value, "detail": str(failure)},
            headers=headers,
        ) from failure


def build_durable_router() -> APIRouter:
    """Router for the Contract B provider surface.

    Inherits ``require_workload_identity`` from its own dependency list rather than from the agent
    router, so a change to one cannot silently unauthenticate the other.
    """
    router = APIRouter(
        prefix="/internal/v1/durable",
        tags=["contract-b"],
        dependencies=[Depends(require_workload_identity)],
    )

    @router.post("/executions", status_code=status.HTTP_201_CREATED)
    def submit(
        request: Request, payload: Annotated[DurableSubmitRequest, Body()]
    ) -> dict[str, Any]:
        """Submits once and returns the provider execution handle.

        **Exactly one provider call, and no retry of it.** A submission that fails ambiguously --
        a timeout, a dropped connection -- must not be retried here, because a retry after an
        acknowledgement that was sent but not received is precisely how one logical request becomes
        two provider executions. The adapter is constructed with the SDK's own retries disabled for
        the same reason. The ambiguity is handed back to Spring, which is the only component holding
        the durable state needed to decide what it means.
        """
        # NOT wrapped in _provider_failures_as_http(). The read paths translate a provider failure
        # into the status that describes it; a submission needs to say something a status cannot:
        # whether a provider execution may now exist. That is stated explicitly in the body below,
        # because the caller decides between a definite failure and an ambiguous one on it, and
        # getting it wrong in the optimistic direction licenses a duplicate provider execution.
        try:
            adapter = _admitted(request)
            submission = adapter.submit(
                DurableSubmissionRequest(
                    request_id=payload.request_id,
                    idempotency_key=payload.idempotency_key,
                    request_digest=payload.request_digest,
                    model=payload.model,
                    messages=tuple(
                        Message(role=message.role, content=message.content)
                        for message in payload.messages
                    ),
                    max_output_tokens=payload.max_output_tokens,
                )
            )
        except HTTPException as refused:
            # The capability gate. It runs before the adapter, so nothing was created -- but it
            # raises its own HTTPException, and that detail needs the marker like any other.
            _mark_submission(refused, SUBMISSION_NOT_CREATED)
            raise
        except GatewayError as failure:
            raise HTTPException(
                status_code=_HTTP_STATUS_BY_CODE.get(failure.code, status.HTTP_502_BAD_GATEWAY),
                detail={
                    "code": failure.code.value,
                    "detail": str(failure),
                    "submission": _submission_disposition(failure.code),
                },
            ) from failure
        except Exception as unexpected:
            # A failure nobody classified, raised from somewhere inside the submission path. It
            # cannot prove the provider created nothing, so it must not be allowed to look like it
            # can. 502 with MAY_EXIST, never a bare 500 the caller has to interpret.
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail={
                    "code": "SUBMISSION_UNCLASSIFIED",
                    "detail": f"the submission failed with {type(unexpected).__name__}",
                    "submission": SUBMISSION_MAY_EXIST,
                },
            ) from unexpected

        # Past this line the batch exists. The response is built first and logged second, so that a
        # failure in logging cannot lose a provider execution the caller has no other way to learn
        # about -- which would recreate the very orphan this endpoint exists to avoid.
        acknowledgement = asdict(submission)
        try:
            business_event(
                logger,
                level=logging.INFO,
                operation="gateway.durable.submit",
                message="submitted a durable provider execution",
                fields={
                    "requestId": payload.request_id,
                    "providerExecutionId": submission.provider_execution_id,
                    "customId": submission.custom_id,
                    "state": submission.state,
                    "outcome": "SUBMITTED",
                },
            )
        except Exception:  # noqa: BLE001 - never lose an acknowledged execution to a logging fault
            logger.exception("failed to record a durable submission that succeeded")
        return acknowledgement

    @router.get("/executions")
    def search(
        request: Request,
        custom_id: Annotated[str, Query(min_length=1, max_length=128)],
        created_after: Annotated[str, Query(min_length=1, max_length=40)],
        created_before: Annotated[str, Query(min_length=1, max_length=40)],
        max_pages: Annotated[int, Query(ge=1, le=10)] = 10,
        max_inspections: Annotated[int, Query(ge=1, le=50)] = 50,
        exclude_id: Annotated[list[str] | None, Query()] = None,
    ) -> dict[str, Any]:
        """Finds every provider execution carrying a ``custom_id`` in a creation-time window.

        The lost-acknowledgement recovery path (M2-ADR-020). **Read-only**: this endpoint never
        creates an execution, which is the whole point -- it exists so that a lost acknowledgement
        has an answer that is not a resubmission.

        The window is the caller's, because the caller holds the durable ``submitted_at`` saying
        when the lost call happened. This service remembers nothing and could not derive it.

        So is the memory. ``exclude_id`` carries the batches the caller has already proven do not
        carry this key, and ``newly_excluded_ids`` returns the ones this search has just proven --
        the caller persists them (M2-ADR-020 section 3.1). The state lives on the caller's side
        because M2-ADR-017 section 1 makes Spring/PostgreSQL authoritative and this plane
        stateless, and because a cache that died with a process would repay its whole cost after
        every restart.

        ``max_inspections`` is the caller's remaining per-pass budget, so a bounded search resumes
        across attempts rather than re-reading the same first candidates. The bounds accepted here
        are M2-ADR-020 section 3's own -- ten pages, fifty inspections -- rather than the looser
        limits this endpoint used to allow, so the ADR cannot be exceeded by a query parameter.
        """
        with _provider_failures_as_http():
            result = _admitted(request).find_executions_by_custom_id(
                custom_id,
                created_after,
                created_before,
                max_pages,
                max_inspections,
                exclude_ids=tuple(exclude_id or ()),
            )
        business_event(
            logger,
            level=logging.WARNING if result.outcome != "ZERO" else logging.INFO,
            operation="gateway.durable.search",
            message="searched for a provider execution by correlation key",
            fields={
                "customId": custom_id,
                "outcome": str(result.outcome),
                "matches": len(result.matches),
                "batchesListed": result.batches_listed,
                "batchesInspected": result.batches_inspected,
                "batchesUninspectable": result.batches_uninspectable,
                "pagesFetched": result.pages_fetched,
                "limitReached": result.limit_reached,
                "batchesExcluded": result.batches_excluded,
                "newlyExcluded": len(result.newly_excluded_ids),
            },
        )
        return {
            "outcome": str(result.outcome),
            "matches": [asdict(match) for match in result.matches],
            "batches_listed": result.batches_listed,
            "batches_inspected": result.batches_inspected,
            "batches_uninspectable": result.batches_uninspectable,
            "pages_fetched": result.pages_fetched,
            "limit_reached": result.limit_reached,
            "batches_excluded": result.batches_excluded,
            "newly_excluded_ids": list(result.newly_excluded_ids),
        }

    @router.get("/executions/{provider_execution_id}")
    def get_status(request: Request, provider_execution_id: str) -> dict[str, Any]:
        """Authoritative status for an execution this process did not necessarily start.

        The property that makes recovery possible at all: status is read from the provider by
        identity, so a replacement worker asks the same question the dead one would have.
        """
        with _provider_failures_as_http():
            return asdict(_admitted(request).get_status(provider_execution_id))

    @router.get("/executions/{provider_execution_id}/result")
    def get_result(
        request: Request,
        provider_execution_id: str,
        custom_id: Annotated[str | None, Query(max_length=128)] = None,
    ) -> dict[str, Any]:
        """One correlated record, selected by ``custom_id`` and never by position.

        Batch results are correlated by the caller's own key (M2-ADR-016 §3). Reading by position is
        how a result gets attributed to the wrong learner, and it is the difference between a
        correlated retrieval and a plausible one.
        """
        with _provider_failures_as_http():
            return asdict(_admitted(request).get_result(provider_execution_id, custom_id))

    return router


def _admitted(request: Request) -> Any:
    """The configured adapter, having passed the capability gate.

    The gate raises ``GatewayError`` and this boundary turns it into a status, because an
    unhandled refusal reaches the caller as a 500 -- which reads as "this service is broken" when
    the truthful answer is "this path is not supported here". The two send an operator to
    completely different places.

    Checked per call rather than once at startup: configuration can change under a running process,
    and a gate that stopped applying after the first request would be no gate at all.
    """
    adapter = _adapter(request)
    try:
        require_durable_execution_support(adapter)
    except GatewayError as refused:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={"code": refused.code.value, "detail": str(refused)},
        ) from refused
    return adapter


def _adapter(request: Request) -> Any:
    """The configured durable adapter, or a refusal.

    A missing adapter is ``CONTRACT_B_UNSUPPORTED`` rather than a 500. It is a statement about what
    this deployment can do, which is the same category of answer the capability gate gives, and an
    operator reading it should find one code rather than two.
    """
    adapter = getattr(request.app.state, "durable_adapter", None)
    if adapter is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": GatewayErrorCode.CONTRACT_B_UNSUPPORTED.value,
                "detail": "no durable execution adapter is configured in this deployment",
            },
        )
    return adapter


__all__ = ["DurableSubmitRequest", "build_durable_router"]
