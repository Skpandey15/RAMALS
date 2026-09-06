"""MCP-3 review requirement #14: an authorization/policy failure is never retryable; only a safe
transport-level transient failure is (tightly bounded, elsewhere, within the caller's deadline)."""

from __future__ import annotations

from ramals_ai.mcp.errors import McpErrorCode, policy_for

_NEVER_RETRYABLE = (
    McpErrorCode.MCP_DISABLED,
    McpErrorCode.MCP_WORKLOAD_TOKEN_ACQUISITION_FAILED,
    McpErrorCode.MCP_WORKLOAD_AUTH_FAILED,
    McpErrorCode.MCP_DELEGATED_CONTEXT_MISSING,
    McpErrorCode.MCP_DELEGATED_CONTEXT_REJECTED,
    McpErrorCode.MCP_CAPABILITY_DENIED,
    McpErrorCode.MCP_DOMAIN_MISMATCH,
    McpErrorCode.MCP_CAPABILITY_NOT_ALLOWLISTED,
    McpErrorCode.MCP_MALFORMED_INPUT,
    McpErrorCode.MCP_DEADLINE_EXCEEDED,
    McpErrorCode.MCP_INVALID_PAYLOAD,
    McpErrorCode.MCP_RESULT_SCHEMA_MISMATCH,
    McpErrorCode.MCP_UNKNOWN_CAPABILITY,
    McpErrorCode.MCP_SERVER_ERROR,
)

_SAFELY_RETRYABLE = (McpErrorCode.MCP_TRANSPORT_ERROR, McpErrorCode.MCP_UNAVAILABLE)


def test_authorization_and_policy_failures_are_never_retryable() -> None:
    for code in _NEVER_RETRYABLE:
        assert policy_for(code) is False, f"{code} must never be retried"


def test_only_transport_level_transient_failures_are_retryable() -> None:
    for code in _SAFELY_RETRYABLE:
        assert policy_for(code) is True, f"{code} should be safely retryable"


def test_every_error_code_has_an_explicit_policy_entry() -> None:
    for code in McpErrorCode:
        # Raises KeyError (failing the test) if a future code is added without a policy decision.
        policy_for(code)
