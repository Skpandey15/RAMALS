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
from decimal import Decimal

import pytest

from ramals_ai.config.settings import ModelRoute
from ramals_ai.gateway.budget import Deadline
from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.gateway import MAX_ATTEMPTS_PER_ROUTE, LLMGateway
from ramals_ai.gateway.providers.base import Message, ProviderRequest, ProviderResponse
from ramals_ai.gateway.providers.fake import FakeProvider
from ramals_ai.gateway.routes import RouteRegistry, default_registry

MESSAGES = (
    Message(role="system", content="You are a tutor."),
    Message(role="user", content="Explain Kafka partitioning."),
)


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
        route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 5000)
    )
    second = gateway.complete(
        route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 5000)
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
        route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 5000)
    )
    second = gateway.complete(
        route=ModelRoute.CI_FAKE, messages=other, deadline=deadline_for(clock, 5000)
    )

    assert first.text != second.text


def test_ci_fake_costs_nothing_end_to_end() -> None:
    gateway, clock = build()
    result = gateway.complete(
        route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 5000)
    )
    assert result.estimated_cost_usd == Decimal("0.000000")
    assert result.cost_string == "0.000000"


# -- metadata a proposal must carry ---------------------------------------------------------------


def test_the_result_records_the_configuration_that_produced_it() -> None:
    gateway, clock = build()
    config = default_registry().resolve(ModelRoute.CI_FAKE)

    result = gateway.complete(
        route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 5000)
    )

    assert result.route is ModelRoute.CI_FAKE
    assert result.model == config.model
    assert result.prompt_version == config.prompt_version
    assert result.route_table_version == default_registry().version
    assert result.attempts == 1
    assert not result.fell_back


def test_usage_is_reported() -> None:
    gateway, clock = build()
    result = gateway.complete(
        route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 5000)
    )
    assert result.input_tokens > 0
    assert result.output_tokens > 0
    assert result.latency_ms >= 0


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
            route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 5000)
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
            messages=MESSAGES,
            deadline=deadline_for(clock, 5000),
        )

    assert refusal.value.code is GatewayErrorCode.COST_CEILING_EXCEEDED
    assert adapter.calls == 0


def test_an_already_expired_deadline_refuses_before_any_work() -> None:
    adapter = CountingProvider()
    gateway, clock = build(adapter)
    deadline = deadline_for(clock, 1000)
    clock.advance_ms(1001)

    with pytest.raises(GatewayError) as refusal:
        gateway.complete(route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline)

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
        route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 10000)
    )

    assert adapter.calls == 2
    assert result.attempts == 2


def test_retries_are_bounded() -> None:
    adapter = FlakyProvider(GatewayErrorCode.PROVIDER_UNAVAILABLE, failures=99)
    gateway, clock = build(adapter)

    with pytest.raises(GatewayError):
        gateway.complete(
            route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 60000)
        )

    assert adapter.calls == MAX_ATTEMPTS_PER_ROUTE


def test_a_timeout_is_cancelled_rather_than_retried() -> None:
    """Doc 04 §4. The deadline that was too short for the first attempt is shorter now."""
    adapter = FlakyProvider(GatewayErrorCode.PROVIDER_TIMEOUT, failures=1)
    gateway, clock = build(adapter)

    with pytest.raises(GatewayError) as failure:
        gateway.complete(
            route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 10000)
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
        gateway.complete(route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline)

    assert adapter.calls == 1


def test_an_auth_failure_is_not_retried() -> None:
    adapter = FlakyProvider(GatewayErrorCode.PROVIDER_AUTH_ERROR, failures=1)
    gateway, clock = build(adapter)

    with pytest.raises(GatewayError) as failure:
        gateway.complete(
            route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 60000)
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
            route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 60000)
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
        messages=MESSAGES,
        deadline=deadline_for(clock, 60000),
    )

    assert result.requested_route is ModelRoute.ASSESSMENT_DEFAULT
    assert result.route is ModelRoute.CI_FAKE, "the effective route must be the one that served it"
    assert result.fell_back


def test_a_budget_refusal_never_falls_back() -> None:
    """The rule that stops a transient failure becoming an unbounded bill."""
    registry = registry_with_fallback(ModelRoute.ASSESSMENT_DEFAULT, ModelRoute.CI_FAKE)
    primary = registry.resolve(ModelRoute.ASSESSMENT_DEFAULT)
    adapter = CountingProvider(token_count=primary.max_input_tokens + 1)
    gateway, clock = build(adapter, registry=registry)

    with pytest.raises(GatewayError) as refusal:
        gateway.complete(
            route=ModelRoute.ASSESSMENT_DEFAULT,
            messages=MESSAGES,
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
            messages=MESSAGES,
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
        gateway.complete(route=ModelRoute.ASSESSMENT_DEFAULT, messages=MESSAGES, deadline=deadline)

    assert failure.value.code is GatewayErrorCode.PROVIDER_TIMEOUT


# -- rollback smoke (required test) ---------------------------------------------------------------


def test_rolling_back_a_prompt_changes_what_the_next_call_records() -> None:
    gateway, clock = build()
    before = gateway.complete(
        route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 5000)
    )

    rolled_back = gateway.registry.rolled_back(
        ModelRoute.CI_FAKE, prompt_version="CI_FAKE_PROMPT_V0"
    )
    after_gateway, after_clock = build(registry=rolled_back)
    after = after_gateway.complete(
        route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(after_clock, 5000)
    )

    assert before.prompt_version == "CI_FAKE_PROMPT_V1"
    assert after.prompt_version == "CI_FAKE_PROMPT_V0"


def test_rollback_does_not_rewrite_already_recorded_metadata() -> None:
    """The property M1-ADR-008 is most concerned with.

    A result already handed to a caller records what actually produced it. If rolling a route back
    could change that, the only evidence of why an output looked the way it did would be destroyed
    at exactly the moment someone needs it.
    """
    gateway, clock = build()
    recorded = gateway.complete(
        route=ModelRoute.CI_FAKE, messages=MESSAGES, deadline=deadline_for(clock, 5000)
    )
    original_prompt = recorded.prompt_version
    original_model = recorded.model

    gateway.registry.rolled_back(
        ModelRoute.CI_FAKE, prompt_version="CI_FAKE_PROMPT_V0", model="something-else"
    )

    assert recorded.prompt_version == original_prompt
    assert recorded.model == original_model


def test_model_and_prompt_roll_back_independently() -> None:
    """Coupling them would make the cheap remedy carry the expensive one's risk."""
    registry = default_registry()
    before = registry.resolve(ModelRoute.TUTOR_DEFAULT)

    prompt_only = registry.rolled_back(ModelRoute.TUTOR_DEFAULT, prompt_version="TUTOR_PROMPT_V0")
    rolled = prompt_only.resolve(ModelRoute.TUTOR_DEFAULT)

    assert rolled.prompt_version == "TUTOR_PROMPT_V0"
    assert rolled.model == before.model, "a prompt rollback must not move the model"


def test_a_rollback_that_changes_nothing_is_rejected() -> None:
    """Silently succeeding would let an operator believe a remedy was applied when it was not."""
    with pytest.raises(ValueError, match="must change"):
        default_registry().rolled_back(ModelRoute.TUTOR_DEFAULT)


def test_the_registry_a_rollback_came_from_is_unchanged() -> None:
    registry = default_registry()
    registry.rolled_back(ModelRoute.TUTOR_DEFAULT, prompt_version="TUTOR_PROMPT_V0")
    assert registry.resolve(ModelRoute.TUTOR_DEFAULT).prompt_version == "TUTOR_PROMPT_V1"
