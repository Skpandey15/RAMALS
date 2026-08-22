"""Model routes as versioned configuration (Doc 04 §2–§3, M1-ADR-008).

A route is data: which model, which prompt version, what it may spend, how long it may take. Adding
one is a reviewed configuration change, not a new branch in the gateway. That is what makes
per-route budgets enforceable at all -- a budget needs something to be a budget *of*.

The table is validated at import time. A route whose fallback is more expensive than itself is a
configuration that would let a failure escalate cost, so it cannot be expressed: the module refuses
to load rather than allowing the invariant to hold "by review".
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, replace
from decimal import Decimal

from ramals_ai.config.settings import ModelRoute
from ramals_ai.prompting.templates import PromptRegister, PromptTemplateId

# Bump when any route's model, prompt, ceiling or target changes. Recorded with every call so a
# result can be traced back to the configuration that produced it.
ROUTE_TABLE_VERSION = "ROUTE_TABLE_V1"


@dataclass(frozen=True)
class ApprovedModel:
    """A model a route may be pointed at, carrying the prices that apply when it is.

    Prices travel with the model rather than with the route because they are the only reason the
    cost ceiling is enforceable at all. A binding that inherited the route's prices would project
    one provider's spend at another provider's list price: the ceiling would go on refusing and
    allowing exactly as before, while describing a bill nobody was running up.
    """

    model: str
    input_cost_per_1k_usd: Decimal
    output_cost_per_1k_usd: Decimal


@dataclass(frozen=True)
class RouteConfig:
    """One governed route. Immutable: rollback replaces the pointer, never edits the value."""

    route: ModelRoute
    model: str
    """Provider model identifier, pinned to a dated version rather than a moving alias.

    An alias like ``gpt-4o`` silently changes what produced an answer; a dated identifier makes a
    model change a configuration change with a review and a rollback path.
    """

    @property
    def resolved_provider(self) -> str:
        """Stable provider identity implied by the approved concrete model binding."""
        if self.model == "ci-fake-deterministic-v1":
            return "ci-fake"
        if self.model.startswith("claude-"):
            return "anthropic"
        if self.model.startswith("gpt-"):
            return "openai"
        raise RouteTableError(f"model '{self.model}' has no governed provider identity")

    prompt_versions: Mapping[PromptTemplateId, str]
    """Which revision of each prompt this route serves. The pointer M1-ADR-008 rolls back.

    A map rather than a single version, because a single version cannot describe either of the two
    shapes that actually occur: ``ci-fake`` serves all four agents, and the assessment agent has two
    prompts. One string covering several prompts names none of them, and the identity recorded
    against an output would then not be evidence of what produced it.
    """

    max_input_tokens: int
    max_output_tokens: int

    # Doc 04 §2. The soft target drives optimization and alerting; only the hard ceiling refuses.
    soft_target_cost_usd: Decimal
    hard_cost_ceiling_usd: Decimal
    completion_target_p95_ms: int

    # Used to project cost before dispatch. Provider list prices, versioned with the table.
    input_cost_per_1k_usd: Decimal
    output_cost_per_1k_usd: Decimal

    previously_approved_models: tuple[str, ...] = ()
    """Models this route was approved for before the current one, oldest first.

    Empty at V1, and that is the honest state: MVP-1 is the first release, so there is no earlier
    approved model to return to. It exists now rather than later because a model pin has to be
    checked against *something* -- an unchecked pin is a way to put an unreviewed model in front of
    learners with an environment variable, which is the opposite of what a rollback lever is for.
    """

    alternate_models: tuple[ApprovedModel, ...] = ()
    """Approved peers of the current model -- a different provider serving the same contract.

    Distinct from ``previously_approved_models``, and deliberately not merged with it. That tuple is
    a history: where this route may be returned *to*. This one is a set of equals: which vendors may
    serve this route *now*. Collapsing them would make a rollback and a provider switch
    indistinguishable in the table, and the audit question "what was this route approved to run
    before the incident" would no longer have an answer.

    The route contract -- prompt version, token ceilings, cost ceiling, latency target -- is
    unchanged by the choice. Only the model identity and its prices differ, and both are recorded.
    """

    fallback_route: ModelRoute | None = None
    """An approved, semantically equivalent route (Doc 04 §4).

    ``None`` for every route at V1: Doc 04 permits fallback but approves no specific pairing, and
    inventing one here would be a routing decision made by an implementer rather than by review.
    """

    @property
    def is_deterministic_fake(self) -> bool:
        return self.route is ModelRoute.CI_FAKE

    @property
    def approved_models(self) -> tuple[str, ...]:
        """Every model this route may be pointed at, current one included."""
        return (
            *self.previously_approved_models,
            *(binding.model for binding in self.alternate_models),
            self.model,
        )

    def binding_for(self, model: str) -> ApprovedModel | None:
        """The price binding for an alternate model, or ``None`` if this route declares none.

        ``None`` is the correct answer for the current model and for a rollback target, both of
        which are already priced by the route itself.
        """
        for binding in self.alternate_models:
            if binding.model == model:
                return binding
        return None

    def prompt_version_for(self, template_id: PromptTemplateId) -> str:
        """The revision of one prompt this route serves.

        Raises rather than returning a default: a route asked for a template it does not serve is a
        wiring mistake, and answering it with some other route's version would put a false identity
        on a real output.
        """
        try:
            return self.prompt_versions[template_id]
        except KeyError:
            raise RouteTableError(
                f"{self.route} serves no prompt for {template_id}; "
                f"it serves {sorted(t.value for t in self.prompt_versions)}"
            ) from None


def _route(
    route: ModelRoute,
    *,
    model: str,
    prompt_versions: Mapping[PromptTemplateId, str],
    max_input_tokens: int,
    max_output_tokens: int,
    soft_target: str,
    hard_ceiling: str,
    p95_ms: int,
    input_per_1k: str,
    output_per_1k: str,
    alternates: tuple[ApprovedModel, ...] = (),
) -> RouteConfig:
    return RouteConfig(
        route=route,
        model=model,
        prompt_versions=dict(prompt_versions),
        max_input_tokens=max_input_tokens,
        max_output_tokens=max_output_tokens,
        soft_target_cost_usd=Decimal(soft_target),
        hard_cost_ceiling_usd=Decimal(hard_ceiling),
        completion_target_p95_ms=p95_ms,
        input_cost_per_1k_usd=Decimal(input_per_1k),
        output_cost_per_1k_usd=Decimal(output_per_1k),
        alternate_models=alternates,
    )


# The OpenAI binding approved for the live routes, so a deployment holding an OpenAI credential can
# serve them without a table edit. A dated snapshot rather than the moving ``gpt-4.1`` alias, for
# the reason RouteConfig.model already gives: an alias silently changes what produced an answer.
#
# The prices are OpenAI list prices as of ROUTE_TABLE_V1 and are governance numbers, not trivia --
# they are what the cost ceiling projects against when this binding is pinned. Both sit below the
# Anthropic pin they stand in for, which _validate requires of an alternate.
_GPT_4_1 = ApprovedModel(
    model="gpt-4.1-2025-04-14",
    input_cost_per_1k_usd=Decimal("0.002"),
    output_cost_per_1k_usd=Decimal("0.008"),
)

# Doc 04 §2 and §3 verbatim. These numbers are versioned engineering guardrails, not external SLAs;
# TokenAndCostCeilingTests asserts them against the document so a quiet edit fails the build.
_V1_ROUTES: tuple[RouteConfig, ...] = (
    _route(
        ModelRoute.TUTOR_DEFAULT,
        model="claude-sonnet-5",
        prompt_versions={PromptTemplateId.TUTOR_EXPLAIN: "TUTOR_PROMPT_V1"},
        max_input_tokens=12000,
        max_output_tokens=1200,
        soft_target="0.020",
        hard_ceiling="0.050",
        p95_ms=8000,
        input_per_1k="0.003",
        output_per_1k="0.015",
        alternates=(_GPT_4_1,),
    ),
    _route(
        ModelRoute.DIAGNOSTIC_DEFAULT,
        model="claude-sonnet-5",
        prompt_versions={PromptTemplateId.DIAGNOSTIC_ROOT_CAUSE: "DIAGNOSTIC_PROMPT_V1"},
        max_input_tokens=8000,
        max_output_tokens=700,
        soft_target="0.015",
        hard_ceiling="0.040",
        p95_ms=6000,
        input_per_1k="0.003",
        output_per_1k="0.015",
        alternates=(_GPT_4_1,),
    ),
    _route(
        ModelRoute.ASSESSMENT_DEFAULT,
        model="claude-sonnet-5",
        prompt_versions={
            PromptTemplateId.ASSESSMENT_ITEM: "ASSESSMENT_PROMPT_V1",
            PromptTemplateId.ASSESSMENT_EVALUATE: "ASSESSMENT_PROMPT_V1",
        },
        max_input_tokens=12000,
        max_output_tokens=1400,
        soft_target="0.030",
        hard_ceiling="0.060",
        p95_ms=10000,
        input_per_1k="0.003",
        output_per_1k="0.015",
        alternates=(_GPT_4_1,),
    ),
    _route(
        ModelRoute.ADAPTATION_DEFAULT,
        model="claude-sonnet-5",
        prompt_versions={PromptTemplateId.ADAPTATION_PLAN: "ADAPTATION_PROMPT_V1"},
        max_input_tokens=8000,
        max_output_tokens=700,
        soft_target="0.015",
        hard_ceiling="0.040",
        p95_ms=6000,
        input_per_1k="0.003",
        output_per_1k="0.015",
        alternates=(_GPT_4_1,),
    ),
    # Doc 04 §2: zero cost, deterministic, local. Not a mock bolted onto the tests -- a real route,
    # so CI exercises the same gateway path a provider call takes.
    _route(
        ModelRoute.CI_FAKE,
        model="ci-fake-deterministic-v1",
        # Serving four agents, this route carries a pointer per template. It previously carried
        # one string, CI_FAKE_PROMPT_V1, which named none of the five prompts it actually sends --
        # so every CI run recorded a prompt identity no artifact could match.
        prompt_versions={
            PromptTemplateId.TUTOR_EXPLAIN: "TUTOR_PROMPT_V1",
            PromptTemplateId.DIAGNOSTIC_ROOT_CAUSE: "DIAGNOSTIC_PROMPT_V1",
            PromptTemplateId.ASSESSMENT_ITEM: "ASSESSMENT_PROMPT_V1",
            PromptTemplateId.ASSESSMENT_EVALUATE: "ASSESSMENT_PROMPT_V1",
            PromptTemplateId.ADAPTATION_PLAN: "ADAPTATION_PROMPT_V1",
        },
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
        seen: set[str] = set()
        for binding in config.alternate_models:
            # An alternate naming the current model is not an error by itself: pinning promotes
            # an alternate to primary, and the binding it came from is still listed. What must
            # never hold is the two disagreeing on price -- then which number the ceiling projects
            # against depends on whether the route was pinned, and the same call is affordable or
            # refused for reasons nothing in the table explains.
            if binding.model == config.model and (
                binding.input_cost_per_1k_usd != config.input_cost_per_1k_usd
                or binding.output_cost_per_1k_usd != config.output_cost_per_1k_usd
            ):
                raise RouteTableError(
                    f"{config.route}: alternate '{binding.model}' names the route's current "
                    "model at a different price; the primary and its binding must agree"
                )
            if binding.model in seen:
                raise RouteTableError(
                    f"{config.route}: '{binding.model}' is declared twice as an alternate, so "
                    "which price applies would depend on declaration order"
                )
            seen.add(binding.model)
            if (
                binding.input_cost_per_1k_usd > config.input_cost_per_1k_usd
                or binding.output_cost_per_1k_usd > config.output_cost_per_1k_usd
            ):
                raise RouteTableError(
                    f"{config.route}: alternate model '{binding.model}' is priced above the model "
                    f"the route was approved at ({binding.input_cost_per_1k_usd}/"
                    f"{binding.output_cost_per_1k_usd} vs {config.input_cost_per_1k_usd}/"
                    f"{config.output_cost_per_1k_usd} per 1k); changing provider must never "
                    "escalate cost (M1-ADR-008)"
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
        register: PromptRegister,
        model: str | None = None,
        prompts: Mapping[PromptTemplateId, str] | None = None,
    ) -> RouteRegistry:
        """Points a route at an approved model and/or prompt revisions.

        Serves two motions that look identical in the table and differ only in intent: withdrawing a
        bad model (a rollback, to ``previously_approved_models``) and selecting an equally approved
        vendor (a provider switch, to ``alternate_models``). Both are pins, both are checked against
        what this image ships, and both are recorded in the resolved table version.

        Model and prompts roll back independently (M1-ADR-008): a prompt regression should not force
        a model rollback, and coupling them would make the cheap remedy carry the expensive one's
        risk. Prompts roll back per template for the same reason -- an assessment item regression
        should not withdraw the evaluation prompt.

        ``register`` is required rather than optional, and this is the check that makes the whole
        mechanism safe to expose to an operator. A prompt version is what gets *recorded* about an
        output, so accepting a version this build cannot produce would let a rollback relabel every
        subsequent proposal while changing nothing that ran. Refusing here means a rollback target
        must be a prompt already reviewed and shipped in this image.
        """
        if model is None and not prompts:
            raise ValueError("a rollback must change the model, at least one prompt, or both")
        current = self.resolve(route)
        if model is not None and model not in current.approved_models:
            raise RouteTableError(
                f"{route} was never approved for model '{model}'; "
                f"approved models are {list(current.approved_models)}"
            )

        updated_prompts = dict(current.prompt_versions)
        for template_id, version in (prompts or {}).items():
            if template_id not in current.prompt_versions:
                raise RouteTableError(
                    f"{route} serves no prompt for {template_id}, so there is nothing to roll back"
                )
            # Resolving, not merely checking membership: the artifact is what will build the
            # messages, so this proves the rollback target can actually be produced.
            register.resolve(template_id, version)
            updated_prompts[template_id] = version

        # Pinning an alternate carries its prices across with it. Leaving the route's prices in
        # place would project an OpenAI call at Anthropic list price -- the ceiling would still
        # refuse and allow, just against a number describing a bill nobody was running up.
        input_price = current.input_cost_per_1k_usd
        output_price = current.output_cost_per_1k_usd
        binding = None if model is None else current.binding_for(model)
        if binding is not None:
            input_price = binding.input_cost_per_1k_usd
            output_price = binding.output_cost_per_1k_usd

        rolled = self.with_route(
            replace(
                current,
                model=current.model if model is None else model,
                prompt_versions=updated_prompts,
                input_cost_per_1k_usd=input_price,
                output_cost_per_1k_usd=output_price,
            )
        )
        return RouteRegistry(version=rolled._pinned_version(), routes=rolled.routes)

    def _pinned_version(self) -> str:
        """The table version, extended with whatever differs from the shipped table.

        Recorded with every call, so it has to say when the configuration is no longer the one the
        image shipped. A rollback that left ``ROUTE_TABLE_V1`` on every record would make the two
        halves of an incident -- before and after -- indistinguishable in the logs.
        """
        shipped = {config.route: config for config in _V1_ROUTES}
        pins: list[str] = []
        for route in sorted(self.routes, key=lambda r: r.value):
            config = self.routes[route]
            baseline = shipped.get(route)
            if baseline is None:
                continue
            if config.model != baseline.model:
                pins.append(f"{route.value}:model={config.model}")
            for template_id in sorted(config.prompt_versions, key=lambda t: t.value):
                version = config.prompt_versions[template_id]
                if baseline.prompt_versions.get(template_id) != version:
                    pins.append(f"{route.value}:{template_id.value}={version}")
        return ROUTE_TABLE_VERSION if not pins else f"{ROUTE_TABLE_VERSION}+" + ",".join(pins)


def pins_from_config(
    prompt_pins: Mapping[str, Mapping[str, str]],
    model_pins: Mapping[str, str],
) -> tuple[dict[ModelRoute, dict[PromptTemplateId, str]], dict[ModelRoute, str]]:
    """Turns configuration strings into route and template identities.

    Names are resolved rather than trusted. An operator typing ``tutor_default`` or ``TUTOR_PROMPT``
    has made a mistake that must stop the process: a pin silently dropped because its key did not
    parse is a rollback that appears to have been applied and was not.
    """

    def route_of(name: str) -> ModelRoute:
        try:
            return ModelRoute(name)
        except ValueError:
            raise RouteTableError(
                f"'{name}' is not a model route; routes are "
                f"{sorted(route.value for route in ModelRoute)}"
            ) from None

    def template_of(name: str) -> PromptTemplateId:
        try:
            return PromptTemplateId(name)
        except ValueError:
            raise RouteTableError(
                f"'{name}' is not a prompt template; templates are "
                f"{sorted(template.value for template in PromptTemplateId)}"
            ) from None

    prompts = {
        route_of(route): {template_of(template): version for template, version in pins.items()}
        for route, pins in prompt_pins.items()
    }
    models = {route_of(route): model for route, model in model_pins.items()}
    return prompts, models


def registry_from_pins(
    register: PromptRegister,
    *,
    prompt_pins: Mapping[ModelRoute, Mapping[PromptTemplateId, str]] | None = None,
    model_pins: Mapping[ModelRoute, str] | None = None,
) -> RouteRegistry:
    """The shipped table with rollback pins applied, as resolved at startup.

    This is what makes M1-ADR-008's "rollback is available without a service deployment" true rather
    than aspirational. Without it the only way to withdraw a bad prompt is to edit the table, build
    an image and run the release pipeline -- which is precisely the alternative the ADR rejected,
    because it makes the fastest available remedy as slow and as risky as shipping.

    Every pin is checked here, at startup, against what the image can actually produce. A bad pin
    stops the process rather than degrading it: a service that starts while silently ignoring a
    rollback is indistinguishable from one that applied it, and the difference only surfaces in the
    outputs somebody was trying to stop producing.
    """
    registry = default_registry()
    for route, model in (model_pins or {}).items():
        registry = registry.rolled_back(route, register=register, model=model)
    for route, prompts in (prompt_pins or {}).items():
        if prompts:
            registry = registry.rolled_back(route, register=register, prompts=prompts)
    return registry


def unbuildable_pointers(registry: RouteRegistry, register: PromptRegister) -> tuple[str, ...]:
    """Route pointers naming a prompt revision this build cannot produce.

    The route table names versions as literal strings -- it cannot import the agent prompt modules
    without a cycle -- so the two can drift. This is the check that stops the drift being silent,
    and it is applied at startup as well as in a test: a table naming a prompt that does not exist
    would put an unbuildable identity on every proposal from that route.
    """
    return tuple(
        f"{route.value}:{template_id.value}={version}"
        for route, config in sorted(registry.routes.items(), key=lambda item: item[0].value)
        for template_id, version in sorted(
            config.prompt_versions.items(), key=lambda item: item[0].value
        )
        if not register.is_approved(template_id, version)
    )


def default_registry() -> RouteRegistry:
    """The V1 table. A fresh dict each call so no caller can mutate the shared default."""
    return RouteRegistry(
        version=ROUTE_TABLE_VERSION,
        routes={config.route: config for config in _V1_ROUTES},
    )
