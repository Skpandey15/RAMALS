"""Outgoing workload identity for the reverse direction MCP-3 introduces (M2-ADR-031).

``workload_identity.py`` (M1-ADR-003) answers the question this module does not: it *verifies* a
caller's workload token, for the direction Spring calls in as ``ramals-core-workload``/
``aud=ramals-ai``. This module is the reverse: ``ramals-ai`` is the *caller*, and it must
authenticate to Java's MCP transport as a distinct identity, ``ramals-ai-workload``, audienced
``ramals-mcp`` -- never the ``ramals-core-workload`` credential, whose secret this process never
holds and never will (that client authenticates the opposite direction).

Mirrors Java's own ``WorkloadTokenProvider`` (M1-ADR-003's outgoing side, Spring calling
``ramals-ai``): a shared, cached, client-credentials token, refreshed ahead of expiry rather than on
every call, so many MCP reads across many interactions do not each acquire a fresh credential from
the identity provider.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

import anyio
import httpx2

from ramals_ai.config.settings import Settings
from ramals_ai.mcp.errors import McpError, McpErrorCode

# Refresh this long before expiry, so a token is not spent at the moment it is presented -- the same
# margin WorkloadTokenProvider.java uses for the same reason.
_REFRESH_MARGIN_SECONDS = 10.0


@dataclass(frozen=True)
class _CachedToken:
    token: str
    usable_until: float

    def usable_now(self, *, clock: float) -> bool:
        return clock < self.usable_until


class McpWorkloadTokenProvider:
    """Acquires and caches the ``ramals-ai-workload`` client-credentials token.

    Never constructed with, and never touches, ``ramals-core-workload``'s own secret -- that
    credential belongs exclusively to Java's own outgoing call to ``ramals-ai`` (M1-ADR-003) and is
    never configured into this process at all (``Settings`` here has no field for it).
    """

    def __init__(self, settings: Settings) -> None:
        if not settings.mcp_enabled:
            raise McpError(McpErrorCode.MCP_DISABLED, "MCP is not enabled")
        self._token_url = settings.mcp_workload_token_url
        self._client_id = settings.mcp_workload_client_id
        self._client_secret = settings.mcp_workload_client_secret
        self._audience = settings.mcp_workload_audience
        self._lock = anyio.Lock()
        self._cached: _CachedToken | None = None

    async def get_token(self, *, timeout_s: float) -> str:
        """Returns a cached, still-usable token, or acquires and caches a fresh one.

        ``timeout_s`` bounds a real acquisition against the *caller's own remaining interaction
        budget* -- never a fixed, independent value -- because token acquisition is part of the MCP
        invocation's own budget, not a side channel exempt from it. The cached path never looks at
        it: a cache hit costs no network call, so there is nothing here for a deadline to bound.

        Never retried here on failure -- token acquisition failure is reported as
        ``MCP_WORKLOAD_TOKEN_ACQUISITION_FAILED`` and it is the caller's own bounded-retry policy
        (never this provider's) that decides whether to try again within the interaction's deadline.
        """
        cached = self._cached
        if cached is not None and cached.usable_now(clock=time.monotonic()):
            return cached.token

        if timeout_s <= 0:
            # Fails closed before ever constructing an httpx timeout: httpx2.Timeout(0) means "no
            # timeout" to the transport, not "no time left" -- reaching the network with either
            # value here would spend a real request against a budget that has already run out.
            raise McpError(
                McpErrorCode.MCP_DEADLINE_EXCEEDED,
                "no time remains in the interaction deadline to acquire an MCP workload token",
            )

        async with self._lock:
            cached = self._cached
            if cached is not None and cached.usable_now(clock=time.monotonic()):
                return cached.token
            return await self._fetch(timeout_s=timeout_s)

    async def _fetch(self, *, timeout_s: float) -> str:
        try:
            async with httpx2.AsyncClient(timeout=httpx2.Timeout(timeout_s)) as client:
                response = await client.post(
                    self._token_url,
                    data={
                        "grant_type": "client_credentials",
                        "client_id": self._client_id,
                        "client_secret": self._client_secret,
                        "audience": self._audience,
                    },
                    headers={"Content-Type": "application/x-www-form-urlencoded"},
                )
                response.raise_for_status()
                body = response.json()
        except httpx2.HTTPError as failure:
            # Never the URL, the response body, or the secret -- an identity-provider failure detail
            # belongs in the identity provider's own logs, not in ramals-ai's.
            raise McpError(
                McpErrorCode.MCP_WORKLOAD_TOKEN_ACQUISITION_FAILED,
                "the MCP workload identity provider could not be reached",
            ) from failure

        token = body.get("access_token")
        if not isinstance(token, str) or not token:
            raise McpError(
                McpErrorCode.MCP_WORKLOAD_TOKEN_ACQUISITION_FAILED,
                "the identity provider returned no access token",
            )
        expires_in = body.get("expires_in")
        expires_in_seconds = float(expires_in) if isinstance(expires_in, (int, float)) else 60.0
        usable_until = time.monotonic() + max(1.0, expires_in_seconds - _REFRESH_MARGIN_SECONDS)
        self._cached = _CachedToken(token=token, usable_until=usable_until)
        return token
