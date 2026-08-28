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
        return asdict(submission)

    @router.get("/executions")
    def search(
        request: Request,
        custom_id: Annotated[str, Query(min_length=1, max_length=128)],
        created_after: Annotated[str, Query(min_length=1, max_length=40)],
        created_before: Annotated[str, Query(min_length=1, max_length=40)],
        max_pages: Annotated[int, Query(ge=1, le=50)] = 10,
        max_inspections: Annotated[int, Query(ge=1, le=200)] = 50,
    ) -> dict[str, Any]:
        """Finds every provider execution carrying a ``custom_id`` in a creation-time window.

        The lost-acknowledgement recovery path (M2-ADR-020). **Read-only**: this endpoint never
        creates an execution, which is the whole point -- it exists so that a lost acknowledgement
        has an answer that is not a resubmission.

        The window is the caller's, because the caller holds the durable ``submitted_at`` saying
        when the lost call happened. This service remembers nothing and could not derive it.
        """
        result = _admitted(request).find_executions_by_custom_id(
            custom_id, created_after, created_before, max_pages, max_inspections
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
        }

    @router.get("/executions/{provider_execution_id}")
    def get_status(request: Request, provider_execution_id: str) -> dict[str, Any]:
        """Authoritative status for an execution this process did not necessarily start.

        The property that makes recovery possible at all: status is read from the provider by
        identity, so a replacement worker asks the same question the dead one would have.
        """
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
