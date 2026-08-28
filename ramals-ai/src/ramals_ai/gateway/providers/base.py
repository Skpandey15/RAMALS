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
from enum import StrEnum
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

    def find_executions_by_custom_id(
        self,
        custom_id: str,
        created_after: str,
        created_before: str,
        max_pages: int = 10,
        max_inspections: int = 50,
    ) -> DurableExecutionSearch:
        """Finds every provider execution carrying ``custom_id`` within a creation-time window.

        The recovery path for a lost acknowledgement (M2-ADR-020). Read-only: an implementation must
        never create an execution while searching for one.

        Correlation must be **proven from batch results**, never taken from list metadata, which
        carries no ``custom_id``. An implementation matching on creation time would be guessing,
        and the thing it would guess wrong is whose diagnosis this is.

        Must return ``INCONCLUSIVE`` rather than ``ZERO`` whenever a candidate could not be
        opened or a bound was hit. ``ZERO`` is a claim that the search finished.
        """
        ...

    def get_result(self, provider_execution_id: str, custom_id: str | None = None) -> DurableResult:
        """Retrieves one terminal result, after the submitting process may be long gone.

        ``custom_id`` selects the record within a provider execution that carries several. It is
        optional because a provider whose execution maps one-to-one to a result does not need it;
        it is present because the batch-shaped providers do, and correlating by position rather
        than by the caller's own key is how results get attributed to the wrong request.
        """
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
class DurableExecutionCounts:
    """Per-state request counts, kept at the provider's granularity.

    Five fields rather than the three an OpenAI-shaped batch reports, because ``expired`` and
    ``canceled`` are distinct terminal facts Contract B has to record honestly -- an execution that
    hit the provider's processing deadline is not one that errored, and collapsing them would make
    the reconciliation record say something untrue.
    """

    processing: int = 0
    succeeded: int = 0
    errored: int = 0
    canceled: int = 0
    expired: int = 0


@dataclass(frozen=True)
class DurableSubmission:
    """The acknowledgement. Everything here is needed again after a worker death."""

    provider_execution_id: str
    state: str
    provider_request_id: str | None = None
    custom_id: str | None = None
    """The caller-supplied correlation key, echoed back. The reconciliation handle."""

    created_at: str | None = None
    expires_at: str | None = None
    """When the provider stops processing. Distinct from how long results are retained."""


class DurableSearchOutcome(StrEnum):
    """What an enumeration search actually established.

    Four values rather than three, and the fourth is the important one. A batch that has not ended
    has no results to read, so it cannot be correlated at all -- and reporting that as ZERO would
    assert no orphan exists at the moment one is most likely to be running. INCONCLUSIVE keeps
    "we looked and found nothing" apart from "we could not finish looking" (M2-ADR-020 §2).
    """

    ZERO = "ZERO"
    ONE = "ONE"
    MULTIPLE = "MULTIPLE"
    INCONCLUSIVE = "INCONCLUSIVE"


@dataclass(frozen=True)
class DurableExecutionMatch:
    """One provider execution proven to carry the searched ``custom_id``.

    Proven, not inferred. Correlation comes from reading the batch's results, because batch list
    metadata carries no ``custom_id`` -- so every field here describes an execution that was
    actually opened and inspected.
    """

    provider_execution_id: str
    custom_id: str
    outcome: str
    """``succeeded``, ``errored``, ``canceled`` or ``expired``."""

    input_tokens: int = 0
    output_tokens: int = 0
    cached_input_tokens: int = 0
    created_at: str | None = None
    ended_at: str | None = None
    native_status: str | None = None


@dataclass(frozen=True)
class DurableExecutionSearch:
    """The result of enumerating for one ``custom_id``.

    Carries the accounting as well as the answer: how many batches were listed, how many opened,
    and which bound stopped the search. A caller deciding whether to believe ZERO needs to know
    the search finished, and one deciding whether to retry needs to know why it did not.
    """

    outcome: DurableSearchOutcome
    matches: tuple[DurableExecutionMatch, ...] = ()
    batches_listed: int = 0
    batches_inspected: int = 0
    batches_uninspectable: int = 0
    """In the window but impossible to correlate -- still processing, or results unreadable."""

    pages_fetched: int = 0
    limit_reached: str | None = None
    """Which bound stopped the search, when one did: ``pages``, ``inspections``. None otherwise."""


@dataclass(frozen=True)
class DurableStatus:
    provider_execution_id: str
    state: str
    retry_after_ms: int | None = None
    native_status: str | None = None
    """The provider's own status string, unnormalized. Recorded because a normalized status is a
    lossy summary of it, and the transition ledger is forensic evidence rather than a dashboard."""

    counts: DurableExecutionCounts | None = None
    results_available: bool = False
    created_at: str | None = None
    expires_at: str | None = None
    ended_at: str | None = None
    cancel_initiated_at: str | None = None


@dataclass(frozen=True)
class DurableResult:
    """One correlated record, not a whole batch.

    ``outcome`` is required and ``text`` is optional, in that order deliberately: an expired or
    cancelled record has no text, and a result type that made text mandatory would force an adapter
    to invent an empty string and lose the distinction.
    """

    provider_execution_id: str
    outcome: str
    """One of ``succeeded``, ``errored``, ``canceled``, ``expired``."""

    custom_id: str | None = None
    text: str | None = None
    input_tokens: int = 0
    output_tokens: int = 0
    cached_input_tokens: int = 0
    provider_message_id: str | None = None
    error_code: str | None = None


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
