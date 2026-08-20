"""The gateway end to end, on the ci-fake route.

Covers the M1-T05 required tests that need the whole path rather than a single function: ci-fake
determinism, retry and fallback eligibility, deadline arithmetic, and the metadata a proposal
carries. The fake is a real route, so this exercises the same code a provider call takes.

Time is injected. Tests that sleep are slow and flaky; tests that drive a clock are neither, and
they can assert things a wall clock makes untestable -- like a deadline expiring between two
attempts.
"""

from __future__ import annotations

import os
import subprocess
import sys
import textwrap
from collections.abc import Callable
from dataclasses import replace
from decimal import Decimal

import pytest

from ramals_ai.config.settings import ModelRoute
from ramals_ai.contracts.generated import InteractionClass
from ramals_ai.gateway import budget
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.gateway import MAX_ATTEMPTS_PER_ROUTE, LLMGateway
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.gateway.routes import (
    ROUTE_TABLE_VERSION,
    RouteRegistry,
    RouteTableError,
    default_registry,
)
from ramals_ai.prompting.register import default_prompt_register
from ramals_ai.prompting.templates import (
    BuiltPrompt,
    PromptArtifact,
    PromptRegister,
    PromptTemplateId,
    UnknownPromptVersionError,
    register_of,
)

MESSAGES = (
    Message(role="system", content="You are a tutor."),
    Message(role="user", content="Explain Kafka partitioning."),
)


def prompt_of(messages: tuple[Message, ...]) -> BuiltPrompt:
    """Fixture messages under a real prompt identity.

    The gateway records what it was handed, so these tests hand it an identity rather than letting
    it read one off the route table -- which is the behaviour under test in the rollback cases
    below.
    """
    return BuiltPrompt(
        template_id=PromptTemplateId.TUTOR_EXPLAIN,
        version=default_registry()
        .resolve(ModelRoute.CI_FAKE)
        .prompt_version_for(PromptTemplateId.TUTOR_EXPLAIN),
        messages=messages,
    )


PROMPT = prompt_of(MESSAGES)


class ManualClock:
    """A clock the test advances explicitly."""

    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance_ms(self, milliseconds: float) -> None:
        self.now += milliseconds / 1000.0


def build(
    adapter: object | None = None,
    *,
    registry: RouteRegistry | None = None,
    clock: ManualClock | None = None,
) -> tuple[LLMGateway, ManualClock]:
    ticker = clock or ManualClock()
    gateway = LLMGateway(
        adapter or FakeProvider(),  # type: ignore[arg-type]
        registry=registry,
        clock=ticker,
        # Backoff must not really sleep, but it must still consume the deadline, or a test could
        # never observe a retry running out of time.
        sleep=lambda seconds: ticker.advance_ms(seconds * 1000),
    )
    return gateway, ticker


def deadline_for(clock: ManualClock, milliseconds: int) -> Deadline:
    return Deadline.in_ms(milliseconds, clock=clock)


# -- ci-fake determinism (required test) ----------------------------------------------------------


def test_ci_fake_is_deterministic_across_calls() -> None:
    gateway, clock = build()

    first = gateway.complete(
        route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 5000)
    )
    second = gateway.complete(
        route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 5000)
    )

    assert first.text == second.text


def test_ci_fake_is_deterministic_across_separate_interpreters() -> None:
    """Determinism within one process is not determinism.

    ``hash()`` is salted per interpreter but stable inside one, so a fake built on it passes any
    same-process check and still differs on the machine someone is using to reproduce a result.
    Two interpreters with different PYTHONHASHSEED values is the only arrangement that can tell
    the difference, so this actually starts them.
    """
    script = textwrap.dedent(
        """
        from ramals_ai.gateway.providers.base import Message, ProviderRequest
        from ramals_ai.gateway.providers.fake import FakeProvider

        request = ProviderRequest(
            model="ci-fake-deterministic-v1",
            messages=(Message(role="user", content="Explain Kafka partitioning."),),
            max_output_tokens=100,
            timeout_seconds=1.0,
        )
        print(FakeProvider().complete(request).text)
        """
    )

    def run(seed: str) -> str:
        completed = subprocess.run(  # noqa: S603 - fixed argv, no shell
            [sys.executable, "-c", script],
            capture_output=True,
            text=True,
            check=True,
            env={**os.environ, "PYTHONHASHSEED": seed},
        )
        return completed.stdout.strip()

    assert run("0") == run("12345")


def test_ci_fake_output_varies_with_input() -> None:
    """A constant answer would also be 'deterministic' and would prove nothing."""
    gateway, clock = build()
    other = (Message(role="user", content="Explain consumer groups."),)

    first = gateway.complete(
        route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 5000)
    )
    second = gateway.complete(
        route=ModelRoute.CI_FAKE, prompt=prompt_of(other), deadline=deadline_for(clock, 5000)
    )

    assert first.text != second.text


def test_ci_fake_costs_nothing_end_to_end() -> None:
    gateway, clock = build()
    result = gateway.complete(
        route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 5000)
    )
    assert result.estimated_cost_usd == Decimal("0.000000")
    assert result.cost_string == "0.000000"


# -- metadata a proposal must carry ---------------------------------------------------------------


def test_the_result_records_the_configuration_that_produced_it() -> None:
    gateway, clock = build()
    config = default_registry().resolve(ModelRoute.CI_FAKE)

    result = gateway.complete(
        route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 5000)
    )

    assert result.route is ModelRoute.CI_FAKE
    assert result.model == config.model
    assert result.prompt_version == config.prompt_version_for(PromptTemplateId.TUTOR_EXPLAIN)
    assert result.prompt_template_id is PromptTemplateId.TUTOR_EXPLAIN
    assert result.route_table_version == default_registry().version
    assert result.attempts == 1
    assert not result.fell_back


def test_usage_is_reported() -> None:
    gateway, clock = build()
    result = gateway.complete(
        route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 5000)
    )
    assert result.input_tokens > 0
    assert result.output_tokens > 0
    assert result.latency_ms >= 0


def test_latency_and_cost_are_tagged_by_class_and_effective_route(
    histogram_delta: Callable[[str, dict[str, str]], tuple[int, float]],
) -> None:
    gateway, clock = build()
    labels = {"interaction_class": InteractionClass.ASSESSMENT_PROPOSAL.value, "route": "ci-fake"}
    snapshot = histogram_delta.snapshot  # type: ignore[attr-defined]
    delta = histogram_delta
    snapshot("ramals.ai.latency", labels)
    snapshot("ramals.ai.cost", labels)

    result = gateway.complete(
        route=ModelRoute.CI_FAKE,
        prompt=PROMPT,
        deadline=deadline_for(clock, 5000),
        interaction_class=InteractionClass.ASSESSMENT_PROPOSAL,
    )

    latency_count, latency_sum = delta("ramals.ai.latency", labels)
    cost_count, cost_sum = delta("ramals.ai.cost", labels)
    assert result.interaction_class is InteractionClass.ASSESSMENT_PROPOSAL
    assert latency_count == 1
    assert latency_sum == result.latency_ms
    assert cost_count == 1
    assert cost_sum == float(result.estimated_cost_usd)


# -- budgets refuse before dispatch ---------------------------------------------------------------


class CountingProvider(FakeProvider):
    """Records whether the provider was reached, so a refusal can be proven to be pre-dispatch."""

    def __init__(self, *, token_count: int | None = None) -> None:
        super().__init__()
        self.calls = 0
        self._token_count = token_count

    def count_input_tokens(self, model: str, messages: tuple[Message, ...]) -> int:
        if self._token_count is not None:
            return self._token_count
        return super().count_input_tokens(model, messages)

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        self.calls += 1
        return super().complete(request)


def test_an_oversized_context_never_reaches_the_provider() -> None:
    """The decisive property of a pre-dispatch ceiling: nothing was sent and nothing was billed."""
    config = default_registry().resolve(ModelRoute.CI_FAKE)
    adapter = CountingProvider(token_count=config.max_input_tokens + 1)
    gateway, clock = build(adapter)

    with pytest.raises(GatewayError) as refusal:
        gateway.complete(
            route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 5000)
        )

    assert refusal.value.code is GatewayErrorCode.TOKEN_CEILING_EXCEEDED
    assert adapter.calls == 0, "the provider was contacted despite the ceiling refusing the call"


def test_a_cost_ceiling_refusal_never_reaches_the_provider() -> None:
    """Uses tutor-default, the one V1 route where cost binds before tokens do.

    ci-fake is free and can never exceed a cost ceiling, so it cannot exercise this path at all.
    """
    registry = default_registry()
    priced = registry.resolve(ModelRoute.TUTOR_DEFAULT)
    # Inside the token ceiling, so the *cost* ceiling is what refuses.
    adapter = CountingProvider(token_count=priced.max_input_tokens)
    gateway, clock = build(adapter)

    with pytest.raises(GatewayError) as refusal:
        gateway.complete(
            route=ModelRoute.TUTOR_DEFAULT,
            prompt=PROMPT,
            deadline=deadline_for(clock, 5000),
        )

    assert refusal.value.code is GatewayErrorCode.COST_CEILING_EXCEEDED
    assert adapter.calls == 0


def test_request_budget_with_room_allows_the_provider_call() -> None:
    config = default_registry().resolve(ModelRoute.TUTOR_DEFAULT)
    adapter = CountingProvider(token_count=1000)
    gateway, clock = build(adapter)
    projected = budget.project_cost_usd(config, input_tokens=1000, max_output_tokens=1000)

    result = gateway.complete(
        route=ModelRoute.TUTOR_DEFAULT,
        prompt=PROMPT,
        deadline=deadline_for(clock, 5000),
        max_output_tokens=1000,
        request_cost_budget_usd=projected,
        request_cost_spent_usd=Decimal("0.000000"),
    )

    assert result.estimated_cost_usd >= Decimal("0.000000")
    assert adapter.calls == 1


def test_request_budget_refusal_happens_before_provider_dispatch() -> None:
    config = default_registry().resolve(ModelRoute.TUTOR_DEFAULT)
    adapter = CountingProvider(token_count=1000)
    gateway, clock = build(adapter)
    projected = budget.project_cost_usd(config, input_tokens=1000, max_output_tokens=1000)

    with pytest.raises(GatewayError) as refusal:
        gateway.complete(
            route=ModelRoute.TUTOR_DEFAULT,
            prompt=PROMPT,
            deadline=deadline_for(clock, 5000),
            max_output_tokens=1000,
            request_cost_budget_usd=projected,
            request_cost_spent_usd=Decimal("0.000001"),
        )

    assert refusal.value.code is GatewayErrorCode.COST_CEILING_EXCEEDED
    assert adapter.calls == 0


def test_request_budget_exact_boundary_is_allowed() -> None:
    config = default_registry().resolve(ModelRoute.TUTOR_DEFAULT)
    adapter = CountingProvider(token_count=1000)
    gateway, clock = build(adapter)
    projected = budget.project_cost_usd(config, input_tokens=1000, max_output_tokens=1000)

    gateway.complete(
        route=ModelRoute.TUTOR_DEFAULT,
        prompt=PROMPT,
        deadline=deadline_for(clock, 5000),
        max_output_tokens=1000,
        request_cost_budget_usd=Decimal("0.020000"),
        request_cost_spent_usd=Decimal("0.002000"),
    )

    assert projected == Decimal("0.018000")
    assert adapter.calls == 1


def test_zero_request_budget_preserves_existing_route_only_semantics() -> None:
    adapter = CountingProvider(token_count=1000)
    gateway, clock = build(adapter)

    gateway.complete(
        route=ModelRoute.TUTOR_DEFAULT,
        prompt=PROMPT,
        deadline=deadline_for(clock, 5000),
        max_output_tokens=1000,
        request_cost_budget_usd=Decimal("0.000000"),
        request_cost_spent_usd=Decimal("1.000000"),
    )

    assert adapter.calls == 1


def test_an_already_expired_deadline_refuses_before_any_work() -> None:
    adapter = CountingProvider()
    gateway, clock = build(adapter)
    deadline = deadline_for(clock, 1000)
    clock.advance_ms(1001)

    with pytest.raises(GatewayError) as refusal:
        gateway.complete(route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline)

    assert refusal.value.code is GatewayErrorCode.DEADLINE_EXCEEDED
    assert adapter.calls == 0


# -- retry, bounded by policy and by the caller's deadline ---------------------------------------


class FlakyProvider(FakeProvider):
    """Fails a fixed number of times, then succeeds."""

    def __init__(self, code: GatewayErrorCode, failures: int) -> None:
        super().__init__()
        self._code = code
        self._remaining = failures
        self.calls = 0

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        self.calls += 1
        if self._remaining > 0:
            self._remaining -= 1
            raise GatewayError(self._code, "transient")
        return super().complete(request)


def test_a_transient_failure_is_retried_once_and_succeeds() -> None:
    adapter = FlakyProvider(GatewayErrorCode.PROVIDER_RATE_LIMITED, failures=1)
    gateway, clock = build(adapter)

    result = gateway.complete(
        route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 10000)
    )

    assert adapter.calls == 2
    assert result.attempts == 2


def test_retries_are_bounded() -> None:
    adapter = FlakyProvider(GatewayErrorCode.PROVIDER_UNAVAILABLE, failures=99)
    gateway, clock = build(adapter)

    with pytest.raises(GatewayError):
        gateway.complete(
            route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 60000)
        )

    assert adapter.calls == MAX_ATTEMPTS_PER_ROUTE


def test_a_timeout_is_cancelled_rather_than_retried() -> None:
    """Doc 04 §4. The deadline that was too short for the first attempt is shorter now."""
    adapter = FlakyProvider(GatewayErrorCode.PROVIDER_TIMEOUT, failures=1)
    gateway, clock = build(adapter)

    with pytest.raises(GatewayError) as failure:
        gateway.complete(
            route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 10000)
        )

    assert failure.value.code is GatewayErrorCode.PROVIDER_TIMEOUT
    assert adapter.calls == 1, "a timeout must not be retried"


def test_a_retry_is_not_attempted_when_the_deadline_cannot_accommodate_it() -> None:
    """A retry must be able to finish, not merely to start.

    Beginning work the deadline cannot accommodate spends the remaining budget and still fails,
    having also paid for the tokens the attempt consumed.
    """
    adapter = FlakyProvider(GatewayErrorCode.PROVIDER_RATE_LIMITED, failures=1)
    config = default_registry().resolve(ModelRoute.CI_FAKE)
    gateway, clock = build(adapter)

    # Enough to make one attempt, not enough for the backoff plus another full completion.
    deadline = deadline_for(clock, config.completion_target_p95_ms // 2)

    with pytest.raises(GatewayError):
        gateway.complete(route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline)

    assert adapter.calls == 1


def test_an_auth_failure_is_not_retried() -> None:
    adapter = FlakyProvider(GatewayErrorCode.PROVIDER_AUTH_ERROR, failures=1)
    gateway, clock = build(adapter)

    with pytest.raises(GatewayError) as failure:
        gateway.complete(
            route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 60000)
        )

    assert failure.value.code is GatewayErrorCode.PROVIDER_AUTH_ERROR
    assert adapter.calls == 1


# -- fallback eligibility (required test) ---------------------------------------------------------


def registry_with_fallback(primary: ModelRoute, fallback: ModelRoute | None) -> RouteRegistry:
    registry = default_registry()
    config = registry.resolve(primary)
    return registry.with_route(type(config)(**{**vars(config), "fallback_route": fallback}))


def test_no_fallback_is_taken_when_none_is_configured() -> None:
    """The V1 default. Doc 04 approves no pairing, so nothing should be substituted."""
    adapter = FlakyProvider(GatewayErrorCode.PROVIDER_UNAVAILABLE, failures=99)
    gateway, clock = build(adapter)

    with pytest.raises(GatewayError):
        gateway.complete(
            route=ModelRoute.CI_FAKE, prompt=PROMPT, deadline=deadline_for(clock, 60000)
        )


def test_an_approved_fallback_serves_the_request_and_is_recorded() -> None:
    """A fallback that is not recorded is worse than no fallback: the metadata would lie."""
    registry = registry_with_fallback(ModelRoute.ASSESSMENT_DEFAULT, ModelRoute.CI_FAKE)

    class FailPrimaryOnly(FakeProvider):
        def complete(self, request: ProviderRequest) -> ProviderResponse:
            if request.model != registry.resolve(ModelRoute.CI_FAKE).model:
                raise GatewayError(GatewayErrorCode.PROVIDER_UNAVAILABLE, "primary is down")
            return super().complete(request)

    gateway, clock = build(FailPrimaryOnly(), registry=registry)

    result = gateway.complete(
        route=ModelRoute.ASSESSMENT_DEFAULT,
        prompt=PROMPT,
        deadline=deadline_for(clock, 60000),
    )

    assert result.requested_route is ModelRoute.ASSESSMENT_DEFAULT
    assert result.route is ModelRoute.CI_FAKE, "the effective route must be the one that served it"
    assert result.fell_back


def test_fallback_cannot_bypass_the_remaining_request_budget() -> None:
    registry = default_registry()
    fallback = registry.resolve(ModelRoute.DIAGNOSTIC_DEFAULT)
    fallback = replace(
        fallback,
        output_cost_per_1k_usd=Decimal("0.050"),
    )
    registry = registry.with_route(fallback)
    primary = registry.resolve(ModelRoute.ASSESSMENT_DEFAULT)
    registry = registry.with_route(replace(primary, fallback_route=ModelRoute.DIAGNOSTIC_DEFAULT))

    class FailPrimaryOnly(FakeProvider):
        def __init__(self) -> None:
            super().__init__()
            self.calls = 0

        def count_input_tokens(self, model: str, messages: tuple[Message, ...]) -> int:  # noqa: ARG002
            return 1000

        def complete(self, request: ProviderRequest) -> ProviderResponse:
            self.calls += 1
            if request.model == primary.model:
                raise GatewayError(GatewayErrorCode.PROVIDER_TIMEOUT, "primary is down")
            return super().complete(request)

    adapter = FailPrimaryOnly()
    gateway, clock = build(adapter, registry=registry)
    primary_projected = budget.project_cost_usd(
        primary,
        input_tokens=1000,
        max_output_tokens=primary.max_output_tokens,
    )

    with pytest.raises(GatewayError) as refusal:
        gateway.complete(
            route=ModelRoute.ASSESSMENT_DEFAULT,
            prompt=PROMPT,
            deadline=deadline_for(clock, 60000),
            request_cost_budget_usd=primary_projected,
            request_cost_spent_usd=Decimal("0.000000"),
        )

    assert refusal.value.code is GatewayErrorCode.COST_CEILING_EXCEEDED
    assert adapter.calls == 1, "the fallback must not bypass the remaining request budget"


def test_a_budget_refusal_never_falls_back() -> None:
    """The rule that stops a transient failure becoming an unbounded bill."""
    registry = registry_with_fallback(ModelRoute.ASSESSMENT_DEFAULT, ModelRoute.CI_FAKE)
    primary = registry.resolve(ModelRoute.ASSESSMENT_DEFAULT)
    adapter = CountingProvider(token_count=primary.max_input_tokens + 1)
    gateway, clock = build(adapter, registry=registry)

    with pytest.raises(GatewayError) as refusal:
        gateway.complete(
            route=ModelRoute.ASSESSMENT_DEFAULT,
            prompt=PROMPT,
            deadline=deadline_for(clock, 60000),
        )

    assert refusal.value.code is GatewayErrorCode.TOKEN_CEILING_EXCEEDED
    assert adapter.calls == 0, "a budget refusal must not be retried on any route"


def test_an_auth_failure_never_falls_back() -> None:
    registry = registry_with_fallback(ModelRoute.ASSESSMENT_DEFAULT, ModelRoute.CI_FAKE)
    adapter = FlakyProvider(GatewayErrorCode.PROVIDER_AUTH_ERROR, failures=99)
    gateway, clock = build(adapter, registry=registry)

    with pytest.raises(GatewayError) as failure:
        gateway.complete(
            route=ModelRoute.ASSESSMENT_DEFAULT,
            prompt=PROMPT,
            deadline=deadline_for(clock, 60000),
        )

    assert failure.value.code is GatewayErrorCode.PROVIDER_AUTH_ERROR
    assert adapter.calls == 1


def test_a_fallback_is_refused_when_the_deadline_cannot_accommodate_it() -> None:
    """Doc 04 §4: on timeout, fall back only if the deadline still permits a complete attempt."""
    registry = registry_with_fallback(ModelRoute.ASSESSMENT_DEFAULT, ModelRoute.CI_FAKE)
    adapter = FlakyProvider(GatewayErrorCode.PROVIDER_TIMEOUT, failures=99)
    fallback_target = registry.resolve(ModelRoute.CI_FAKE).completion_target_p95_ms
    gateway, clock = build(adapter, registry=registry)

    deadline = deadline_for(clock, fallback_target + 50)
    clock.advance_ms(100)  # now too little left for a complete fallback attempt

    with pytest.raises(GatewayError) as failure:
        gateway.complete(route=ModelRoute.ASSESSMENT_DEFAULT, prompt=PROMPT, deadline=deadline)

    assert failure.value.code is GatewayErrorCode.PROVIDER_TIMEOUT


# -- rollback smoke (required test) ---------------------------------------------------------------
#
# MVP-1 is the first release, so the shipped register holds exactly one revision per template and
# there is genuinely nothing to roll back to. These tests therefore build a register with a second
# approved revision -- what the image will look like the first time a prompt is revised -- rather
# than asserting against a version string nobody ever reviewed. A rollback target that exists only
# in a test argument is the situation M1-ADR-008 is trying to prevent.

_TUTOR = PromptTemplateId.TUTOR_EXPLAIN
_V1 = "TUTOR_PROMPT_V1"
_V2 = "TUTOR_PROMPT_V2"
_V1_MESSAGES = (Message(role="system", content="The shipped tutor prompt."),)
_V2_MESSAGES = (Message(role="system", content="A revised tutor prompt."),)


def register_with_a_second_revision() -> PromptRegister:
    """Two revisions of one template, both stubs.

    The shipped register is not extended here: its V1 is the real tutor prompt and building it would
    tie these gateway tests to the tutor's context shape. What is under test is that moving the
    pointer moves both the identity and the messages, which stubs demonstrate exactly.
    """
    return register_of(
        PromptArtifact(template_id=_TUTOR, version=_V1, build=lambda **_: _V1_MESSAGES),
        PromptArtifact(template_id=_TUTOR, version=_V2, build=lambda **_: _V2_MESSAGES),
    )


def built_from(registry: RouteRegistry, register: PromptRegister) -> BuiltPrompt:
    """Builds whatever the route currently points at -- the path a real agent takes."""
    return register.build(_TUTOR, registry.resolve(ModelRoute.CI_FAKE).prompt_version_for(_TUTOR))


def test_a_prompt_rollback_changes_the_messages_and_not_only_the_label() -> None:
    """The property that makes a recorded prompt identity worth anything.

    Before this was enforced, a rollback moved the version recorded on every subsequent proposal and
    left the dispatched prompt byte-identical -- so the record said one prompt ran while another
    had. The Master Plan lists hallucinated provenance as an adversarial case for exactly this
    reason, and a rollback lever that produces it is worse than having no lever at all.
    """
    register = register_with_a_second_revision()
    shipped = default_registry()
    rolled = shipped.rolled_back(ModelRoute.CI_FAKE, register=register, prompts={_TUTOR: _V2})

    before = built_from(shipped, register)
    after = built_from(rolled, register)

    assert before.version != after.version, "the recorded identity must move"
    assert before.messages != after.messages, "and so must the prompt that is actually sent"
    assert after.messages == _V2_MESSAGES


def test_a_rollback_target_that_this_build_cannot_produce_is_refused() -> None:
    """An unbuildable target would relabel every later proposal while changing nothing that ran."""
    with pytest.raises(UnknownPromptVersionError, match="TUTOR_PROMPT_V0"):
        default_registry().rolled_back(
            ModelRoute.CI_FAKE,
            register=default_prompt_register(),
            prompts={_TUTOR: "TUTOR_PROMPT_V0"},
        )


def test_a_rollback_is_recorded_in_the_route_table_version() -> None:
    """Before and after an incident have to be distinguishable in the logs."""
    register = register_with_a_second_revision()
    rolled = default_registry().rolled_back(
        ModelRoute.CI_FAKE, register=register, prompts={_TUTOR: _V2}
    )

    assert default_registry().version == ROUTE_TABLE_VERSION
    assert rolled.version != ROUTE_TABLE_VERSION
    assert _V2 in rolled.version and ModelRoute.CI_FAKE.value in rolled.version


def test_rolling_back_a_prompt_changes_what_the_next_call_records() -> None:
    register = register_with_a_second_revision()
    gateway, clock = build()
    before = gateway.complete(
        route=ModelRoute.CI_FAKE,
        prompt=built_from(gateway.registry, register),
        deadline=deadline_for(clock, 5000),
    )

    rolled = gateway.registry.rolled_back(
        ModelRoute.CI_FAKE, register=register, prompts={_TUTOR: _V2}
    )
    after_gateway, after_clock = build(registry=rolled)
    after = after_gateway.complete(
        route=ModelRoute.CI_FAKE,
        prompt=built_from(after_gateway.registry, register),
        deadline=deadline_for(after_clock, 5000),
    )

    assert before.prompt_version == _V1
    assert after.prompt_version == _V2
    assert before.prompt_template_id is after.prompt_template_id is _TUTOR


def test_rollback_does_not_rewrite_already_recorded_metadata() -> None:
    """The property M1-ADR-008 is most concerned with.

    A result already handed to a caller records what actually produced it. If rolling a route back
    could change that, the only evidence of why an output looked the way it did would be destroyed
    at exactly the moment someone needs it.
    """
    register = register_with_a_second_revision()
    gateway, clock = build()
    recorded = gateway.complete(
        route=ModelRoute.CI_FAKE,
        prompt=built_from(gateway.registry, register),
        deadline=deadline_for(clock, 5000),
    )
    original_prompt = recorded.prompt_version
    original_model = recorded.model

    gateway.registry.rolled_back(ModelRoute.CI_FAKE, register=register, prompts={_TUTOR: _V2})

    assert recorded.prompt_version == original_prompt
    assert recorded.model == original_model


def test_model_and_prompt_roll_back_independently() -> None:
    """Coupling them would make the cheap remedy carry the expensive one's risk."""
    register = register_with_a_second_revision()
    registry = default_registry()
    before = registry.resolve(ModelRoute.CI_FAKE)

    prompt_only = registry.rolled_back(ModelRoute.CI_FAKE, register=register, prompts={_TUTOR: _V2})
    rolled = prompt_only.resolve(ModelRoute.CI_FAKE)

    assert rolled.prompt_version_for(_TUTOR) == _V2
    assert rolled.model == before.model, "a prompt rollback must not move the model"
    assert (
        rolled.prompt_versions[PromptTemplateId.ADAPTATION_PLAN]
        == before.prompt_versions[PromptTemplateId.ADAPTATION_PLAN]
    ), "nor may it move another template the same route serves"


def test_a_model_this_route_was_never_approved_for_is_refused() -> None:
    """Otherwise a model pin is a way to put unreviewed inference in front of learners."""
    with pytest.raises(RouteTableError, match="never approved"):
        default_registry().rolled_back(
            ModelRoute.CI_FAKE, register=default_prompt_register(), model="something-cheaper"
        )


def test_a_rollback_that_changes_nothing_is_rejected() -> None:
    """Silently succeeding would let an operator believe a remedy was applied when it was not."""
    with pytest.raises(ValueError, match="must change"):
        default_registry().rolled_back(ModelRoute.TUTOR_DEFAULT, register=default_prompt_register())


def test_a_route_cannot_roll_back_a_prompt_it_does_not_serve() -> None:
    """The adaptation route serves no tutor prompt, so there is no pointer to move."""
    register = register_with_a_second_revision()
    with pytest.raises(RouteTableError, match="nothing to roll back"):
        default_registry().rolled_back(
            ModelRoute.ADAPTATION_DEFAULT, register=register, prompts={_TUTOR: _V2}
        )


def test_the_registry_a_rollback_came_from_is_unchanged() -> None:
    register = register_with_a_second_revision()
    registry = default_registry()
    registry.rolled_back(ModelRoute.CI_FAKE, register=register, prompts={_TUTOR: _V2})
    assert registry.resolve(ModelRoute.CI_FAKE).prompt_version_for(_TUTOR) == _V1
