"""The Anthropic Message Batches adapter -- the durable-execution half of the provider boundary.

The second module in this service permitted to import a provider SDK, and the reason the isolation
test now names two files instead of one. That test's rule is unchanged: no agent, node or service
module may import a provider, so every call still passes through the gateway where it can be priced,
deadlined or refused. A second adapter *behind* that boundary does not weaken it; a single agent
importing a provider would.

LiteLLM is not used here, and that is a deliberate finding rather than a preference. At the pinned
1.97.0, LiteLLM implements exactly one of the operations Contract B needs for Anthropic: it can
retrieve a batch's status, cannot create one, cannot cancel or list one, and its retrieve transform
drops ``results_url`` -- so even the operation it supports cannot reach the results. M2-ADR-016
Addendum A records the evaluation.

Nothing in this module is wired to a route. No route is Contract B, no execution state is persisted,
and there is no reconciliation worker. This is the provider surface only, so that the durable
execution machinery has something verified to be written against.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.providers.base import (
    DurableExecutionCapability,
    DurableExecutionCounts,
    DurableExecutionMatch,
    DurableExecutionSearch,
    DurableResult,
    DurableSearchOutcome,
    DurableStatus,
    DurableSubmission,
    DurableSubmissionRequest,
    Message,
)

# The failure taxonomy is shared with the Contract A adapter rather than duplicated. It maps by
# exception *class name*, and the Anthropic SDK raises the same names LiteLLM normalizes
# (RateLimitError, APIConnectionError, APITimeoutError, BadRequestError, NotFoundError...), so one
# table serves both. Two copies of a failure policy is one copy too many -- they drift, and the
# drift shows up as two providers disagreeing about what a rate limit means.
from ramals_ai.gateway.providers.litellm_adapter import normalize_exception_name

if TYPE_CHECKING:  # pragma: no cover - import shape only
    pass

ANTHROPIC_BATCH_RESULT_RETENTION_DAYS = 29
"""Documented retention for Message Batch results, from the published Anthropic contract.

Declared as a capability field rather than hardcoded in a policy, because M2-ADR-017's 30-day
result ceiling was chosen *against* this number. If the provider changes it, the ceiling moves, and
the capability report is where that becomes visible.
"""

# Anthropic's processing_status, mapped to the durable state machine's vocabulary. Deliberately not
# collapsed into an OpenAI-shaped status: `native_status` carries the original alongside this, so
# nothing is lost by the translation.
_STATE_BY_PROCESSING_STATUS: dict[str, str] = {
    "in_progress": "RUNNING",
    "canceling": "CANCELLING",
    "ended": "RESULT_AVAILABLE",
}


class AnthropicBatchesProvider:
    """Durable recoverable execution over the Anthropic Message Batches API."""

    name = "anthropic-batches"

    def __init__(self, api_key: str | None = None) -> None:
        self._api_key = api_key
        self._client: Any = None

    # -- capability -------------------------------------------------------------------------------

    def durable_capability(self) -> DurableExecutionCapability:
        """What this path can prove, row by row, with the one failure stated plainly.

        ``replay_safe_admission=False`` is the important field. Anthropic documents no idempotency
        key on batch creation, so a lost acknowledgement can leave an execution this adapter did not
        record. What makes the path viable anyway is that ``custom_id`` travels with every result
        and batches can be enumerated, so the orphan is *findable* -- which is a reconciliation
        heuristic, never provider idempotency, and must never be reported as one.

        Stated rather than probed, and it performs no I/O: this describes the provider's published
        contract, not the reachability of this process's credentials.
        """
        return DurableExecutionCapability(
            supported=True,
            replay_safe_admission=False,
            durable_execution_id=True,
            status_lookup=True,
            result_retrieval=True,
            cancellation=True,
            result_retention_days=ANTHROPIC_BATCH_RESULT_RETENTION_DAYS,
            reason="",
        )

    # -- SDK access -------------------------------------------------------------------------------

    def _batches(self) -> Any:
        """Imports the SDK on first use, the same way the Contract A adapter does.

        Deferred for the same two reasons: CI runs the whole gateway path on ``ci-fake`` and should
        not pay for a provider library it will never call, and a build without the ``provider``
        extra must not fail to start because of one. A missing optional dependency is a
        misconfiguration, not a transient fault, so it surfaces as one and is never retried.
        """
        if self._client is None:
            try:
                import anthropic
            except ImportError as missing:  # pragma: no cover - exercised by the isolation test
                raise GatewayError(
                    GatewayErrorCode.ROUTE_NOT_CONFIGURED,
                    "anthropic is not installed; a durable model route needs the 'provider' extra",
                ) from missing
            # max_retries=0 is the whole point. The SDK retries twice by default, below RAMALS'
            # gateway and invisible to it -- and a retried batch *create* is a second logical
            # execution for one request on a provider that offers no idempotency key. Contract A
            # had to disable the same hidden layer in LiteLLM; Contract B cannot afford it at all.
            self._client = anthropic.Anthropic(api_key=self._api_key, max_retries=0)
        return self._client.messages.batches

    def ensure_available(self) -> None:
        """Loads the SDK now, so a build that cannot reach the provider says so at startup."""
        self._batches()

    # -- durable execution ------------------------------------------------------------------------

    def submit(self, request: DurableSubmissionRequest) -> DurableSubmission:
        """Creates a batch carrying exactly one request, keyed by the caller's idempotency key.

        One request per batch is not a misuse of the API so much as the only shape that fits: a
        RAMALS diagnostic is one learner's request, and a batch of several would tie unrelated
        learners' executions to a single durable handle and a single cancellation.

        ``custom_id`` is the caller's server-derived idempotency key. It is the only correlation
        handle that survives a lost acknowledgement, so it is set from the key rather than
        generated here.
        """
        batches = self._batches()
        try:
            batch = batches.create(
                requests=[
                    {
                        "custom_id": request.idempotency_key,
                        "params": {
                            "model": request.model,
                            "max_tokens": request.max_output_tokens,
                            "messages": [
                                {"role": message.role, "content": message.content}
                                for message in request.messages
                            ],
                        },
                    }
                ]
            )
        except Exception as failure:  # noqa: BLE001 - classified into the fixed taxonomy below
            raise self._normalize(failure) from None

        return DurableSubmission(
            provider_execution_id=str(batch.id),
            state=_STATE_BY_PROCESSING_STATUS.get(str(batch.processing_status), "ACCEPTED"),
            custom_id=request.idempotency_key,
            created_at=_timestamp(getattr(batch, "created_at", None)),
            expires_at=_timestamp(getattr(batch, "expires_at", None)),
        )

    def get_status(self, provider_execution_id: str) -> DurableStatus:
        """Authoritative provider status for an execution this process need not have started."""
        batches = self._batches()
        try:
            batch = batches.retrieve(provider_execution_id)
        except Exception as failure:  # noqa: BLE001
            raise self._normalize(failure) from None

        native = str(batch.processing_status)
        counts = getattr(batch, "request_counts", None)
        return DurableStatus(
            provider_execution_id=str(batch.id),
            state=_STATE_BY_PROCESSING_STATUS.get(native, "RUNNING"),
            native_status=native,
            counts=_counts(counts),
            # results_url is the field LiteLLM's transform discards, which is why this adapter
            # exists. Reported as a boolean rather than the URL: the URL is a short-lived,
            # credentialed handle, and the SDK fetches results by batch id anyway.
            results_available=bool(getattr(batch, "results_url", None)),
            created_at=_timestamp(getattr(batch, "created_at", None)),
            expires_at=_timestamp(getattr(batch, "expires_at", None)),
            ended_at=_timestamp(getattr(batch, "ended_at", None)),
            cancel_initiated_at=_timestamp(getattr(batch, "cancel_initiated_at", None)),
        )

    def get_result(self, provider_execution_id: str, custom_id: str | None = None) -> DurableResult:
        """Streams the batch results and returns the record matching ``custom_id``.

        Matched by key, never by position. The API documents that results may be returned out of
        request order, so taking the first record would silently attribute one learner's diagnosis
        to another request the day a batch carried more than one.
        """
        batches = self._batches()
        try:
            results = batches.results(provider_execution_id)
            for record in results:
                if custom_id is not None and str(record.custom_id) != custom_id:
                    continue
                return self._to_result(provider_execution_id, record)
        except GatewayError:
            raise
        except Exception as failure:  # noqa: BLE001
            raise self._normalize(failure) from None

        raise GatewayError(
            GatewayErrorCode.INVALID_STRUCTURED_OUTPUT,
            "the batch results contained no record for the requested correlation key",
        )

    def find_executions_by_custom_id(
        self,
        custom_id: str,
        created_after: str,
        created_before: str,
        max_pages: int = 10,
        max_inspections: int = 50,
    ) -> DurableExecutionSearch:
        """Enumerates batches in a creation-time window and correlates them by ``custom_id``.

        Two calls per candidate, and that shape is forced rather than chosen: ``GET
        /v1/messages/batches`` returns no ``custom_id``, so the listing only narrows the field
        and the correlation must come from opening each candidate's results. Everything bounded
        here is bounded because of that second call.

        Pages backwards from newest via ``before_id`` and stops once a whole page predates the
        window. A lost acknowledgement is recovered soon after it happens; starting from the oldest
        batch would page through the workspace's entire history to reach the relevant hour.

        Never creates anything. This is the recovery path for an execution that may already exist,
        and a create call here would produce the duplicate the search exists to detect.
        """
        batches = self._batches()
        matches: list[DurableExecutionMatch] = []
        listed = inspected = uninspectable = pages = 0
        cursor: str | None = None
        limit_reached: str | None = None
        exhausted_window = False

        while pages < max_pages and not exhausted_window:
            try:
                page = batches.list(limit=100, **({"before_id": cursor} if cursor else {}))
                batch_list = list(page)
            except Exception as failure:  # noqa: BLE001
                raise self._normalize(failure) from None

            pages += 1
            if not batch_list:
                exhausted_window = True
                break

            older_than_window = 0
            for batch in batch_list:
                listed += 1
                created = _timestamp(getattr(batch, "created_at", None))
                if created is None:
                    uninspectable += 1
                    continue
                if created > created_before:
                    # Newer than the window. Paging newest-first, so these precede the candidates.
                    continue
                if created < created_after:
                    older_than_window += 1
                    continue

                # In the window. No results_url means the batch has not ended, so there is nothing
                # to correlate against -- uninspectable, which is not "does not match".
                if not getattr(batch, "results_url", None):
                    uninspectable += 1
                    continue

                if inspected >= max_inspections:
                    limit_reached = "inspections"
                    break

                inspected += 1
                readable, found = self._inspect(batch, custom_id)
                if not readable:
                    uninspectable += 1
                elif found is not None:
                    matches.append(found)

            if limit_reached:
                break
            # Every batch on this page predated the window, so every later page does too.
            if older_than_window == len(batch_list):
                exhausted_window = True
                break
            cursor = str(batch_list[-1].id)

        if limit_reached is None and pages >= max_pages and not exhausted_window:
            limit_reached = "pages"

        if len(matches) > 1:
            outcome = DurableSearchOutcome.MULTIPLE
        elif len(matches) == 1:
            outcome = DurableSearchOutcome.ONE
        elif uninspectable or limit_reached:
            # The distinction M2-ADR-020 section 2 turns on: nothing found, search unfinished.
            # Reporting ZERO would claim no orphan exists on the strength of a search that could
            # not see one.
            outcome = DurableSearchOutcome.INCONCLUSIVE
        else:
            outcome = DurableSearchOutcome.ZERO

        return DurableExecutionSearch(
            outcome=outcome,
            matches=tuple(matches),
            batches_listed=listed,
            batches_inspected=inspected,
            batches_uninspectable=uninspectable,
            pages_fetched=pages,
            limit_reached=limit_reached,
        )

    def _inspect(
        self, batch: Any, custom_id: str
    ) -> tuple[bool, DurableExecutionMatch | None]:
        """Opens one batch's results and looks for the key.

        Returns ``(readable, match)``. Two values rather than an optional because there are three
        answers, and the third is the one that matters: a batch whose results would not stream has
        told us nothing, and folding that into "no match" is the fail-open this design exists to
        avoid.
        """
        provider_execution_id = str(batch.id)
        try:
            for record in self._batches().results(provider_execution_id):
                if str(record.custom_id) != custom_id:
                    continue
                result = self._to_result(provider_execution_id, record)
                return True, DurableExecutionMatch(
                    provider_execution_id=provider_execution_id,
                    custom_id=custom_id,
                    outcome=result.outcome,
                    input_tokens=result.input_tokens,
                    output_tokens=result.output_tokens,
                    cached_input_tokens=result.cached_input_tokens,
                    created_at=_timestamp(getattr(batch, "created_at", None)),
                    ended_at=_timestamp(getattr(batch, "ended_at", None)),
                    native_status=str(getattr(batch, "processing_status", "")) or None,
                )
        except Exception:  # noqa: BLE001 - an unreadable candidate is unknown, never a non-match
            return False, None
        return True, None

    def cancel(self, provider_execution_id: str) -> DurableStatus:
        """Requests cancellation. The batch reaches ``ended`` and may carry partial results."""
        batches = self._batches()
        try:
            batch = batches.cancel(provider_execution_id)
        except Exception as failure:  # noqa: BLE001
            raise self._normalize(failure) from None
        native = str(batch.processing_status)
        return DurableStatus(
            provider_execution_id=str(batch.id),
            state=_STATE_BY_PROCESSING_STATUS.get(native, "CANCELLING"),
            native_status=native,
            counts=_counts(getattr(batch, "request_counts", None)),
            results_available=bool(getattr(batch, "results_url", None)),
            cancel_initiated_at=_timestamp(getattr(batch, "cancel_initiated_at", None)),
        )

    # -- mapping ----------------------------------------------------------------------------------

    @staticmethod
    def _to_result(provider_execution_id: str, record: Any) -> DurableResult:
        """Maps one ``MessageBatchIndividualResponse`` onto the durable result type.

        The four result variants are kept apart. ``expired`` in particular is its own outcome and
        not an error: it means the batch hit the provider's processing deadline without the request
        being sent, and an operator reading "errored" would go looking for a provider fault that
        never happened.
        """
        outcome = str(getattr(record.result, "type", "errored"))
        custom_id = str(record.custom_id)

        if outcome != "succeeded":
            return DurableResult(
                provider_execution_id=provider_execution_id,
                outcome=outcome,
                custom_id=custom_id,
                error_code=_error_code(record.result),
            )

        message = record.result.message
        usage = getattr(message, "usage", None)
        return DurableResult(
            provider_execution_id=provider_execution_id,
            outcome="succeeded",
            custom_id=custom_id,
            text=_text_of(message),
            input_tokens=int(getattr(usage, "input_tokens", 0) or 0),
            output_tokens=int(getattr(usage, "output_tokens", 0) or 0),
            cached_input_tokens=int(getattr(usage, "cache_read_input_tokens", 0) or 0),
            provider_message_id=str(getattr(message, "id", "")) or None,
        )

    def _normalize(self, failure: Exception) -> GatewayError:
        """Maps an SDK exception onto the shared taxonomy, by class name.

        The provider's message is dropped for the reason the Contract A adapter drops it: provider
        errors routinely echo the request body, which here is a minimized learner context.
        """
        name = type(failure).__name__
        code = normalize_exception_name(name, getattr(failure, "status_code", None))
        return GatewayError(code, f"anthropic batch call failed with {name}")


def _counts(counts: Any) -> DurableExecutionCounts | None:
    if counts is None:
        return None
    return DurableExecutionCounts(
        processing=int(getattr(counts, "processing", 0) or 0),
        succeeded=int(getattr(counts, "succeeded", 0) or 0),
        errored=int(getattr(counts, "errored", 0) or 0),
        canceled=int(getattr(counts, "canceled", 0) or 0),
        expired=int(getattr(counts, "expired", 0) or 0),
    )


def _timestamp(value: Any) -> str | None:
    """ISO-8601, or None. A string because this layer stores nothing and compares nothing."""
    if value is None:
        return None
    isoformat = getattr(value, "isoformat", None)
    return isoformat() if callable(isoformat) else str(value)


def _text_of(message: Any) -> str:
    """Concatenates the text blocks of a message, ignoring every other block type.

    Non-text blocks are dropped rather than serialized. Thinking blocks in particular must never
    reach a durable result: M2-ADR-017 prohibits persisting internal reasoning outright, and the
    cheapest place to honour that is here, where it is never read in the first place.
    """
    blocks = getattr(message, "content", None) or []
    return "".join(
        str(getattr(block, "text", ""))
        for block in blocks
        if str(getattr(block, "type", "")) == "text"
    )


def _error_code(result: Any) -> str | None:
    error = getattr(result, "error", None)
    inner = getattr(error, "error", None)
    code = getattr(inner, "type", None) or getattr(error, "type", None)
    return str(code) if code else None


__all__ = ["ANTHROPIC_BATCH_RESULT_RETENTION_DAYS", "AnthropicBatchesProvider", "Message"]
