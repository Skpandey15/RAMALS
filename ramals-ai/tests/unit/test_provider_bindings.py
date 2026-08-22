"""A live route must be servable by either approved provider, without a table edit.

MVP-1 pinned every live route to ``claude-sonnet-5``. A deployment holding an OpenAI credential
could not serve them at all: the credential itself is provider-agnostic -- LiteLLM routes on the
model name -- so the only thing binding the platform to one vendor was the model string here.

The interesting half of these is not that a switch is possible. It is that switching cannot quietly
move anything else. The route contract -- prompt version, token ceilings, cost ceiling, latency
target -- is what a proposal's recorded identity means; a provider switch that also relaxed a
ceiling would make two runs labelled with the same route incomparable.
"""

from __future__ import annotations

from dataclasses import replace
from decimal import Decimal

import pytest

from ramals_ai.config.settings import ModelRoute, Settings
from ramals_ai.gateway import budget
from ramals_ai.gateway.routes import (
    ApprovedModel,
    RouteRegistry,
    RouteTableError,
    default_registry,
    pins_from_config,
    registry_from_pins,
)
from ramals_ai.prompting.register import default_prompt_register

OPENAI_MODEL = "gpt-4.1-2025-04-14"
ANTHROPIC_MODEL = "claude-sonnet-5"
LIVE_ROUTES = [
    ModelRoute.TUTOR_DEFAULT,
    ModelRoute.DIAGNOSTIC_DEFAULT,
    ModelRoute.ASSESSMENT_DEFAULT,
    ModelRoute.ADAPTATION_DEFAULT,
]


def _pinned(route: ModelRoute, model: str) -> RouteRegistry:
    """The registry as a process resolves it from configuration, rather than hand-built."""
    settings = Settings(model_pins={route.value: model})
    prompt_pins, model_pins = pins_from_config(settings.prompt_pins, settings.model_pins)
    return registry_from_pins(
        default_prompt_register(), prompt_pins=prompt_pins, model_pins=model_pins
    )


# -- both providers are approved, and only where a provider is involved --------------------------


@pytest.mark.parametrize("route", LIVE_ROUTES)
def test_every_live_route_approves_both_providers(route: ModelRoute) -> None:
    approved = default_registry().resolve(route).approved_models
    assert ANTHROPIC_MODEL in approved
    assert OPENAI_MODEL in approved


def test_ci_fake_has_no_provider_alternate() -> None:
    """It is deterministic and local. An alternate would imply a vendor it never calls."""
    assert default_registry().resolve(ModelRoute.CI_FAKE).alternate_models == ()


def test_the_approved_model_is_a_dated_snapshot_not_a_moving_alias() -> None:
    """RouteConfig.model's own rule: an alias silently changes what produced an answer."""
    with pytest.raises(RouteTableError, match="never approved"):
        default_registry().rolled_back(
            ModelRoute.ADAPTATION_DEFAULT, register=default_prompt_register(), model="gpt-4.1"
        )


# -- switching provider moves the model and its prices, and nothing else -------------------------


def test_pinning_the_alternate_switches_the_model() -> None:
    config = _pinned(ModelRoute.ADAPTATION_DEFAULT, OPENAI_MODEL).resolve(
        ModelRoute.ADAPTATION_DEFAULT
    )
    assert config.model == OPENAI_MODEL


def test_pinning_the_alternate_carries_its_prices() -> None:
    """The defect this exists to catch: a switch that leaves the old vendor's prices behind.

    Nothing would fail visibly. The ceiling would go on refusing and allowing exactly as before,
    projecting an OpenAI call at Anthropic list price -- enforcing a limit on a bill nobody was
    actually running up.
    """
    config = _pinned(ModelRoute.ADAPTATION_DEFAULT, OPENAI_MODEL).resolve(
        ModelRoute.ADAPTATION_DEFAULT
    )
    assert config.input_cost_per_1k_usd == Decimal("0.002")
    assert config.output_cost_per_1k_usd == Decimal("0.008")


def test_the_switched_prices_reach_the_cost_projection() -> None:
    """Asserted through budget rather than on the field, because the field is not the point.

    1000 in + 1000 out is 0.010 at the OpenAI binding and 0.018 at the Anthropic one. If the pin
    ever stopped repricing, this reads 0.018 and the assertion below is what changes.
    """
    config = _pinned(ModelRoute.ADAPTATION_DEFAULT, OPENAI_MODEL).resolve(
        ModelRoute.ADAPTATION_DEFAULT
    )
    projected = budget.project_cost_usd(config, input_tokens=1000, max_output_tokens=1000)

    assert projected == Decimal("0.010000")
    assert projected != Decimal("0.018000"), "the route is still priced as Anthropic"


@pytest.mark.parametrize("route", LIVE_ROUTES)
def test_switching_provider_does_not_move_the_route_contract(route: ModelRoute) -> None:
    """The guardrails are Doc 04's; they belong to the route, not to whoever serves it."""
    before = default_registry().resolve(route)
    after = _pinned(route, OPENAI_MODEL).resolve(route)

    assert after.max_input_tokens == before.max_input_tokens
    assert after.max_output_tokens == before.max_output_tokens
    assert after.hard_cost_ceiling_usd == before.hard_cost_ceiling_usd
    assert after.soft_target_cost_usd == before.soft_target_cost_usd
    assert after.completion_target_p95_ms == before.completion_target_p95_ms
    assert after.prompt_versions == before.prompt_versions


def test_pinning_one_route_leaves_the_others_on_their_shipped_model() -> None:
    registry = _pinned(ModelRoute.ADAPTATION_DEFAULT, OPENAI_MODEL)
    for route in LIVE_ROUTES:
        if route is ModelRoute.ADAPTATION_DEFAULT:
            continue
        assert registry.resolve(route).model == ANTHROPIC_MODEL


def test_the_switch_is_recorded_in_the_resolved_table_version() -> None:
    """Provenance. Two proposals from different vendors must not label themselves identically."""
    version = _pinned(ModelRoute.ADAPTATION_DEFAULT, OPENAI_MODEL).version
    assert version == "ROUTE_TABLE_V1+adaptation-default:model=" + OPENAI_MODEL


@pytest.mark.parametrize("route", LIVE_ROUTES)
def test_a_maximal_request_stays_within_the_ceiling_on_the_alternate(route: ModelRoute) -> None:
    """A binding whose prices refuse every maximal request would be approved in name only."""
    config = _pinned(route, OPENAI_MODEL).resolve(route)
    worst_case = budget.project_cost_usd(
        config,
        input_tokens=config.max_input_tokens,
        max_output_tokens=config.max_output_tokens,
    )
    assert worst_case <= config.hard_cost_ceiling_usd


# -- the table refuses to express a binding that would escalate cost ------------------------------


def _with_alternates(route: ModelRoute, *alternates: ApprovedModel) -> None:
    registry = default_registry()
    routes = {**registry.routes}
    routes[route] = replace(registry.resolve(route), alternate_models=alternates)
    RouteRegistry(version="TEST", routes=routes)


def test_an_alternate_priced_above_the_primary_cannot_be_configured() -> None:
    """Mirrors the fallback rule: changing vendor must never escalate cost (M1-ADR-008).

    Without this, approving a costlier vendor is a one-line table edit that raises the real spend of
    every call on the route while every ceiling in the document still reads unchanged.
    """
    with pytest.raises(RouteTableError, match="never escalate cost"):
        _with_alternates(
            ModelRoute.ADAPTATION_DEFAULT,
            ApprovedModel("pricey", Decimal("0.004"), Decimal("0.008")),
        )


def test_an_alternate_dearer_on_output_alone_is_also_refused() -> None:
    """Checked per dimension: a cheaper input token does not buy a dearer output token."""
    with pytest.raises(RouteTableError, match="never escalate cost"):
        _with_alternates(
            ModelRoute.ADAPTATION_DEFAULT,
            ApprovedModel("lopsided", Decimal("0.001"), Decimal("0.020")),
        )


def test_the_same_alternate_declared_twice_is_refused() -> None:
    with pytest.raises(RouteTableError, match="declared twice"):
        _with_alternates(
            ModelRoute.ADAPTATION_DEFAULT,
            ApprovedModel(OPENAI_MODEL, Decimal("0.002"), Decimal("0.008")),
            ApprovedModel(OPENAI_MODEL, Decimal("0.001"), Decimal("0.004")),
        )


def test_an_alternate_repricing_the_current_model_is_refused() -> None:
    """Otherwise a call's projected cost depends on whether the route happened to be pinned."""
    with pytest.raises(RouteTableError, match="must agree"):
        _with_alternates(
            ModelRoute.ADAPTATION_DEFAULT,
            ApprovedModel(ANTHROPIC_MODEL, Decimal("0.001"), Decimal("0.005")),
        )


def test_a_pinned_registry_still_validates() -> None:
    """The pinned model is promoted to primary while its binding is still listed.

    That state has to stay expressible, or the switch this change exists for fails at startup --
    which is exactly how the shadow check was written the first time.
    """
    registry = _pinned(ModelRoute.ADAPTATION_DEFAULT, OPENAI_MODEL)
    config = registry.resolve(ModelRoute.ADAPTATION_DEFAULT)
    assert config.model == OPENAI_MODEL
    assert config.binding_for(OPENAI_MODEL) is not None


# -- the build must actually be able to reach a provider ------------------------------------------
#
# None of the approvals above mean anything if the deployable artifact cannot call a provider at
# all. RC7 shipped without the SDK: `pip install .` omits optional extras, the adapter imports
# lazily, and every health gate passed because `ci-fake` never imports it. The first live call
# would have failed with ROUTE_NOT_CONFIGURED, in production, on a learner request.


def _pyproject() -> dict[str, object]:
    import tomllib
    from pathlib import Path

    with (Path(__file__).resolve().parents[2] / "pyproject.toml").open("rb") as handle:
        return tomllib.load(handle)


def _dockerfile() -> str:
    from pathlib import Path

    return (Path(__file__).resolve().parents[2] / "Dockerfile").read_text(encoding="utf-8")


def test_the_image_installs_the_provider_extra() -> None:
    """Without it the image can only ever serve the deterministic fake."""
    assert ".[provider]" in _dockerfile(), (
        "the runtime image installs the base package only, so no live model route can dispatch"
    )


def test_the_test_environment_pins_the_same_sdk_as_the_image() -> None:
    """CI validating one SDK version while the image ships another is worse than not testing."""
    project = _pyproject()["project"]
    assert isinstance(project, dict)
    extras = project["optional-dependencies"]
    shipped = [d for d in extras["provider"] if d.startswith("litellm")]
    tested = [d for d in extras["dev"] if d.startswith("litellm")]

    assert shipped, "the provider extra no longer pins litellm"
    assert tested, "the dev extra cannot construct a live-route app without the SDK"
    assert shipped == tested, f"image ships {shipped}, tests run {tested}"


def test_a_live_route_without_the_provider_sdk_is_refused_at_startup(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Fails at startup rather than on the first learner request that reaches an agent.

    Same rule Settings already applies to a missing credential: a live route that cannot dispatch
    is a misconfiguration, not a degraded mode. Binding the name to ``None`` is what makes
    ``import litellm`` raise, so this reproduces the shipped-image state exactly.
    """
    import sys

    from ramals_ai.config.settings import ConfigurationError, Environment
    from ramals_ai.main import create_app

    monkeypatch.setitem(sys.modules, "litellm", None)

    with pytest.raises(ConfigurationError, match="provider"):
        create_app(
            Settings(
                environment=Environment.TEST,
                ai_enabled=True,
                model_route=ModelRoute.ADAPTATION_DEFAULT,
                provider_api_key="not-a-real-key",
            )
        )


def test_the_fake_route_still_starts_without_the_provider_sdk(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The lazy import must survive. CI and a fresh checkout run without the SDK installed."""
    import sys

    from ramals_ai.config.settings import Environment
    from ramals_ai.main import create_app

    monkeypatch.setitem(sys.modules, "litellm", None)

    assert (
        create_app(
            Settings(environment=Environment.TEST, ai_enabled=True, model_route=ModelRoute.CI_FAKE)
        )
        is not None
    )
