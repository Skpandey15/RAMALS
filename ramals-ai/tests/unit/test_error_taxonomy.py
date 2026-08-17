"""Provider failures must normalize to a fixed taxonomy with the right handling policy.

An agent cannot make a sound decision about a failure it cannot name. More importantly, the two
flags each code carries are the actual policy: get ``fallback_eligible`` wrong on a budget refusal
and a ceiling stops being a ceiling.

These assert the mapping without LiteLLM installed, by matching exception class names. That is
deliberate -- it means CI verifies the taxonomy on every run rather than only where a provider
library happens to be present.
"""

from __future__ import annotations

import pytest

from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode, policy_for
from ramals_ai.gateway.providers.litellm_adapter import normalize_exception_name


@pytest.mark.parametrize(
    ("exception_name", "expected"),
    [
        ("Timeout", GatewayErrorCode.PROVIDER_TIMEOUT),
        ("APITimeoutError", GatewayErrorCode.PROVIDER_TIMEOUT),
        ("RateLimitError", GatewayErrorCode.PROVIDER_RATE_LIMITED),
        ("ServiceUnavailableError", GatewayErrorCode.PROVIDER_UNAVAILABLE),
        ("InternalServerError", GatewayErrorCode.PROVIDER_UNAVAILABLE),
        ("APIConnectionError", GatewayErrorCode.PROVIDER_UNAVAILABLE),
        ("AuthenticationError", GatewayErrorCode.PROVIDER_AUTH_ERROR),
        ("PermissionDeniedError", GatewayErrorCode.PROVIDER_AUTH_ERROR),
        ("BadRequestError", GatewayErrorCode.PROVIDER_INVALID_REQUEST),
        ("ContextWindowExceededError", GatewayErrorCode.TOKEN_CEILING_EXCEEDED),
    ],
)
def test_provider_exceptions_normalize(exception_name: str, expected: GatewayErrorCode) -> None:
    assert normalize_exception_name(exception_name) is expected


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (429, GatewayErrorCode.PROVIDER_RATE_LIMITED),
        (401, GatewayErrorCode.PROVIDER_AUTH_ERROR),
        (403, GatewayErrorCode.PROVIDER_AUTH_ERROR),
        (408, GatewayErrorCode.PROVIDER_TIMEOUT),
        (400, GatewayErrorCode.PROVIDER_INVALID_REQUEST),
        (422, GatewayErrorCode.PROVIDER_INVALID_REQUEST),
        (500, GatewayErrorCode.PROVIDER_UNAVAILABLE),
        (503, GatewayErrorCode.PROVIDER_UNAVAILABLE),
    ],
)
def test_unrecognised_exceptions_fall_back_to_http_semantics(
    status: int, expected: GatewayErrorCode
) -> None:
    assert normalize_exception_name("SomethingNewTheyShipped", status) is expected


def test_an_entirely_unknown_failure_is_treated_as_transient() -> None:
    """Defaulting to permanent would turn a provider's new transient class into a hard outage."""
    code = normalize_exception_name("CompletelyUnknownError")
    assert code is GatewayErrorCode.PROVIDER_UNAVAILABLE
    assert GatewayError(code, "x").retryable


@pytest.mark.parametrize(
    ("code", "retryable", "fallback_eligible"),
    [
        (GatewayErrorCode.PROVIDER_RATE_LIMITED, True, True),
        (GatewayErrorCode.PROVIDER_UNAVAILABLE, True, True),
        # Cancelled, not retried: the deadline that was too short is shorter now.
        (GatewayErrorCode.PROVIDER_TIMEOUT, False, True),
        # Retrying a misconfiguration floods the alert and delays the fix.
        (GatewayErrorCode.PROVIDER_AUTH_ERROR, False, False),
        (GatewayErrorCode.PROVIDER_INVALID_REQUEST, False, False),
        # Repair happens on the governed route; it is not an excuse to change models.
        (GatewayErrorCode.INVALID_STRUCTURED_OUTPUT, True, False),
        # Ceilings never escalate. This row is the reason the taxonomy exists.
        (GatewayErrorCode.TOKEN_CEILING_EXCEEDED, False, False),
        (GatewayErrorCode.COST_CEILING_EXCEEDED, False, False),
        (GatewayErrorCode.DEADLINE_EXCEEDED, False, False),
        (GatewayErrorCode.ROUTE_NOT_CONFIGURED, False, False),
    ],
)
def test_handling_policy_matches_the_adr(
    code: GatewayErrorCode, retryable: bool, fallback_eligible: bool
) -> None:
    assert policy_for(code) == (retryable, fallback_eligible)


def test_every_code_has_a_policy() -> None:
    """A code with no policy entry would raise a KeyError at the worst possible moment."""
    for code in GatewayErrorCode:
        assert policy_for(code) is not None


def test_no_provider_message_is_carried_into_the_error() -> None:
    """Provider error bodies echo the request, which here is a minimized learner context."""
    secret_context = "learner asked about KAFKA_PARTITIONING and scored 0.72"

    class BadRequestError(Exception):
        pass

    from ramals_ai.gateway.providers.litellm_adapter import LiteLLMProvider

    normalized = LiteLLMProvider()._normalize(BadRequestError(secret_context))

    assert secret_context not in str(normalized)
    assert normalized.code is GatewayErrorCode.PROVIDER_INVALID_REQUEST


def test_governance_refusals_are_distinguishable_from_provider_failures() -> None:
    """Different problems: one means callers are sending work we will not pay for."""
    assert GatewayError(GatewayErrorCode.COST_CEILING_EXCEEDED, "x").is_governance_refusal
    assert not GatewayError(GatewayErrorCode.PROVIDER_RATE_LIMITED, "x").is_governance_refusal
