"""The Java MCP-2 read client (M2-ADR-031).

The only path from ramals-ai/LangGraph to Java's five governed MCP read-only capabilities. Exposes
exactly five explicit, typed methods -- never ``call_tool(name, arbitrary_args)`` to graph or model
code. Each call:

1. Acquires (or reuses a cached) ``ramals-ai-workload`` client-credentials token, audienced
   ``ramals-mcp`` -- never ``ramals-core-workload``, whose secret this process never holds.
2. Opens one short-lived Streamable HTTP session, with the workload token as ``Authorization`` and
   the interaction's own delegated learner-context credential as a dedicated header (never a tool
   argument -- see ``context.py``), consuming the interaction's own remaining deadline as the
   transport timeout, never an independent one.
3. Sends business lookup arguments only (``domainCode``/``attemptId``/``misconceptionId``/
   ``versionCode``) -- no ``learnerId``, no ``learnerRef``, no credential of any kind travels as a
   tool argument.
4. Validates the response against the exact typed Pydantic contract for that capability
   (``models.py``) and fails closed, as ``McpError``, on anything else -- a transport failure, a
   denial, a malformed payload, or an internal server error.

SDK path used (mcp==2.1.1, verified directly against the installed package, not guessed):
``mcp.client.streamable_http.streamable_http_client`` (note the SDK's real export name has
underscores throughout; the commonly cited ``streamablehttp_client`` does not exist in this
version), given a pre-configured ``httpx2.AsyncClient`` built by
``mcp.shared._httpx_utils.create_mcp_http_client`` carrying both credentials as fixed headers,
paired with ``mcp.ClientSession``. The high-level SDK's ``ClientSession.call_tool`` in this version
has no per-call header override (an open SDK feature request, not yet released) and a documented
``x-mcp-header``/``Mcp-Param-*`` mirroring mechanism that requires a matching tool-schema annotation
Java's MCP-2 schemas do not (and must not) carry. A fresh session is opened per interaction instead
-- exactly matching the delegated context's own per-interaction lifecycle (M2-ADR-031: never cached
globally, never reused across interactions), so no per-call header override is needed at all.
"""

from __future__ import annotations

import logging
import time
from typing import TypeVar

import httpx2
from mcp import ClientSession, types
from mcp.client.streamable_http import streamable_http_client
from mcp.shared._httpx_utils import create_mcp_http_client
from pydantic import BaseModel, ValidationError

from ramals_ai.config.settings import Settings
from ramals_ai.mcp.context import McpExecutionContext
from ramals_ai.mcp.errors import McpError, McpErrorCode
from ramals_ai.mcp.models import McpDiagnosticReport, McpLongitudinalReport, McpMasteryReport
from ramals_ai.security.mcp_workload_identity import McpWorkloadTokenProvider

logger = logging.getLogger(__name__)

#: Never a tool argument. Set once per interaction on the underlying HTTP client's fixed headers.
DELEGATED_CONTEXT_HEADER = "X-Ramals-Delegated-Context"

CURRENT_DOMAIN_REPORT = "diagnostics.current-domain-report"
ATTEMPT_REPORT = "diagnostics.attempt-report"
LONGITUDINAL_SUMMARY = "diagnostics.longitudinal-summary"
MISCONCEPTION_LONGITUDINAL_DETAIL = "diagnostics.misconception-longitudinal-detail"
MASTERY_CURRENT = "mastery.current"

#: Exactly the five capabilities this client may ever invoke -- a hardcoded, local literal set,
#: never derived from a remote `tools/list` response. Server advertisement is not authorization.
ALLOWLISTED_CAPABILITIES: frozenset[str] = frozenset(
    {
        CURRENT_DOMAIN_REPORT,
        ATTEMPT_REPORT,
        LONGITUDINAL_SUMMARY,
        MISCONCEPTION_LONGITUDINAL_DETAIL,
        MASTERY_CURRENT,
    }
)

# Denial reason codes Java's MCP-1/MCP-2 layers emit verbatim as CallToolResult text content
# (DelegatedLearnerContextException.Reason / McpAuthorizationException.Reason). Matched by exact
# string, never partially, so an unrecognized future reason falls through to the safe default.
_MISSING_REASONS = frozenset({"MISSING"})
_REJECTED_REASONS = frozenset(
    {
        "MALFORMED",
        "BAD_SIGNATURE",
        "WRONG_ISSUER",
        "WRONG_AUDIENCE",
        "EXPIRED",
        "NOT_YET_VALID",
        "UNKNOWN_KEY_ID",
        "MISSING_INTERACTION_ID",
        "MISSING_LEARNER_SCOPE",
        "MISSING_DOMAIN_SCOPE",
        "MISSING_CAPABILITIES",
        "INVALID_CAPABILITY_FORMAT",
    }
)
_CAPABILITY_DENIED_REASONS = frozenset(
    {
        "CAPABILITY_NOT_REGISTERED",
        "CAPABILITY_NOT_DELEGATED",
        "ATTEMPT_NOT_OWNED",
        "MISCONCEPTION_NOT_ACCESSIBLE",
        "LEARNER_SCOPE_RESOLUTION_FAILURE",
    }
)
_DOMAIN_MISMATCH_REASONS = frozenset({"DOMAIN_MISMATCH"})
_MALFORMED_REASONS = frozenset({"MALFORMED_REQUEST"})

_ResultModel = TypeVar("_ResultModel", bound=BaseModel)


class RamalsMcpReadClient:
    """The only path from ramals-ai/LangGraph to Java's MCP-2 read-only capabilities.

    One instance may be shared across interactions (it holds no per-interaction state itself --
    every call takes its own ``McpExecutionContext`` explicitly); the underlying workload token
    provider is what caches safely across interactions, exactly like Java's own
    ``WorkloadTokenProvider``.
    """

    def __init__(self, settings: Settings, token_provider: McpWorkloadTokenProvider) -> None:
        if not settings.mcp_enabled:
            raise McpError(McpErrorCode.MCP_DISABLED, "MCP is not enabled")
        self._url = settings.mcp_base_url.rstrip("/") + "/mcp"
        self._token_provider = token_provider

    async def current_domain_report(
        self, context: McpExecutionContext, *, domain_code: str
    ) -> McpDiagnosticReport:
        """H6 current-domain diagnostic report (M2-ADR-029)."""
        result = await self._call(context, CURRENT_DOMAIN_REPORT, {"domainCode": domain_code})
        return self._parse(CURRENT_DOMAIN_REPORT, result, McpDiagnosticReport)

    async def attempt_report(
        self, context: McpExecutionContext, *, attempt_id: str
    ) -> McpDiagnosticReport:
        """H6 attempt diagnostic report -- exact-attempt findings, never mastery, never H5."""
        result = await self._call(context, ATTEMPT_REPORT, {"attemptId": attempt_id})
        return self._parse(ATTEMPT_REPORT, result, McpDiagnosticReport)

    async def longitudinal_summary(
        self, context: McpExecutionContext, *, domain_code: str
    ) -> McpLongitudinalReport:
        """H7 longitudinal evidence domain summary (M2-ADR-030)."""
        result = await self._call(context, LONGITUDINAL_SUMMARY, {"domainCode": domain_code})
        return self._parse(LONGITUDINAL_SUMMARY, result, McpLongitudinalReport)

    async def misconception_longitudinal_detail(
        self, context: McpExecutionContext, *, misconception_id: str
    ) -> McpLongitudinalReport:
        """H7 single-misconception longitudinal detail -- present even when NO_BASELINE."""
        result = await self._call(
            context, MISCONCEPTION_LONGITUDINAL_DETAIL, {"misconceptionId": misconception_id}
        )
        return self._parse(MISCONCEPTION_LONGITUDINAL_DETAIL, result, McpLongitudinalReport)

    async def current_mastery(
        self, context: McpExecutionContext, *, domain_code: str, version_code: str
    ) -> McpMasteryReport:
        """Authoritative current mastery map -- read-only, never diagnostic confidence."""
        result = await self._call(
            context, MASTERY_CURRENT, {"domainCode": domain_code, "versionCode": version_code}
        )
        return self._parse(MASTERY_CURRENT, result, McpMasteryReport)

    # -- internal: never exposed to LangGraph/model code directly --------------------------------

    async def _call(
        self, context: McpExecutionContext, capability: str, arguments: dict[str, object]
    ) -> types.CallToolResult:
        if capability not in ALLOWLISTED_CAPABILITIES:
            # Defense in depth only -- every public method above names a hardcoded literal, so this
            # is reachable solely by a defect in this file, never by a caller's input.
            raise McpError(
                McpErrorCode.MCP_CAPABILITY_NOT_ALLOWLISTED,
                f"'{capability}' is not a locally allowlisted MCP capability",
            )

        remaining_ms = context.deadline.remaining_ms()
        if remaining_ms <= 0:
            raise McpError(
                McpErrorCode.MCP_DEADLINE_EXCEEDED,
                "the interaction deadline had already passed before the MCP call was attempted",
            )

        started_at = time.monotonic()
        try:
            token = await self._token_provider.get_token(timeout_s=remaining_ms / 1000.0)
        except McpError as failure:
            self._log(capability, "DENIED", failure.code.value, started_at)
            raise

        # Token acquisition itself spends real wall-clock time out of this same interaction budget
        # -- a cache miss can cost a full round trip to the identity provider. Recomputed rather
        # than reusing the pre-token figure, or the MCP transport call would be given an allowance
        # that already partly elapsed, and could run past the interaction's actual deadline while
        # believing it still had the original one.
        remaining_ms = context.deadline.remaining_ms()
        if remaining_ms <= 0:
            self._log(capability, "DENIED", McpErrorCode.MCP_DEADLINE_EXCEEDED.value, started_at)
            raise McpError(
                McpErrorCode.MCP_DEADLINE_EXCEEDED,
                "the interaction deadline passed while acquiring the MCP workload token",
            )

        headers = {
            "Authorization": f"Bearer {token}",
            DELEGATED_CONTEXT_HEADER: context.delegated_context_token,
            "X-Interaction-ID": context.interaction_id,
        }
        timeout = httpx2.Timeout(remaining_ms / 1000.0)

        try:
            async with (
                create_mcp_http_client(headers=headers, timeout=timeout) as http_client,
                streamable_http_client(self._url, http_client=http_client) as (read, write),
                ClientSession(read, write) as session,
            ):
                await session.initialize()
                result = await session.call_tool(capability, arguments)
        except httpx2.TimeoutException as failure:
            self._log(capability, "DENIED", McpErrorCode.MCP_DEADLINE_EXCEEDED.value, started_at)
            raise McpError(
                McpErrorCode.MCP_DEADLINE_EXCEEDED,
                "the MCP call did not complete within the deadline",
            ) from failure
        except httpx2.HTTPStatusError as failure:
            code = self._map_http_status(failure.response.status_code)
            self._log(capability, "DENIED", code.value, started_at)
            raise McpError(code, "the MCP transport refused the call") from failure
        except httpx2.HTTPError as failure:
            self._log(capability, "DENIED", McpErrorCode.MCP_TRANSPORT_ERROR.value, started_at)
            raise McpError(
                McpErrorCode.MCP_TRANSPORT_ERROR, "the MCP transport could not be reached"
            ) from failure
        except McpError:
            raise
        except Exception as failure:  # noqa: BLE001 - fail closed on anything unanticipated too
            # Never the raw exception message onward: an MCP/JSON-RPC parse failure can echo
            # response body content, which here could be a diagnostic/mastery payload.
            self._log(capability, "ERROR", McpErrorCode.MCP_SERVER_ERROR.value, started_at)
            raise McpError(
                McpErrorCode.MCP_SERVER_ERROR, "the MCP call failed unexpectedly"
            ) from failure

        if result.is_error:
            code = self._map_denial(result)
            self._log(capability, "DENIED", code.value, started_at)
            raise McpError(code, "the MCP call was refused")

        self._log(capability, "SUCCESS", None, started_at)
        return result

    def _parse(
        self, capability: str, result: types.CallToolResult, model: type[_ResultModel]
    ) -> _ResultModel:
        payload = result.structured_content
        if payload is None:
            raise McpError(
                McpErrorCode.MCP_INVALID_PAYLOAD, f"{capability} returned no structured content"
            )
        try:
            return model.model_validate(payload)
        except ValidationError as failure:
            # Never the payload itself -- it is learner-scoped diagnostic/mastery data.
            logger.warning(
                "MCP result failed schema validation",
                extra={
                    "operation": "mcp.result.invalid",
                    "capability": capability,
                    "outcome": "ERROR",
                },
            )
            raise McpError(
                McpErrorCode.MCP_RESULT_SCHEMA_MISMATCH,
                f"{capability} result did not match the expected contract",
            ) from failure

    @staticmethod
    def _map_http_status(status_code: int) -> McpErrorCode:
        if status_code in (401, 403):
            return McpErrorCode.MCP_WORKLOAD_AUTH_FAILED
        if status_code == 404:
            return McpErrorCode.MCP_UNKNOWN_CAPABILITY
        if status_code >= 500:
            return McpErrorCode.MCP_UNAVAILABLE
        return McpErrorCode.MCP_TRANSPORT_ERROR

    @staticmethod
    def _map_denial(result: types.CallToolResult) -> McpErrorCode:
        # Exact-token match, never substring containment: Java's own reason vocabulary has near
        # neighbors that share a prefix (e.g. the delegated-context validator's own "MALFORMED" vs.
        # the authorization layer's "MALFORMED_REQUEST") -- a substring check would misclassify one
        # as the other.
        tokens = {block.text for block in result.content if isinstance(block, types.TextContent)}
        if tokens & _MISSING_REASONS:
            return McpErrorCode.MCP_DELEGATED_CONTEXT_MISSING
        if tokens & _REJECTED_REASONS:
            return McpErrorCode.MCP_DELEGATED_CONTEXT_REJECTED
        if tokens & _DOMAIN_MISMATCH_REASONS:
            return McpErrorCode.MCP_DOMAIN_MISMATCH
        if tokens & _CAPABILITY_DENIED_REASONS:
            return McpErrorCode.MCP_CAPABILITY_DENIED
        if tokens & _MALFORMED_REASONS:
            return McpErrorCode.MCP_MALFORMED_INPUT
        return McpErrorCode.MCP_SERVER_ERROR

    @staticmethod
    def _log(capability: str, outcome: str, reason_code: str | None, started_at: float) -> None:
        # Never the raw workload token, the delegated-context token, learner PII, or the full
        # payload -- capability name, outcome, reason code, and latency only.
        latency_ms = int((time.monotonic() - started_at) * 1000)
        logger.info(
            "MCP capability call",
            extra={
                "operation": "mcp.capability.call",
                "capability": capability,
                "outcome": outcome,
                "mcpFailureCategory": reason_code,
                "latencyMs": latency_ms,
            },
        )


def build_read_client(settings: Settings) -> RamalsMcpReadClient:
    """One shared client + token provider for the process -- the token provider's own cache is what
    makes sharing safe; no per-interaction state lives on either object.

    Plain, synchronous construction: neither constructor performs I/O, so this needs no event loop
    and can be called directly from the synchronous application factory (``main.py``) at startup.
    """
    token_provider = McpWorkloadTokenProvider(settings)
    return RamalsMcpReadClient(settings, token_provider)
