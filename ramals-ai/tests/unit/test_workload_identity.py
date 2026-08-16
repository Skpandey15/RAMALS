"""Workload identity verification (M1-ADR-003).

These sign genuine RS256 tokens with a throwaway key and verify them through the real code path.
Mocking the verifier would prove only that the mock was called — and the M0-T18 drill exists
precisely because an inert protocol mapper passed every mock-JWT test in the suite.

The case that matters most is `test_learner_token_for_another_audience_is_rejected`: a token that is
correctly signed, unexpired and issued by the same realm, and must still be refused here.
"""

from __future__ import annotations

import time
from typing import Any, cast

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from jwt import PyJWKClient

from ramals_ai.config.settings import Environment, Settings
from ramals_ai.security.workload_identity import (
    WorkloadAuthenticationError,
    WorkloadTokenVerifier,
)

ISSUER = "http://keycloak:8080/realms/ramals"


@pytest.fixture(scope="module")
def signing_key() -> Any:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture
def settings() -> Settings:
    return Settings(environment=Environment.TEST, oidc_issuer=ISSUER)


class _StubJwkClient:
    """Returns a fixed public key, standing in for the realm's JWKS endpoint."""

    def __init__(self, key: Any) -> None:
        self._key = key

    def get_signing_key_from_jwt(self, token: str) -> Any:  # noqa: ARG002
        class _Key:
            key = self._key.public_key()

        return _Key()


def mint(
    signing_key: Any,
    *,
    audience: str | list[str] = "ramals-ai",
    azp: str = "ramals-core-workload",
    issuer: str = ISSUER,
    expires_in: int = 300,
    subject: str = "service-account-ramals-core-workload",
    extra: dict[str, Any] | None = None,
) -> str:
    now = int(time.time())
    claims: dict[str, Any] = {
        "iss": issuer,
        "sub": subject,
        "aud": audience,
        "azp": azp,
        "iat": now,
        "exp": now + expires_in,
        "typ": "Bearer",
    }
    claims.update(extra or {})
    pem = signing_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    return jwt.encode(claims, pem, algorithm="RS256")


@pytest.fixture
def verifier(settings: Settings, signing_key: Any) -> WorkloadTokenVerifier:
    stub = cast(PyJWKClient, _StubJwkClient(signing_key))
    return WorkloadTokenVerifier(settings, jwk_client=stub)


def test_valid_workload_token_is_accepted(
    verifier: WorkloadTokenVerifier, signing_key: Any
) -> None:
    identity = verifier.verify(mint(signing_key))
    assert identity.client_id == "ramals-core-workload"
    assert identity.subject == "service-account-ramals-core-workload"


def test_learner_token_for_another_audience_is_rejected(
    verifier: WorkloadTokenVerifier, signing_key: Any
) -> None:
    """The whole point of M1-ADR-003.

    This token is correctly signed by the realm, unexpired, and perfectly valid at the platform API.
    It must still be refused here, because it was not issued for this audience.
    """
    learner_token = mint(
        signing_key,
        audience="ramals-api",
        azp="ramals-web-ui",
        subject="a-real-learner",
    )
    with pytest.raises(WorkloadAuthenticationError):
        verifier.verify(learner_token)


def test_token_without_the_ai_audience_is_rejected(
    verifier: WorkloadTokenVerifier, signing_key: Any
) -> None:
    with pytest.raises(WorkloadAuthenticationError):
        verifier.verify(mint(signing_key, audience=["ramals-api", "account"]))


def test_multi_audience_token_including_ai_is_accepted(
    verifier: WorkloadTokenVerifier, signing_key: Any
) -> None:
    """Keycloak legitimately mints multiple audiences; presence is what matters."""
    identity = verifier.verify(mint(signing_key, audience=["account", "ramals-ai"]))
    assert identity.client_id == "ramals-core-workload"


def test_expired_token_is_rejected(verifier: WorkloadTokenVerifier, signing_key: Any) -> None:
    with pytest.raises(WorkloadAuthenticationError):
        verifier.verify(mint(signing_key, expires_in=-1))


def test_token_from_another_issuer_is_rejected(
    verifier: WorkloadTokenVerifier, signing_key: Any
) -> None:
    with pytest.raises(WorkloadAuthenticationError):
        verifier.verify(mint(signing_key, issuer="http://evil:8080/realms/ramals"))


def test_token_from_an_unexpected_client_is_rejected(
    verifier: WorkloadTokenVerifier, signing_key: Any
) -> None:
    """Audience alone would admit any client the realm mints an ramals-ai token for."""
    with pytest.raises(WorkloadAuthenticationError):
        verifier.verify(mint(signing_key, azp="some-other-service"))


def test_token_signed_by_a_different_key_is_rejected(verifier: WorkloadTokenVerifier) -> None:
    attacker_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    with pytest.raises(WorkloadAuthenticationError):
        verifier.verify(mint(attacker_key))


def test_unsigned_token_is_rejected(verifier: WorkloadTokenVerifier) -> None:
    """`alg: none` must never be accepted, however well-formed the claims are."""
    now = int(time.time())
    forged = jwt.encode(
        {
            "iss": ISSUER,
            "sub": "service-account-ramals-core-workload",
            "aud": "ramals-ai",
            "azp": "ramals-core-workload",
            "exp": now + 300,
        },
        key="",
        algorithm="none",
    )
    with pytest.raises(WorkloadAuthenticationError):
        verifier.verify(forged)


def test_token_missing_required_claims_is_rejected(
    verifier: WorkloadTokenVerifier, signing_key: Any
) -> None:
    pem = signing_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    without_subject = jwt.encode(
        {
            "iss": ISSUER,
            "aud": "ramals-ai",
            "azp": "ramals-core-workload",
            "exp": int(time.time()) + 300,
        },
        pem,
        algorithm="RS256",
    )
    with pytest.raises(WorkloadAuthenticationError):
        verifier.verify(without_subject)


def test_garbage_is_rejected(verifier: WorkloadTokenVerifier) -> None:
    with pytest.raises(WorkloadAuthenticationError):
        verifier.verify("not-a-token")
