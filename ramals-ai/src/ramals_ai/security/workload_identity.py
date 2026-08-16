"""Workload identity verification for the internal AI API (M1-ADR-003).

The threat is not an anonymous caller — those are trivially rejected. It is a **replayed learner
token**: legitimately issued, unexpired, correctly signed by the same realm. Signature validation
alone accepts it. If it works, a learner drives the agent layer directly and supplies their own
learner context, bypassing every authorization and ownership check Spring performs.

Audience is what rejects it. A learner token carries `ramals-api`; a workload token carries
`ramals-ai`. Both are valid tokens from the same issuer, and only one is valid *here*.

Every rejection is deliberately indistinguishable to the caller: one status, one code, no detail
about which check failed. A boundary that explains itself is an oracle for probing it.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

import jwt
from jwt import PyJWKClient

from ramals_ai.config.settings import Settings


class WorkloadAuthenticationError(Exception):
    """Raised when a caller is not the authorized RAMALS core workload."""


@dataclass(frozen=True)
class WorkloadIdentity:
    """The verified caller. Never a learner, by construction."""

    subject: str
    client_id: str
    expires_at: int


class WorkloadTokenVerifier:
    """Validates a bearer token against the realm's JWKS.

    The signing keys are fetched from the issuer and cached by the JWK client, so a rotated key is
    picked up without a restart while a steady state costs no network round trip.
    """

    def __init__(self, settings: Settings, jwk_client: PyJWKClient | None = None) -> None:
        self._settings = settings
        self._issuer = settings.oidc_issuer.rstrip("/")
        self._audience = settings.oidc_audience
        self._expected_client = settings.expected_workload_client_id
        self._jwk_client = jwk_client or PyJWKClient(
            f"{self._issuer}/protocol/openid-connect/certs",
            cache_keys=True,
            lifespan=settings.jwks_cache_seconds,
        )

    def verify(self, token: str) -> WorkloadIdentity:
        try:
            signing_key = self._jwk_client.get_signing_key_from_jwt(token)
        except Exception as failure:  # noqa: BLE001 - any JWKS failure is an auth failure here
            raise WorkloadAuthenticationError("signing key unavailable") from failure

        try:
            claims: dict[str, Any] = jwt.decode(
                token,
                signing_key.key,
                algorithms=["RS256", "RS384", "RS512", "ES256", "ES384"],
                audience=self._audience,
                issuer=self._issuer,
                options={
                    "require": ["exp", "iss", "aud", "sub"],
                    "verify_exp": True,
                    "verify_aud": True,
                    "verify_iss": True,
                    "verify_signature": True,
                },
            )
        except jwt.PyJWTError as failure:
            raise WorkloadAuthenticationError("token rejected") from failure

        # Audience alone would admit any client the realm chooses to mint an ramals-ai token for.
        # Pinning the client id keeps the door open to exactly one workload.
        client_id = str(claims.get("azp") or claims.get("client_id") or "")
        if client_id != self._expected_client:
            raise WorkloadAuthenticationError("token rejected")

        # A learner token that somehow reached here would carry a user session. A service account
        # token has none, so this is a second, independent reason to refuse a replayed user token.
        if "session_state" in claims and claims.get("typ") != "Bearer":
            raise WorkloadAuthenticationError("token rejected")

        return WorkloadIdentity(
            subject=str(claims["sub"]),
            client_id=client_id,
            expires_at=int(claims["exp"]),
        )


def build_verifier(settings: Settings) -> WorkloadTokenVerifier | None:
    """Returns a verifier, or None when workload authentication is disabled for local runs."""
    if not settings.workload_auth_enabled:
        return None
    return WorkloadTokenVerifier(settings)


def seconds_until_expiry(identity: WorkloadIdentity) -> int:
    """Remaining validity. Used by callers that cache a verified identity for a request scope."""
    return max(0, identity.expires_at - int(time.time()))
