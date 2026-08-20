"""Hard per-route budgets must be enforceable, and the numbers must be Doc 04's.

Two separate claims, both required by M1-T05:

* the route table says what the document says -- a quiet edit to a ceiling is a governance change
  wearing the clothes of a refactor;
* the ceilings actually refuse, before anything is dispatched.
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from ramals_ai.config.settings import ModelRoute
from ramals_ai.gateway import budget
from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.routes import (
    ROUTE_TABLE_VERSION,
    RouteRegistry,
    RouteTableError,
    default_registry,
)

# Doc 04 §2 and §3, transcribed. If this table and the document ever disagree, the document wins and
# this test is the thing that says so.
DOC_04: dict[ModelRoute, dict[str, object]] = {
    ModelRoute.TUTOR_DEFAULT: {
        "soft": "0.020",
        "hard": "0.050",
        "p95_ms": 8000,
        "in": 12000,
        "out": 1200,
    },
    ModelRoute.DIAGNOSTIC_DEFAULT: {
        "soft": "0.015",
        "hard": "0.040",
        "p95_ms": 6000,
        "in": 8000,
        "out": 700,
    },
    ModelRoute.ASSESSMENT_DEFAULT: {
        "soft": "0.030",
        "hard": "0.060",
        "p95_ms": 10000,
        "in": 12000,
        "out": 1400,
    },
    ModelRoute.ADAPTATION_DEFAULT: {
        "soft": "0.015",
        "hard": "0.040",
        "p95_ms": 6000,
        "in": 8000,
        "out": 700,
    },
}


DOC_04_ROUTES: list[ModelRoute] = sorted(DOC_04.keys(), key=lambda route: route.value)


@pytest.mark.parametrize("route", DOC_04_ROUTES)
def test_route_guardrails_match_doc_04(route: ModelRoute) -> None:
    expected = DOC_04[route]
    config = default_registry().resolve(route)

    assert config.soft_target_cost_usd == Decimal(str(expected["soft"]))
    assert config.hard_cost_ceiling_usd == Decimal(str(expected["hard"]))
    assert config.completion_target_p95_ms == expected["p95_ms"]
    assert config.max_input_tokens == expected["in"]
    assert config.max_output_tokens == expected["out"]


def test_every_route_in_the_enum_is_configured() -> None:
    """An unconfigured route would fail at call time, in production, on the first request."""
    registry = default_registry()
    for route in ModelRoute:
        assert registry.resolve(route) is not None


def test_ci_fake_costs_nothing() -> None:
    """Doc 04 §2. A fake with a non-zero price would make CI's spend a function of test volume."""
    config = default_registry().resolve(ModelRoute.CI_FAKE)
    assert config.hard_cost_ceiling_usd == Decimal("0.000")
    assert config.input_cost_per_1k_usd == Decimal("0.000")
    assert config.output_cost_per_1k_usd == Decimal("0.000")


def test_the_route_table_is_versioned() -> None:
    """Results record this, so a call can be traced to the configuration that produced it."""
    assert default_registry().version == ROUTE_TABLE_VERSION


# -- the ceilings must refuse -------------------------------------------------------------------


def test_oversized_input_is_refused_rather_than_truncated() -> None:
    config = default_registry().resolve(ModelRoute.DIAGNOSTIC_DEFAULT)

    with pytest.raises(GatewayError) as refusal:
        budget.enforce_input_ceiling(config, config.max_input_tokens + 1)

    assert refusal.value.code is GatewayErrorCode.TOKEN_CEILING_EXCEEDED
    assert refusal.value.is_governance_refusal


def test_input_exactly_at_the_ceiling_is_allowed() -> None:
    """Off-by-one in the strict direction is still a defect: it rejects work that was in budget."""
    config = default_registry().resolve(ModelRoute.DIAGNOSTIC_DEFAULT)
    budget.enforce_input_ceiling(config, config.max_input_tokens)


def test_cost_is_projected_against_the_worst_case_output() -> None:
    """Pricing the hoped-for output would make the ceiling an estimate."""
    config = default_registry().resolve(ModelRoute.TUTOR_DEFAULT)

    projected = budget.project_cost_usd(config, input_tokens=1000, max_output_tokens=1000)

    # 1000 input at 0.003/1k + 1000 output at 0.015/1k
    assert projected == Decimal("0.018000")


def test_a_call_over_the_hard_cost_ceiling_is_refused() -> None:
    config = default_registry().resolve(ModelRoute.DIAGNOSTIC_DEFAULT)

    over = config.hard_cost_ceiling_usd + Decimal("0.000001")
    with pytest.raises(GatewayError) as refusal:
        budget.enforce_cost_ceiling(config, over)

    assert refusal.value.code is GatewayErrorCode.COST_CEILING_EXCEEDED


def test_a_call_exactly_at_the_hard_ceiling_is_allowed() -> None:
    config = default_registry().resolve(ModelRoute.DIAGNOSTIC_DEFAULT)
    budget.enforce_cost_ceiling(config, config.hard_cost_ceiling_usd)


def test_request_cost_ceiling_allows_the_exact_remaining_boundary() -> None:
    config = default_registry().resolve(ModelRoute.TUTOR_DEFAULT)
    projected = budget.project_cost_usd(config, input_tokens=1000, max_output_tokens=1000)

    budget.enforce_request_cost_ceiling(
        config,
        projected,
        request_budget_usd=Decimal("0.020000"),
        cost_spent_usd=Decimal("0.002000"),
    )


def test_request_cost_ceiling_uses_existing_stable_cost_error() -> None:
    config = default_registry().resolve(ModelRoute.TUTOR_DEFAULT)
    projected = budget.project_cost_usd(config, input_tokens=1000, max_output_tokens=1000)

    with pytest.raises(GatewayError) as refusal:
        budget.enforce_request_cost_ceiling(
            config,
            projected,
            request_budget_usd=projected,
            cost_spent_usd=Decimal("0.000001"),
        )

    assert refusal.value.code is GatewayErrorCode.COST_CEILING_EXCEEDED
    assert not refusal.value.retryable
    assert not refusal.value.fallback_eligible


def test_disabled_request_cost_ceiling_preserves_route_budget_semantics() -> None:
    config = default_registry().resolve(ModelRoute.TUTOR_DEFAULT)
    projected = budget.project_cost_usd(config, input_tokens=1000, max_output_tokens=1000)

    budget.enforce_request_cost_ceiling(
        config,
        projected,
        request_budget_usd=Decimal("0.000000"),
        cost_spent_usd=Decimal("1.000000"),
    )


def test_a_budget_refusal_is_neither_retryable_nor_fallback_eligible() -> None:
    """The decision that stops a transient error becoming an unbounded bill."""
    for code in (GatewayErrorCode.TOKEN_CEILING_EXCEEDED, GatewayErrorCode.COST_CEILING_EXCEEDED):
        failure = GatewayError(code, "refused")
        assert not failure.retryable
        assert not failure.fallback_eligible


# -- stricter-wins precedence (Doc 04 §7, M1-ADR-001) --------------------------------------------


def test_the_callers_tighter_output_limit_wins() -> None:
    config = default_registry().resolve(ModelRoute.TUTOR_DEFAULT)
    assert budget.resolve_output_ceiling(config, 300) == 300


def test_the_routes_tighter_output_limit_wins() -> None:
    """A caller cannot widen a route ceiling by asking for more."""
    config = default_registry().resolve(ModelRoute.DIAGNOSTIC_DEFAULT)
    assert budget.resolve_output_ceiling(config, 4000) == config.max_output_tokens


def test_no_requested_limit_falls_back_to_the_route_ceiling() -> None:
    config = default_registry().resolve(ModelRoute.TUTOR_DEFAULT)
    assert budget.resolve_output_ceiling(config, None) == config.max_output_tokens


# -- the table refuses to express an escalating fallback ------------------------------------------


def test_a_costlier_fallback_cannot_be_configured() -> None:
    """Makes the bad configuration unrepresentable rather than merely discouraged."""
    registry = default_registry()
    cheap = registry.resolve(ModelRoute.DIAGNOSTIC_DEFAULT)
    escalating = {**registry.routes}
    escalating[ModelRoute.DIAGNOSTIC_DEFAULT] = type(cheap)(
        **{**vars(cheap), "fallback_route": ModelRoute.ASSESSMENT_DEFAULT}
    )

    with pytest.raises(RouteTableError, match="never escalate"):
        RouteRegistry(version="TEST", routes=escalating)


def test_no_v1_route_declares_a_fallback() -> None:
    """Doc 04 permits fallback but approves no pairing; inventing one is a routing decision."""
    registry = default_registry()
    for config in registry.routes.values():
        assert config.fallback_route is None, (
            f"{config.route} declares a fallback that no document approves"
        )


# -- which ceiling binds, and why it is worth knowing ---------------------------------------------


def test_the_two_ceilings_are_not_redundant() -> None:
    """On tutor-default the cost ceiling refuses a request the token ceiling would allow.

    Doc 04 sets token ceilings (§3) and cost ceilings (§2) independently and specifies no unit
    prices, so which one binds is a function of the prices configured in the route table. At the
    configured prices, a maximal tutor-default request projects to 0.054 USD against a 0.050 USD
    ceiling -- meaning the effective input budget on that route is lower than §3 alone suggests.

    Asserted rather than left implicit because it is easy to read the two tables as alternative
    expressions of one limit, and to "simplify" by dropping the cost check. They are different
    limits, and on this route the cheaper-looking one is not the one that binds.
    """
    config = default_registry().resolve(ModelRoute.TUTOR_DEFAULT)

    worst_case = budget.project_cost_usd(
        config,
        input_tokens=config.max_input_tokens,
        max_output_tokens=config.max_output_tokens,
    )

    assert worst_case > config.hard_cost_ceiling_usd
    with pytest.raises(GatewayError) as refusal:
        budget.enforce_cost_ceiling(config, worst_case)
    assert refusal.value.code is GatewayErrorCode.COST_CEILING_EXCEEDED


@pytest.mark.parametrize(
    "route",
    [ModelRoute.DIAGNOSTIC_DEFAULT, ModelRoute.ADAPTATION_DEFAULT, ModelRoute.ASSESSMENT_DEFAULT],
)
def test_other_routes_are_bounded_by_tokens_first(route: ModelRoute) -> None:
    """Documents the converse, so a price change that flips one of these is visible in the diff."""
    config = default_registry().resolve(route)
    worst_case = budget.project_cost_usd(
        config,
        input_tokens=config.max_input_tokens,
        max_output_tokens=config.max_output_tokens,
    )
    assert worst_case <= config.hard_cost_ceiling_usd
