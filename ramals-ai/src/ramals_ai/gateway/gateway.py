"""The governed model gateway.

One place decides what a model call may cost, how long it may take, and what happens when it fails.
Agents ask for a route and a deadline; they never see a provider, a retry, or a price.

The rules enforced here come from M1-ADR-008, and the two that matter most are negative:

* **A failure never escalates to a costlier route.** That is what stops a bad provider afternoon
  from becoming a bill nobody approved.
* **Retries and fallbacks spend the caller's deadline, not their own.** A bounded retry that
  outlives the deadline is a deadline violation wearing a different name.

The result records the route that *actually* served the request. After a fallback, reporting the
requested route would be worse than reporting nothing, because it reads as trustworthy.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable
from dataclasses import dataclass
from decimal import Decimal

from opentelemetry import metrics

from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import InteractionClass
from ramals_ai.gateway import budget
from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.providers.base import (
    Message,
    ProviderAdapter,
    ProviderRequest,
    ProviderResponse,
)
from ramals_ai.gateway.routes import RouteConfig, RouteRegistry, default_registry
from ramals_ai.telemetry.logging import business_event

logger = logging.getLogger(__name__)

_meter = metrics.get_meter("ramals-ai")

gateway_calls = _meter.create_counter(
    "ramals.ai.gateway.calls",
    description="Model gateway calls, by route and outcome",
)
gateway_failures = _meter.create_counter(
    "ramals.ai.gateway.failures",
    description="Model gateway failures, by normalized error code",
)
gateway_refusals = _meter.create_counter(
    "ramals.ai.gateway.refusals",
    description="Calls refused by a budget ceiling before any provider was contacted",
)
gateway_fallbacks = _meter.create_counter(
    "ramals.ai.gateway.fallbacks",
    description="Calls served by an approved fallback route rather than the requested one",
)
gateway_latency = _meter.create_histogram(
    "ramals.ai.latency",
    unit="ms",
    description="Completed model-call latency, by interaction class and effective route",
)
gateway_cost = _meter.create_histogram(
    "ramals.ai.cost",
    unit="USD",
    description="Actual model-call cost, by interaction class and effective route",
)

MAX_ATTEMPTS_PER_ROUTE = 2
"""One retry, not a storm. Doc 04 says "bounded"; the deadline does the rest of the bounding."""

_RETRY_BACKOFF_MS = 200.0


@dataclass(frozen=True)
class GatewayResult:
    """What a call cost and what produced it.

    A value, not a view: rolling a route back afterwards cannot change what this says. That is the
    mechanism behind "rollback never rewrites recorded proposal metadata" -- there is nothing to
    rewrite, because nothing points back at the registry.

    ``latency_ms`` is the elapsed time for this governed model-call operation, including any
    internal retry or fallback handling. It is not the end-to-end HTTP request or agent latency;
    ``AgentState`` sums this value across the model calls in one graph run.
    """

    text: str
    route: ModelRoute
    """The route that served the request. After a fallback this is *not* the requested route."""

    requested_route: ModelRoute
    interaction_class: InteractionClass
    model: str
    prompt_version: str
    route_table_version: str

    input_tokens: int
    cached_input_tokens: int
    output_tokens: int
    estimated_cost_usd: Decimal
    latency_ms: int

    attempts: int
    fell_back: bool

    @property
    def cost_string(self) -> str:
        """Six decimal places, matching the contract's ``estimatedCostUsd`` pattern."""
        return f"{self.estimated_cost_usd:.6f}"


class LLMGateway:
    """The only component in the service that knows a model provider exists."""

    def __init__(
        self,
        adapter: ProviderAdapter,
        *,
        registry: RouteRegistry | None = None,
        clock: budget.Clock = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._adapter = adapter
        self._registry = registry if registry is not None else default_registry()
        self._clock = clock
        self._sleep = sleep

    @property
    def registry(self) -> RouteRegistry:
        return self._registry

    def complete(
        self,
        *,
        route: ModelRoute,
        messages: tuple[Message, ...],
        deadline: budget.Deadline,
        max_output_tokens: int | None = None,
        interaction_class: InteractionClass = InteractionClass.INTERACTIVE_AI,
        request_cost_budget_usd: Decimal | None = None,
        request_cost_spent_usd: Decimal = Decimal("0.000000"),
    ) -> GatewayResult:
        """Runs one governed model call, retrying and falling back only where policy allows.

        When supplied, ``request_cost_budget_usd`` and ``request_cost_spent_usd`` describe the
        cumulative request budget before this call. Each attempted route is checked before provider
        dispatch; callers still record actual usage after a successful response.
        """
        started = self._clock()
        requested = route
        config = self._registry.resolve(route)
        attempts = 0

        while True:
            try:
                response, attempts_used = self._attempt_route(
                    config,
                    messages,
                    deadline,
                    max_output_tokens,
                    request_cost_budget_usd,
                    request_cost_spent_usd,
                )
            except GatewayError as failure:
                attempts += getattr(failure, "attempts_used", 1)
                self._record_failure(config, failure)

                fallback = self._eligible_fallback(config, failure, deadline)
                if fallback is None:
                    raise

                logger.warning(
                    "falling back to an approved route",
                    extra={
                        "operation": "gateway.fallback",
                        "requestedRoute": config.route.value,
                        "fallbackRoute": fallback.route.value,
                        "errorCode": failure.code.value,
                    },
                )
                gateway_fallbacks.add(1, {"from": config.route.value, "to": fallback.route.value})
                config = fallback
                continue

            attempts += attempts_used
            break

        cost = budget.actual_cost_usd(
            config,
            input_tokens=response.input_tokens,
            output_tokens=response.output_tokens,
        )
        result = GatewayResult(
            text=response.text,
            route=config.route,
            requested_route=requested,
            interaction_class=interaction_class,
            model=config.model,
            prompt_version=config.prompt_version,
            route_table_version=self._registry.version,
            input_tokens=response.input_tokens,
            cached_input_tokens=response.cached_input_tokens,
            output_tokens=response.output_tokens,
            estimated_cost_usd=cost,
            latency_ms=int((self._clock() - started) * 1000),
            attempts=attempts,
            fell_back=config.route is not requested,
        )
        self._record_success(result)
        return result

    # -- one route, with its bounded retries ------------------------------------------------------

    def _attempt_route(
        self,
        config: RouteConfig,
        messages: tuple[Message, ...],
        deadline: budget.Deadline,
        max_output_tokens: int | None,
        request_cost_budget_usd: Decimal | None,
        request_cost_spent_usd: Decimal,
    ) -> tuple[ProviderResponse, int]:
        """Enforces this route's budgets, then calls it, retrying within the caller's deadline.

        ``config`` is resolved again for every fallback route, so the request-level check governs
        the route that is actually about to contact a provider rather than only the requested route.
        """
        deadline.raise_if_expired()

        output_ceiling = budget.resolve_output_ceiling(config, max_output_tokens)
        input_tokens = self._adapter.count_input_tokens(config.model, messages)

        try:
            budget.enforce_input_ceiling(config, input_tokens)
            projected = budget.project_cost_usd(
                config, input_tokens=input_tokens, max_output_tokens=output_ceiling
            )
            budget.enforce_cost_ceiling(config, projected)
            budget.enforce_request_cost_ceiling(
                config,
                projected,
                request_budget_usd=request_cost_budget_usd,
                cost_spent_usd=request_cost_spent_usd,
            )
        except GatewayError as refusal:
            gateway_refusals.add(1, {"route": config.route.value, "code": refusal.code.value})
            logger.warning(
                "refused a call before dispatch",
                extra={
                    "operation": "gateway.refuse",
                    "route": config.route.value,
                    "errorCode": refusal.code.value,
                    "inputTokens": input_tokens,
                },
            )
            raise

        attempt = 0
        while True:
            attempt += 1
            deadline.raise_if_expired()
            request = ProviderRequest(
                model=config.model,
                messages=messages,
                max_output_tokens=output_ceiling,
                # The provider gets what is left of the caller's budget, not a fixed timeout.
                timeout_seconds=deadline.remaining_ms() / 1000.0,
            )
            try:
                return self._adapter.complete(request), attempt
            except GatewayError as failure:
                if not self._may_retry(failure, attempt, deadline, config):
                    failure.attempts_used = attempt  # type: ignore[attr-defined]
                    raise
                logger.info(
                    "retrying a transient provider failure",
                    extra={
                        "operation": "gateway.retry",
                        "route": config.route.value,
                        "errorCode": failure.code.value,
                        "attempt": attempt,
                    },
                )
                self._sleep(_RETRY_BACKOFF_MS / 1000.0)

    def _may_retry(
        self,
        failure: GatewayError,
        attempt: int,
        deadline: budget.Deadline,
        config: RouteConfig,
    ) -> bool:
        if not failure.retryable or attempt >= MAX_ATTEMPTS_PER_ROUTE:
            return False
        # A retry must be able to *finish*, not merely to start. Beginning work that the deadline
        # cannot accommodate spends the remaining budget and still fails -- having also paid for
        # whatever tokens the attempt consumed.
        needed = _RETRY_BACKOFF_MS + config.completion_target_p95_ms
        return deadline.permits(needed)

    # -- fallback eligibility ---------------------------------------------------------------------

    def _eligible_fallback(
        self,
        config: RouteConfig,
        failure: GatewayError,
        deadline: budget.Deadline,
    ) -> RouteConfig | None:
        """The single place that decides whether a different route may serve this request.

        Returns ``None`` far more often than not, and every one of those refusals is deliberate.
        """
        if not failure.fallback_eligible:
            return None
        if config.fallback_route is None:
            return None

        candidate = self._registry.resolve(config.fallback_route)

        # Belt and braces. The route table already refuses to load a costlier fallback, but this is
        # the invariant that matters most, and it costs one comparison to also enforce it here.
        if candidate.hard_cost_ceiling_usd > config.hard_cost_ceiling_usd:
            logger.error(
                "refusing a fallback that would raise the cost ceiling",
                extra={
                    "operation": "gateway.fallback.refused",
                    "route": config.route.value,
                    "fallbackRoute": candidate.route.value,
                    "errorCode": "FALLBACK_WOULD_ESCALATE_COST",
                },
            )
            return None

        # Doc 04 §4: on timeout, fall back only if the deadline still permits a *complete* attempt.
        if not deadline.permits(candidate.completion_target_p95_ms):
            return None

        return candidate

    # -- telemetry --------------------------------------------------------------------------------

    def _record_success(self, result: GatewayResult) -> None:
        gateway_calls.add(1, {"route": result.route.value, "outcome": "success"})
        attributes = {
            "interaction_class": result.interaction_class.value,
            "route": result.route.value,
        }
        gateway_latency.record(result.latency_ms, attributes)
        gateway_cost.record(float(result.estimated_cost_usd), attributes)
        business_event(
            logger,
            level=logging.INFO,
            operation="gateway.complete",
            message="model call completed",
            fields={
                "route": result.route.value,
                "requestedRoute": result.requested_route.value,
                "promptVersion": result.prompt_version,
                "routeTableVersion": result.route_table_version,
                "inputTokens": result.input_tokens,
                "outputTokens": result.output_tokens,
                "estimatedCostUsd": result.cost_string,
                "latencyMs": result.latency_ms,
                "attempts": result.attempts,
                "fellBack": result.fell_back,
                "outcome": "SUCCESS",
            },
        )

    def _record_failure(self, config: RouteConfig, failure: GatewayError) -> None:
        gateway_calls.add(1, {"route": config.route.value, "outcome": "failure"})
        gateway_failures.add(1, {"route": config.route.value, "code": failure.code.value})
        business_event(
            logger,
            level=logging.WARNING,
            operation="gateway.failed",
            message="governed model call failed",
            fields={
                "route": config.route.value,
                "errorCode": failure.code.value,
                "outcome": "FAILURE",
                "retryable": failure.retryable,
            },
        )


def build_gateway(settings_route: ModelRoute, adapter: ProviderAdapter) -> LLMGateway:
    """Convenience for wiring; the route argument exists to fail early on an unknown route."""
    gateway = LLMGateway(adapter)
    gateway.registry.resolve(settings_route)
    return gateway


__all__ = [
    "GatewayError",
    "GatewayErrorCode",
    "GatewayResult",
    "LLMGateway",
    "MAX_ATTEMPTS_PER_ROUTE",
    "build_gateway",
    "gateway_cost",
    "gateway_latency",
]
