"""Budget enforcement: deadlines, token ceilings and cost ceilings.

Every check here runs *before* dispatch. That distinction is the whole design: monitoring tells you
what you spent, a ceiling decides it. A cost check performed on the response has already bought the
thing it was meant to prevent.

Deadlines are absolute instants, never per-hop timeouts (M1-ADR-001). Hops compose; timeouts do
not, so three well-behaved two-second timeouts overrun a five-second deadline while every individual
component believes it obeyed its budget.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.routes import RouteConfig

Clock = Callable[[], float]
"""A monotonic clock in seconds. Injected so tests can drive time rather than sleep through it."""

_COST_QUANTUM = Decimal("0.000001")
"""Six decimal places, matching the contract's ``estimatedCostUsd`` pattern."""


@dataclass(frozen=True)
class Deadline:
    """An absolute instant by which the caller's work must be finished.

    Constructed from the caller's ``deadlineMs`` at the moment the request is accepted, so every
    retry and fallback spends the *same* budget. A retry that starts its own clock is how a bounded
    retry quietly becomes an unbounded one.
    """

    expires_at: float
    clock: Clock = time.monotonic

    @classmethod
    def in_ms(cls, milliseconds: int, *, clock: Clock = time.monotonic) -> Deadline:
        if milliseconds <= 0:
            raise ValueError("a deadline must be a positive number of milliseconds")
        return cls(expires_at=clock() + milliseconds / 1000.0, clock=clock)

    def remaining_ms(self) -> float:
        return max(0.0, (self.expires_at - self.clock()) * 1000.0)

    @property
    def expired(self) -> bool:
        return self.clock() >= self.expires_at

    def permits(self, required_ms: float) -> bool:
        """Whether a further attempt of the given expected duration can still complete in time.

        Used to decide retry and fallback eligibility. Asking this before starting work is what
        stops the gateway beginning something it cannot finish -- which would burn the remaining
        budget and still fail, having also paid for the tokens.
        """
        return self.remaining_ms() >= required_ms

    def raise_if_expired(self) -> None:
        if self.expired:
            raise GatewayError(
                GatewayErrorCode.DEADLINE_EXCEEDED,
                "the caller's deadline had already passed when the call was attempted",
            )


def project_cost_usd(config: RouteConfig, *, input_tokens: int, max_output_tokens: int) -> Decimal:
    """Worst-case cost of a call, priced before it is made.

    Deliberately worst case: output length is not known in advance, so the ceiling is enforced
    against the most the call *could* cost. Pricing the hoped-for output would make the ceiling an
    estimate, and a ceiling that is sometimes wrong in the expensive direction is not a ceiling.
    """
    input_cost = (Decimal(input_tokens) / 1000) * config.input_cost_per_1k_usd
    output_cost = (Decimal(max_output_tokens) / 1000) * config.output_cost_per_1k_usd
    return (input_cost + output_cost).quantize(_COST_QUANTUM, rounding=ROUND_HALF_UP)


def actual_cost_usd(config: RouteConfig, *, input_tokens: int, output_tokens: int) -> Decimal:
    """What the call did cost, from the provider's reported usage."""
    input_cost = (Decimal(input_tokens) / 1000) * config.input_cost_per_1k_usd
    output_cost = (Decimal(output_tokens) / 1000) * config.output_cost_per_1k_usd
    return (input_cost + output_cost).quantize(_COST_QUANTUM, rounding=ROUND_HALF_UP)


def resolve_output_ceiling(config: RouteConfig, requested: int | None) -> int:
    """The stricter of the caller's request and the route's ceiling (Doc 04 §7).

    Restated in code because the route config is the nearer object at the call site, and taking it
    unconditionally would let a caller's tighter constraint be silently widened.
    """
    if requested is None:
        return config.max_output_tokens
    if requested <= 0:
        raise GatewayError(
            GatewayErrorCode.PROVIDER_INVALID_REQUEST,
            "maxOutputTokens must be positive",
        )
    return min(requested, config.max_output_tokens)


def enforce_input_ceiling(config: RouteConfig, input_tokens: int) -> None:
    """Refuses oversized input rather than truncating it.

    The input is a minimized context Spring assembled *after* authorizing the learner. Dropping part
    of it to fit produces a confident answer from a subset nobody chose, and leaves no trace that it
    happened. Failing is loud and points at the real problem, which is context assembly.
    """
    if input_tokens > config.max_input_tokens:
        raise GatewayError(
            GatewayErrorCode.TOKEN_CEILING_EXCEEDED,
            f"{input_tokens} input tokens exceeds the {config.route} ceiling of "
            f"{config.max_input_tokens}; the context is not truncated to fit",
        )


def enforce_cost_ceiling(config: RouteConfig, projected_usd: Decimal) -> None:
    """Refuses a call whose worst case exceeds the route's hard ceiling.

    No fallback is attempted, here or by the caller: escalating on a budget refusal is the exact
    behaviour the ceiling exists to prevent (M1-ADR-008).
    """
    if projected_usd > config.hard_cost_ceiling_usd:
        raise GatewayError(
            GatewayErrorCode.COST_CEILING_EXCEEDED,
            f"projected {projected_usd} USD exceeds the {config.route} hard ceiling of "
            f"{config.hard_cost_ceiling_usd} USD",
        )
