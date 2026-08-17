"""Model routes as versioned configuration (Doc 04 §2–§3, M1-ADR-008).

A route is data: which model, which prompt version, what it may spend, how long it may take. Adding
one is a reviewed configuration change, not a new branch in the gateway. That is what makes
per-route budgets enforceable at all -- a budget needs something to be a budget *of*.

The table is validated at import time. A route whose fallback is more expensive than itself is a
configuration that would let a failure escalate cost, so it cannot be expressed: the module refuses
to load rather than allowing the invariant to hold "by review".
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from decimal import Decimal

from ramals_ai.config.settings import ModelRoute

# Bump when any route's model, prompt, ceiling or target changes. Recorded with every call so a
# result can be traced back to the configuration that produced it.
ROUTE_TABLE_VERSION = "ROUTE_TABLE_V1"


@dataclass(frozen=True)
class RouteConfig:
    """One governed route. Immutable: rollback replaces the pointer, never edits the value."""

    route: ModelRoute
    model: str
    """Provider model identifier, pinned to a dated version rather than a moving alias.

    An alias like ``gpt-4o`` silently changes what produced an answer; a dated identifier makes a
    model change a configuration change with a review and a rollback path.
    """

    prompt_version: str
    max_input_tokens: int
    max_output_tokens: int

    # Doc 04 §2. The soft target drives optimization and alerting; only the hard ceiling refuses.
    soft_target_cost_usd: Decimal
    hard_cost_ceiling_usd: Decimal
    completion_target_p95_ms: int

    # Used to project cost before dispatch. Provider list prices, versioned with the table.
    input_cost_per_1k_usd: Decimal
    output_cost_per_1k_usd: Decimal

    fallback_route: ModelRoute | None = None
    """An approved, semantically equivalent route (Doc 04 §4).

    ``None`` for every route at V1: Doc 04 permits fallback but approves no specific pairing, and
    inventing one here would be a routing decision made by an implementer rather than by review.
    """

    @property
    def is_deterministic_fake(self) -> bool:
        return self.route is ModelRoute.CI_FAKE


def _route(
    route: ModelRoute,
    *,
    model: str,
    prompt_version: str,
    max_input_tokens: int,
    max_output_tokens: int,
    soft_target: str,
    hard_ceiling: str,
    p95_ms: int,
    input_per_1k: str,
    output_per_1k: str,
) -> RouteConfig:
    return RouteConfig(
        route=route,
        model=model,
        prompt_version=prompt_version,
        max_input_tokens=max_input_tokens,
        max_output_tokens=max_output_tokens,
        soft_target_cost_usd=Decimal(soft_target),
        hard_cost_ceiling_usd=Decimal(hard_ceiling),
        completion_target_p95_ms=p95_ms,
        input_cost_per_1k_usd=Decimal(input_per_1k),
        output_cost_per_1k_usd=Decimal(output_per_1k),
    )


# Doc 04 §2 and §3 verbatim. These numbers are versioned engineering guardrails, not external SLAs;
# TokenAndCostCeilingTests asserts them against the document so a quiet edit fails the build.
_V1_ROUTES: tuple[RouteConfig, ...] = (
    _route(
        ModelRoute.TUTOR_DEFAULT,
        model="claude-sonnet-5",
        prompt_version="TUTOR_PROMPT_V1",
        max_input_tokens=12000,
        max_output_tokens=1200,
        soft_target="0.020",
        hard_ceiling="0.050",
        p95_ms=8000,
        input_per_1k="0.003",
        output_per_1k="0.015",
    ),
    _route(
        ModelRoute.DIAGNOSTIC_DEFAULT,
        model="claude-sonnet-5",
        prompt_version="DIAGNOSTIC_PROMPT_V1",
        max_input_tokens=8000,
        max_output_tokens=700,
        soft_target="0.015",
        hard_ceiling="0.040",
        p95_ms=6000,
        input_per_1k="0.003",
        output_per_1k="0.015",
    ),
    _route(
        ModelRoute.ASSESSMENT_DEFAULT,
        model="claude-sonnet-5",
        prompt_version="ASSESSMENT_PROMPT_V1",
        max_input_tokens=12000,
        max_output_tokens=1400,
        soft_target="0.030",
        hard_ceiling="0.060",
        p95_ms=10000,
        input_per_1k="0.003",
        output_per_1k="0.015",
    ),
    _route(
        ModelRoute.ADAPTATION_DEFAULT,
        model="claude-sonnet-5",
        prompt_version="ADAPTATION_PROMPT_V1",
        max_input_tokens=8000,
        max_output_tokens=700,
        soft_target="0.015",
        hard_ceiling="0.040",
        p95_ms=6000,
        input_per_1k="0.003",
        output_per_1k="0.015",
    ),
    # Doc 04 §2: zero cost, deterministic, local. Not a mock bolted onto the tests -- a real route,
    # so CI exercises the same gateway path a provider call takes.
    _route(
        ModelRoute.CI_FAKE,
        model="ci-fake-deterministic-v1",
        prompt_version="CI_FAKE_PROMPT_V1",
        max_input_tokens=12000,
        max_output_tokens=1400,
        soft_target="0.000",
        hard_ceiling="0.000",
        p95_ms=1000,
        input_per_1k="0.000",
        output_per_1k="0.000",
    ),
)


class RouteTableError(RuntimeError):
    """The route table is not a configuration the gateway is willing to serve."""


def _validate(routes: dict[ModelRoute, RouteConfig]) -> None:
    """Rejects tables that would let a failure cost more than a success.

    Checked here rather than in the gateway because a gateway check protects one call path, while
    this one makes the bad configuration unrepresentable.
    """
    for config in routes.values():
        if config.soft_target_cost_usd > config.hard_cost_ceiling_usd:
            raise RouteTableError(
                f"{config.route}: soft target {config.soft_target_cost_usd} exceeds hard ceiling "
                f"{config.hard_cost_ceiling_usd}"
            )
        if config.fallback_route is None:
            continue
        if config.fallback_route not in routes:
            raise RouteTableError(
                f"{config.route}: fallback {config.fallback_route} is not a route"
            )
        if config.fallback_route is config.route:
            raise RouteTableError(f"{config.route}: fallback points at itself")
        fallback = routes[config.fallback_route]
        if fallback.hard_cost_ceiling_usd > config.hard_cost_ceiling_usd:
            raise RouteTableError(
                f"{config.route}: fallback {config.fallback_route} has a higher cost ceiling "
                f"({fallback.hard_cost_ceiling_usd} > {config.hard_cost_ceiling_usd}); a failure "
                "must never escalate to a costlier route (M1-ADR-008)"
            )


@dataclass(frozen=True)
class RouteRegistry:
    """The active route configuration, plus the version pointer it was resolved from.

    Rollback produces a *new* registry. Nothing already handed out changes, which is what keeps
    recorded proposal metadata truthful after a route is rolled back.
    """

    version: str
    routes: dict[ModelRoute, RouteConfig]

    def __post_init__(self) -> None:
        _validate(self.routes)

    def resolve(self, route: ModelRoute) -> RouteConfig:
        try:
            return self.routes[route]
        except KeyError:
            from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode

            raise GatewayError(
                GatewayErrorCode.ROUTE_NOT_CONFIGURED,
                f"no configuration for route '{route}'",
            ) from None

    def with_route(self, config: RouteConfig) -> RouteRegistry:
        """Returns a registry with one route replaced. The receiver is untouched."""
        updated = dict(self.routes)
        updated[config.route] = config
        return RouteRegistry(version=self.version, routes=updated)

    def rolled_back(
        self,
        route: ModelRoute,
        *,
        model: str | None = None,
        prompt_version: str | None = None,
    ) -> RouteRegistry:
        """Points a route at a previously approved model and/or prompt.

        The two roll back independently (M1-ADR-008): a prompt regression should not force a model
        rollback, and coupling them would make the cheap remedy carry the expensive one's risk.
        """
        if model is None and prompt_version is None:
            raise ValueError("a rollback must change the model, the prompt version, or both")
        current = self.resolve(route)
        return self.with_route(
            replace(
                current,
                model=current.model if model is None else model,
                prompt_version=(
                    current.prompt_version if prompt_version is None else prompt_version
                ),
            )
        )


def default_registry() -> RouteRegistry:
    """The V1 table. A fresh dict each call so no caller can mutate the shared default."""
    return RouteRegistry(
        version=ROUTE_TABLE_VERSION,
        routes={config.route: config for config in _V1_ROUTES},
    )
