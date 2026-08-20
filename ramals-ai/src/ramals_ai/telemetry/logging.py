"""Structured JSON logging.

Matches the MVP-0 log contract so a single query spans both runtimes. Correlation fields are
attached to every record from the request context rather than passed by each call site: a log line
that happens to omit them is exactly the line you need when something has gone wrong.

Never log bearer tokens, provider keys, prompt text or learner content.
"""

from __future__ import annotations

import json
import logging
import sys
from collections.abc import Mapping
from typing import Any

from ramals_ai.telemetry.correlation import (
    current_agent_run_id,
    current_interaction_id,
    current_proposal_id,
    current_request_id,
    current_tool_call_id,
)
from ramals_ai.telemetry.tracing import current_span_id, current_trace_id

# Attributes LogRecord always carries. Anything else a caller attaches via `extra` is domain context
# worth emitting, so allow-listing would silently drop it; deny-list instead.
_RESERVED = frozenset(logging.LogRecord("", 0, "", 0, "", None, None).__dict__) | {
    "message",
    "asctime",
    "taskName",
}
_MAX_VALUE_LENGTH = 256


def safe_log_value(key: str, value: object) -> object:
    """Return a bounded, non-sensitive value for a structured log field."""
    if value is None:
        return None
    normalized = key.lower()
    if (
        "password" in normalized
        or "secret" in normalized
        or "authorization" in normalized
        or "api_key" in normalized
        or "apikey" in normalized
        or normalized == "token"
        or normalized.endswith("token")
        or normalized
        in {
            "prompt",
            "raw_prompt",
            "answer",
            "raw_answer",
            "content",
            "raw_content",
            "full_content",
            "output",
            "raw_output",
            "llm_output",
        }
    ):
        return "[REDACTED]"
    if isinstance(value, (bool, int, float)):
        return value
    rendered = str(value)
    return rendered if len(rendered) <= _MAX_VALUE_LENGTH else rendered[:_MAX_VALUE_LENGTH] + "…"


def business_event(
    logger: logging.Logger,
    *,
    level: int,
    operation: str,
    message: str,
    fields: Mapping[str, object] | None = None,
    exception: BaseException | None = None,
) -> None:
    """Emit one governed business event with the common schema.

    Correlation IDs are added by ``JsonFormatter`` from ContextVars, so callers cannot accidentally
    emit an event with a different request context. Exceptions are opt-in and reserved for
    unexpected technical failures; expected business rejections should not carry stack traces.
    """
    extra: dict[str, object] = {
        "operation": safe_log_value("operation", operation),
    }
    if fields:
        extra.update(
            (key, safe_log_value(key, value))
            for key, value in fields.items()
            if key not in _RESERVED and key not in {"service", "environment", "level", "message"}
        )
    if exception is None:
        logger.log(level, message, extra=extra)
    else:
        logger.error(
            message,
            extra=extra,
            exc_info=(type(exception), exception, exception.__traceback__),
        )


class JsonFormatter(logging.Formatter):
    """Renders one log record as a single JSON object."""

    def __init__(self, service: str, environment: str) -> None:
        super().__init__()
        self._service = service
        self._environment = environment

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "service": self._service,
            "environment": self._environment,
            "logger": record.name,
            "message": record.getMessage(),
        }
        # Sourced from the request context, so no call site can forget them.
        interaction_id = current_interaction_id()
        if interaction_id:
            payload["interactionId"] = interaction_id
        request_id = current_request_id()
        if request_id:
            payload["requestId"] = request_id
        trace_id = current_trace_id()
        if trace_id:
            payload["traceId"] = trace_id
            payload["spanId"] = current_span_id()

        # Agent correlation (Observability HLD §9). Emitted only inside a run or a tool call, so an
        # ordinary request log line is not padded with empty agent fields -- and a line that does
        # carry them was genuinely produced inside that run.
        agent_run_id = current_agent_run_id()
        if agent_run_id:
            payload["agentRunId"] = agent_run_id
            payload["proposalId"] = current_proposal_id()
        tool_call_id = current_tool_call_id()
        if tool_call_id:
            payload["toolCallId"] = tool_call_id

        if record.exc_info:
            payload["exceptionType"] = record.exc_info[0].__name__ if record.exc_info[0] else None
            payload["stackTrace"] = self.formatException(record.exc_info)

        for key, value in record.__dict__.items():
            if key not in _RESERVED and not key.startswith("_") and key not in payload:
                payload[key] = safe_log_value(key, value)

        return json.dumps(payload, default=str)


def configure_logging(service: str, environment: str, level: str) -> None:
    """Installs the JSON formatter as the only root handler."""
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter(service=service, environment=environment))

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())
