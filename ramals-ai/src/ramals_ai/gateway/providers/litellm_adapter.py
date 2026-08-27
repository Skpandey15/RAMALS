"""The LiteLLM adapter -- the only module in this service permitted to import a provider SDK.

LiteLLM already normalizes parameter naming, token accounting and exception types across providers.
Doing that by hand means discovering each provider's quirks in production, one incident at a time.
Keeping it behind this boundary means the choice can be revisited without touching an agent.

The import is deferred to first use rather than performed at module import. Two reasons, both
practical: the package pulls a large dependency tree that CI does not need to run the full agent
path on ``ci-fake``, and importing it eagerly would make a service configured for the fake route
fail to start because of a provider library it will never call.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.providers.base import (
    DurableExecutionCapability,
    Message,
    ProviderRequest,
    ProviderResponse,
)

if TYPE_CHECKING:  # pragma: no cover - import shape only
    pass


class LiteLLMProvider:
    """Calls a real provider through LiteLLM and normalizes what comes back."""

    name = "litellm"

    def __init__(self, api_key: str | None = None) -> None:
        self._api_key = api_key
        self._litellm: Any = None

    def _module(self) -> Any:
        """Imports LiteLLM on first use, converting absence into a configuration failure.

        A missing optional dependency is a misconfiguration, not a transient fault, so it surfaces
        as one and is never retried.
        """
        if self._litellm is None:
            try:
                import litellm
            except ImportError as missing:  # pragma: no cover - exercised by the isolation test
                raise GatewayError(
                    GatewayErrorCode.ROUTE_NOT_CONFIGURED,
                    "litellm is not installed; a live model route needs the 'provider' extra",
                ) from missing
            # Provider errors carry the request body, which here is a minimized learner context.
            # Turning that off is what keeps it out of exception messages and logs.
            litellm.drop_params = True
            litellm.suppress_debug_info = True
            self._litellm = litellm
        return self._litellm

    def durable_capability(self) -> DurableExecutionCapability:
        """Declares Contract B unsupported on the synchronous path, and says which rows fail.

        Verified against the published Anthropic contract on 2026-08-27 and recorded in
        M2-ADR-016 §2: the Messages API documents no idempotency key, no status endpoint, and no
        GET for a previously created message. Four of five mandatory rows fail.

        Stated rather than probed. This is a property of the provider's published contract, not of
        this process, so there is nothing to discover at runtime -- and a probe that imported the
        SDK would make asking the question require the optional ``provider`` extra, on a path whose
        whole purpose is to refuse before anything is loaded.

        Contract B on this provider needs the Message Batches API, which is a different submission
        surface this adapter does not implement. When it does, this method changes with it; until
        then the honest answer is no.
        """
        return DurableExecutionCapability.unsupported(
            "the synchronous Messages API documents no replay-safe admission, no status lookup "
            "and no result retrieval; Contract B requires all three",
            replay_safe_admission=False,
            durable_execution_id=False,
            status_lookup=False,
            result_retrieval=False,
            cancellation=False,
            result_retention_days=0,
        )

    def ensure_available(self) -> None:
        """Imports the SDK now, so a build that cannot reach a provider says so at startup.

        The lazy import is right for the call path -- a service on ``ci-fake`` should never pay for
        a library it will not use -- but it also means a build missing the ``provider`` extra looks
        perfectly healthy until the first learner request reaches an agent, and then fails as an
        opaque routing error with nothing pointing at the packaging decision that caused it.
        """
        self._module()

    def count_input_tokens(self, model: str, messages: tuple[Message, ...]) -> int:
        litellm = self._module()
        try:
            counted = litellm.token_counter(
                model=model,
                messages=[{"role": m.role, "content": m.content} for m in messages],
            )
        except Exception:  # noqa: BLE001 - any counter failure must still yield a usable number
            # Falling back to a character estimate keeps the ceiling enforceable when the tokenizer
            # for an unfamiliar model is unavailable. It errs high, so it refuses rather than lets
            # an oversized context through.
            characters = sum(len(m.role) + len(m.content) for m in messages)
            return max(1, characters // 3)
        return int(counted)

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        litellm = self._module()
        try:
            arguments: dict[str, Any] = {
                "model": request.model,
                "messages": [
                    {"role": message.role, "content": message.content}
                    for message in request.messages
                ],
                "max_tokens": request.max_output_tokens,
                "timeout": request.timeout_seconds,
                "api_key": self._api_key,
            }
            if request.single_submission:
                # LiteLLM otherwise owns a retry policy below RAMALS' gateway. Contract A permits
                # one intended external submission after durable IN_FLIGHT and therefore has to
                # disable that hidden layer as well as the gateway's own retry/fallback paths.
                arguments["num_retries"] = 0
            completion = litellm.completion(
                **arguments,
            )
        except Exception as failure:  # noqa: BLE001 - the point of this method is to classify them
            raise self._normalize(failure) from None

        return self._to_response(completion)

    @staticmethod
    def _to_response(completion: Any) -> ProviderResponse:
        try:
            text = completion.choices[0].message.content or ""
            usage = completion.usage
        except (AttributeError, IndexError, TypeError) as malformed:
            raise GatewayError(
                GatewayErrorCode.INVALID_STRUCTURED_OUTPUT,
                "the provider returned a completion without a usable message",
            ) from malformed

        cached = 0
        details = getattr(usage, "prompt_tokens_details", None)
        if details is not None:
            cached = int(getattr(details, "cached_tokens", 0) or 0)

        return ProviderResponse(
            text=text,
            input_tokens=int(getattr(usage, "prompt_tokens", 0) or 0),
            output_tokens=int(getattr(usage, "completion_tokens", 0) or 0),
            cached_input_tokens=cached,
            provider_request_id=LiteLLMProvider._provider_request_id(completion),
            provider_message_id=LiteLLMProvider._provider_message_id(completion),
        )

    @staticmethod
    def _provider_message_id(completion: Any) -> str | None:
        """Returns the provider completion/message identity when LiteLLM exposes one."""
        value = getattr(completion, "id", None)
        return str(value)[:128] if value else None

    @staticmethod
    def _provider_request_id(completion: Any) -> str | None:
        """Reads a provider request identifier for audit only, never for replay/recovery."""
        hidden = getattr(completion, "_hidden_params", None)
        if not isinstance(hidden, dict):
            return None
        headers = hidden.get("additional_headers")
        if not isinstance(headers, dict):
            return None
        normalized = {str(key).lower(): value for key, value in headers.items()}
        value = normalized.get("request-id") or normalized.get("x-request-id")
        return str(value)[:128] if value else None

    def _normalize(self, failure: Exception) -> GatewayError:
        """Maps a provider exception onto the fixed taxonomy.

        Matched by class name rather than by importing LiteLLM's exception classes, so the mapping
        can be asserted in tests that do not have LiteLLM installed -- and so an SDK reorganising
        its exception module does not break the import of this one.
        """
        name = type(failure).__name__
        code = _EXCEPTION_CODES.get(name)
        if code is None:
            code = _classify_by_status(getattr(failure, "status_code", None))
        # The provider's message is dropped deliberately: it routinely echoes the request, which
        # here is a minimized learner context.
        return GatewayError(code, f"provider call failed with {name}")


# LiteLLM's documented exception surface, mapped to our codes. Names, not imports.
_EXCEPTION_CODES: dict[str, GatewayErrorCode] = {
    "Timeout": GatewayErrorCode.PROVIDER_TIMEOUT,
    "APITimeoutError": GatewayErrorCode.PROVIDER_TIMEOUT,
    "RateLimitError": GatewayErrorCode.PROVIDER_RATE_LIMITED,
    "ServiceUnavailableError": GatewayErrorCode.PROVIDER_UNAVAILABLE,
    "InternalServerError": GatewayErrorCode.PROVIDER_UNAVAILABLE,
    "APIConnectionError": GatewayErrorCode.PROVIDER_UNAVAILABLE,
    "APIError": GatewayErrorCode.PROVIDER_UNAVAILABLE,
    "AuthenticationError": GatewayErrorCode.PROVIDER_AUTH_ERROR,
    "PermissionDeniedError": GatewayErrorCode.PROVIDER_AUTH_ERROR,
    "BadRequestError": GatewayErrorCode.PROVIDER_INVALID_REQUEST,
    "UnprocessableEntityError": GatewayErrorCode.PROVIDER_INVALID_REQUEST,
    "ContextWindowExceededError": GatewayErrorCode.TOKEN_CEILING_EXCEEDED,
    "NotFoundError": GatewayErrorCode.ROUTE_NOT_CONFIGURED,
}


def _classify_by_status(status: object) -> GatewayErrorCode:
    """Falls back to HTTP semantics for an exception class we do not recognise.

    An unknown failure defaults to ``PROVIDER_UNAVAILABLE``, which is retryable. That is the
    deliberate choice: the alternative default -- treating the unknown as permanent -- turns a
    provider's new transient error class into a hard outage on the day they ship it.
    """
    if not isinstance(status, int):
        return GatewayErrorCode.PROVIDER_UNAVAILABLE
    if status == 429:
        return GatewayErrorCode.PROVIDER_RATE_LIMITED
    if status in (401, 403):
        return GatewayErrorCode.PROVIDER_AUTH_ERROR
    if status == 408:
        return GatewayErrorCode.PROVIDER_TIMEOUT
    if 400 <= status < 500:
        return GatewayErrorCode.PROVIDER_INVALID_REQUEST
    return GatewayErrorCode.PROVIDER_UNAVAILABLE


def normalize_exception_name(name: str, status_code: object = None) -> GatewayErrorCode:
    """Exposed so the taxonomy can be asserted without a provider installed."""
    return _EXCEPTION_CODES.get(name) or _classify_by_status(status_code)
