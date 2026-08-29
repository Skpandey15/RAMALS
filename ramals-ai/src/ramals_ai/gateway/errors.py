"""Normalized failure taxonomy for the model gateway.

Providers disagree about everything: status codes, exception types, whether a rate limit is a 429
or a 503, whether a timeout raises or returns an empty completion. An agent cannot make a sound
decision about a failure it cannot name, so every provider failure is translated here into one of
a fixed set of codes with the handling policy attached (M1-ADR-008 §Fallback).

The two flags on each error are the decision, not a hint:

* ``retryable`` -- the same call, on the same route, may be attempted again.
* ``fallback_eligible`` -- a *different*, approved route may serve the request instead.

They are deliberately independent. An invalid structured output is retryable but not fallback
eligible: repair happens on the governed route, because repair is not an excuse to change models.
A budget refusal is neither, because escalation on failure is precisely the behaviour that turns a
transient provider error into an unbounded bill.
"""

from __future__ import annotations

from enum import StrEnum


class GatewayErrorCode(StrEnum):
    """Stable failure codes. Callers branch on these, never on provider exception types."""

    # Provider-side, transient. The call may succeed unchanged.
    PROVIDER_RATE_LIMITED = "PROVIDER_RATE_LIMITED"
    PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE"
    PROVIDER_TIMEOUT = "PROVIDER_TIMEOUT"

    # Provider-side, permanent for this request. Retrying changes nothing.
    PROVIDER_AUTH_ERROR = "PROVIDER_AUTH_ERROR"
    PROVIDER_INVALID_REQUEST = "PROVIDER_INVALID_REQUEST"

    # Our own governance refused the call. None of these ever reach a provider.
    TOKEN_CEILING_EXCEEDED = "TOKEN_CEILING_EXCEEDED"  # noqa: S105 - a failure code, not a secret
    COST_CEILING_EXCEEDED = "COST_CEILING_EXCEEDED"
    DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"
    ROUTE_NOT_CONFIGURED = "ROUTE_NOT_CONFIGURED"

    # The model answered, but not in a shape the caller can use.
    INVALID_STRUCTURED_OUTPUT = "INVALID_STRUCTURED_OUTPUT"

    # The route asked for durable recoverable execution (Contract B) and the adapter cannot honour
    # it. Refused before any provider is contacted. Never retryable and never fallback eligible --
    # see the policy note below, which is the whole point of the code.
    CONTRACT_B_UNSUPPORTED = "CONTRACT_B_UNSUPPORTED"


# Handling policy per code, from M1-ADR-008. Kept as data next to the codes so the policy is
# readable in one place rather than inferred from scattered `if` statements.
_POLICY: dict[GatewayErrorCode, tuple[bool, bool]] = {
    # code: (retryable, fallback_eligible)
    GatewayErrorCode.PROVIDER_RATE_LIMITED: (True, True),
    GatewayErrorCode.PROVIDER_UNAVAILABLE: (True, True),
    # A timeout is cancelled rather than retried: the deadline that was too short for the first
    # attempt is shorter now. Fallback stays open because an approved route may be faster, but the
    # gateway still checks the remaining deadline before taking it.
    GatewayErrorCode.PROVIDER_TIMEOUT: (False, True),
    # Retrying a misconfiguration turns one alert into a flood and delays the fix.
    GatewayErrorCode.PROVIDER_AUTH_ERROR: (False, False),
    GatewayErrorCode.PROVIDER_INVALID_REQUEST: (False, False),
    # Governance refusals. Never retried, never escalated -- that is the whole point of a ceiling.
    GatewayErrorCode.TOKEN_CEILING_EXCEEDED: (False, False),
    GatewayErrorCode.COST_CEILING_EXCEEDED: (False, False),
    GatewayErrorCode.DEADLINE_EXCEEDED: (False, False),
    GatewayErrorCode.ROUTE_NOT_CONFIGURED: (False, False),
    # Repair is bounded and happens on the same route.
    GatewayErrorCode.INVALID_STRUCTURED_OUTPUT: (True, False),
    # (False, False) is load-bearing rather than conservative. `fallback_eligible = True` would let
    # a Contract B request be served by a different route, and the only routes that exist are
    # Contract A ones -- which is precisely the silent degradation M2-ADR-016 forbids. A durable
    # execution that quietly completed as a synchronous single submission would leave a row that
    # looks recoverable and is not. `retryable = True` would be no better: the adapter's inability
    # is a property of its published contract, not a transient fault, so a second attempt asks the
    # same question and gets the same answer.
    GatewayErrorCode.CONTRACT_B_UNSUPPORTED: (False, False),
}


class GatewayError(Exception):
    """A provider or governance failure, named and classified.

    Carries no provider payload. Provider error bodies routinely echo the request, which here would
    mean echoing a minimized learner context into an exception message and from there into a log.
    """

    def __init__(
        self, code: GatewayErrorCode, detail: str, retry_after_ms: int | None = None
    ) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail
        self.retryable, self.fallback_eligible = _POLICY[code]
        self.retry_after_ms = retry_after_ms
        """How long the provider asked the caller to wait, when it said so.

        Optional and almost always ``None``. It carries a ``Retry-After`` from a rate limit, which
        is the one piece of a provider's error response worth keeping: the provider knows when it
        will serve again and the caller does not. Everything else about the response is still
        dropped, because provider error bodies routinely echo the request.
        """

    @property
    def is_governance_refusal(self) -> bool:
        """True when the gateway refused before dispatch, rather than the provider failing.

        Worth distinguishing in metrics and alerts: a spike in refusals means the callers are
        sending work the platform has decided it will not pay for, which is a different problem
        from a provider having a bad afternoon.
        """
        return self.code in {
            GatewayErrorCode.TOKEN_CEILING_EXCEEDED,
            GatewayErrorCode.COST_CEILING_EXCEEDED,
            GatewayErrorCode.DEADLINE_EXCEEDED,
            GatewayErrorCode.ROUTE_NOT_CONFIGURED,
        }


def policy_for(code: GatewayErrorCode) -> tuple[bool, bool]:
    """Exposed so tests can assert the table directly rather than by constructing every error."""
    return _POLICY[code]
