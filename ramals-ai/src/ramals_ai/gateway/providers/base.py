"""The provider boundary.

Everything above this line speaks in these types. Only the modules in this package may know that a
particular provider exists, and only the LiteLLM adapter may import a provider SDK -- enforced by
``test_provider_isolation.py`` rather than by convention, because a boundary nothing checks erodes
at the first inconvenient deadline.

An adapter has exactly two jobs: make the call, and translate its failures into ``GatewayError``.
Budgets, retries, fallback and metadata are the gateway's business, not the adapter's, so a second
provider cannot arrive with its own private opinion about what a rate limit means.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable


@dataclass(frozen=True)
class Message:
    """One turn of the conversation sent to the model."""

    role: str
    content: str


@dataclass(frozen=True)
class ProviderRequest:
    """A call the gateway has already decided is within budget."""

    model: str
    messages: tuple[Message, ...]
    max_output_tokens: int
    timeout_seconds: float
    """Derived from the caller's remaining deadline, never a fixed per-hop value."""

    request_id: str | None = None
    """Stable RAMALS request identity for correlation; not a provider idempotency claim."""

    single_submission: bool = False
    """When true, the adapter must disable every controllable provider-SDK retry."""


@dataclass(frozen=True)
class ProviderResponse:
    """What a provider returned, in the only shape the gateway reads."""

    text: str
    input_tokens: int
    output_tokens: int
    cached_input_tokens: int = 0
    provider_request_id: str | None = None
    provider_message_id: str | None = None


@runtime_checkable
class ProviderAdapter(Protocol):
    """The complete surface a provider is allowed to present to the rest of the service."""

    name: str

    def count_input_tokens(self, model: str, messages: tuple[Message, ...]) -> int:
        """Tokens the request will consume, counted before dispatch.

        On the adapter because tokenization is provider-specific, and the input ceiling must be
        enforced against the count the provider will actually bill for -- not an approximation the
        gateway invented.
        """
        ...

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        """Performs the call, or raises ``GatewayError`` with a normalized code."""
        ...
