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


# -- durable recoverable execution (Contract B) -------------------------------------------------
#
# Everything below is the Contract B boundary. NOTHING IMPLEMENTS IT YET, deliberately: this
# increment adds the capability declaration and the fail-closed refusal, and no adapter gains a
# durable execution path until a later change.
#
# The boundary exists before the implementation for one reason. M2-ADR-016 requires that an adapter
# which cannot honour Contract B *fails* rather than falling through to a synchronous call, and a
# rule with nowhere to live is a rule that gets skipped the first time it is inconvenient. Declaring
# it now means the first real adapter is written against a contract that already refuses.


@dataclass(frozen=True)
class DurableExecutionCapability:
    """What a provider path can actually prove about durable recoverable execution.

    One field per mandatory row of the M2-ADR-016 capability gate, because "supports Contract B" is
    not a single fact. A path can offer result retrieval and no replay-safe admission -- which is
    exactly what Anthropic's Message Batches API offers -- and a boolean would flatten that into a
    claim the provider contract does not support.
    """

    supported: bool
    """The only field the admission guard reads. False unless every mandatory row below holds."""

    replay_safe_admission: bool = False
    durable_execution_id: bool = False
    status_lookup: bool = False
    result_retrieval: bool = False
    cancellation: bool = False
    result_retention_days: int = 0
    reason: str = ""
    """Why the path is unsupported, in operator-readable terms. Empty when supported."""

    @classmethod
    def unsupported(cls, reason: str, **observed: bool | int) -> DurableExecutionCapability:
        """Declares no Contract B support, recording whatever the path *does* offer.

        The observed rows are kept rather than zeroed so a capability report stays useful: knowing a
        path has result retrieval but no replay-safe admission is what tells a reader which row to
        go and fix.
        """
        return cls(supported=False, reason=reason, **observed)  # type: ignore[arg-type]


DURABLE_EXECUTION_UNSUPPORTED = DurableExecutionCapability.unsupported(
    "this adapter declares no durable recoverable execution capability"
)
"""The default for any adapter that does not declare one.

Fail-closed by construction: an adapter opts *in* to Contract B by declaring a capability, and
cannot acquire support by omission. A future adapter added by someone who has never read
M2-ADR-016 is unsupported, which is the correct answer.
"""


@runtime_checkable
class DurableProviderAdapter(Protocol):
    """The surface a provider must present to serve Contract B.

    Separate from ``ProviderAdapter`` rather than an extension of it: the two describe different
    execution contracts, and an adapter may serve Contract A perfectly while being unable to serve
    Contract B at all. That is the normal case today -- it is true of every adapter in this service.
    """

    name: str

    def durable_capability(self) -> DurableExecutionCapability:
        """What this adapter can prove. Must not contact the provider, and must not raise.

        Called during admission, before any decision to spend money, and read by operational
        reporting. A capability probe that performs I/O would make a refusal cost a network round
        trip and could itself fail, turning "unsupported" into "unknown".
        """
        ...

    def submit(self, request: DurableSubmissionRequest) -> DurableSubmission:
        """Submits under the caller's idempotency key, returning the provider execution handle."""
        ...

    def get_status(self, provider_execution_id: str) -> DurableStatus:
        """Reads authoritative status for an execution this process may not have started."""
        ...

    def get_result(self, provider_execution_id: str) -> DurableResult:
        """Retrieves the terminal result, after the submitting process may be long gone."""
        ...


@dataclass(frozen=True)
class DurableSubmissionRequest:
    """One durable submission. The idempotency key is server-derived, never caller-supplied."""

    request_id: str
    idempotency_key: str
    request_digest: str
    model: str
    messages: tuple[Message, ...]
    max_output_tokens: int


@dataclass(frozen=True)
class DurableSubmission:
    provider_execution_id: str
    state: str
    provider_request_id: str | None = None


@dataclass(frozen=True)
class DurableStatus:
    provider_execution_id: str
    state: str
    retry_after_ms: int | None = None


@dataclass(frozen=True)
class DurableResult:
    provider_execution_id: str
    text: str
    input_tokens: int
    output_tokens: int
    cached_input_tokens: int = 0


def resolve_durable_capability(adapter: object) -> DurableExecutionCapability:
    """The capability of any adapter, declared or not.

    Deliberately duck-typed rather than an ``isinstance`` check against the Protocol. A
    ``runtime_checkable`` Protocol tests only that the method *names* exist, so an adapter with a
    stub ``submit`` would pass the check while being unable to honour any of it. What decides
    Contract B support is the adapter's own declaration, and an adapter that declares nothing is
    unsupported.
    """
    probe = getattr(adapter, "durable_capability", None)
    if probe is None or not callable(probe):
        return DURABLE_EXECUTION_UNSUPPORTED
    capability = probe()
    if not isinstance(capability, DurableExecutionCapability):
        return DURABLE_EXECUTION_UNSUPPORTED
    return capability
