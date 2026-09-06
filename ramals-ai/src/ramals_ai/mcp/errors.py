"""Normalized failure taxonomy for MCP-3's Java MCP client.

Mirrors ``gateway/errors.py``'s own design exactly, for the same reason: an agent cannot make a
sound decision about a failure it cannot name, and RAMALS already has one governed shape for
"stable code, explicit retry policy, no leaked detail" -- reusing it here rather than inventing a
second taxonomy is the point.

``retryable`` is the only policy flag MCP reads need (there is no "fallback route" concept for a
Java MCP read the way there is for a model route) -- a read-only capability call either succeeds,
fails permanently, or fails on a transport hiccup safe to retry once, tightly bounded, within the
caller's own remaining deadline.
"""

from __future__ import annotations

from enum import StrEnum


class McpErrorCode(StrEnum):
    """Stable failure codes. Callers branch on these, never on transport exception types."""

    # Configuration / startup-adjacent. Never retryable.
    MCP_DISABLED = "MCP_DISABLED"

    # Workload identity acquisition (this process's own client-credentials grant). Never retryable
    # here -- see McpWorkloadTokenProvider's own docstring for why.
    MCP_WORKLOAD_TOKEN_ACQUISITION_FAILED = "MCP_WORKLOAD_TOKEN_ACQUISITION_FAILED"  # noqa: S105 - a failure code, not a secret

    # Authentication/authorization refusals from Java's own MCP security chain or delegated-context
    # validator. Never retryable: a second attempt asks the same question and gets the same answer.
    MCP_WORKLOAD_AUTH_FAILED = "MCP_WORKLOAD_AUTH_FAILED"
    MCP_DELEGATED_CONTEXT_MISSING = "MCP_DELEGATED_CONTEXT_MISSING"
    MCP_DELEGATED_CONTEXT_REJECTED = "MCP_DELEGATED_CONTEXT_REJECTED"
    MCP_CAPABILITY_DENIED = "MCP_CAPABILITY_DENIED"
    MCP_DOMAIN_MISMATCH = "MCP_DOMAIN_MISMATCH"

    # This process's own local governance. Refused before any call to Java is ever attempted.
    MCP_CAPABILITY_NOT_ALLOWLISTED = "MCP_CAPABILITY_NOT_ALLOWLISTED"
    MCP_MALFORMED_INPUT = "MCP_MALFORMED_INPUT"

    # Deadline/transport. PROVIDER_TIMEOUT-shaped: not retried (the deadline that was too short is
    # shorter now), but distinguished from a hard transport failure for observability.
    MCP_DEADLINE_EXCEEDED = "MCP_DEADLINE_EXCEEDED"
    MCP_TRANSPORT_ERROR = "MCP_TRANSPORT_ERROR"
    MCP_UNAVAILABLE = "MCP_UNAVAILABLE"

    # The server answered, but not in a shape this process can trust.
    MCP_INVALID_PAYLOAD = "MCP_INVALID_PAYLOAD"
    MCP_RESULT_SCHEMA_MISMATCH = "MCP_RESULT_SCHEMA_MISMATCH"
    MCP_UNKNOWN_CAPABILITY = "MCP_UNKNOWN_CAPABILITY"

    # Java's own internal failure, reported without detail.
    MCP_SERVER_ERROR = "MCP_SERVER_ERROR"


_POLICY: dict[McpErrorCode, bool] = {
    # code: retryable
    McpErrorCode.MCP_DISABLED: False,
    McpErrorCode.MCP_WORKLOAD_TOKEN_ACQUISITION_FAILED: False,
    McpErrorCode.MCP_WORKLOAD_AUTH_FAILED: False,
    McpErrorCode.MCP_DELEGATED_CONTEXT_MISSING: False,
    McpErrorCode.MCP_DELEGATED_CONTEXT_REJECTED: False,
    McpErrorCode.MCP_CAPABILITY_DENIED: False,
    McpErrorCode.MCP_DOMAIN_MISMATCH: False,
    McpErrorCode.MCP_CAPABILITY_NOT_ALLOWLISTED: False,
    McpErrorCode.MCP_MALFORMED_INPUT: False,
    McpErrorCode.MCP_DEADLINE_EXCEEDED: False,
    # The only transport-level, safe-to-retry-once cases: a connection reset or a momentary
    # unavailability, never a request that already reached Java's own authorization/business logic.
    McpErrorCode.MCP_TRANSPORT_ERROR: True,
    McpErrorCode.MCP_UNAVAILABLE: True,
    McpErrorCode.MCP_INVALID_PAYLOAD: False,
    McpErrorCode.MCP_RESULT_SCHEMA_MISMATCH: False,
    McpErrorCode.MCP_UNKNOWN_CAPABILITY: False,
    McpErrorCode.MCP_SERVER_ERROR: False,
}


class McpError(Exception):
    """A Java MCP call failure, named and classified.

    Carries no raw token, no server response body, and no stack trace detail from the transport --
    provider/server error bodies routinely echo request content, which here could mean echoing a
    learner-scoped diagnostic/mastery payload into a log line.
    """

    def __init__(self, code: McpErrorCode, detail: str) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail
        self.retryable = _POLICY[code]


def policy_for(code: McpErrorCode) -> bool:
    """Exposed so tests can assert the table directly rather than by constructing every error."""
    return _POLICY[code]
