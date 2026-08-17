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
from typing import Any

from ramals_ai.telemetry.correlation import current_interaction_id, current_request_id
from ramals_ai.telemetry.tracing import current_span_id, current_trace_id

# Attributes LogRecord always carries. Anything else a caller attaches via `extra` is domain context
# worth emitting, so allow-listing would silently drop it; deny-list instead.
_RESERVED = frozenset(logging.LogRecord("", 0, "", 0, "", None, None).__dict__) | {
    "message",
    "asctime",
    "taskName",
}


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

        if record.exc_info:
            payload["exceptionType"] = record.exc_info[0].__name__ if record.exc_info[0] else None
            payload["stackTrace"] = self.formatException(record.exc_info)

        for key, value in record.__dict__.items():
            if key not in _RESERVED and not key.startswith("_"):
                payload[key] = value

        return json.dumps(payload, default=str)


def configure_logging(service: str, environment: str, level: str) -> None:
    """Installs the JSON formatter as the only root handler."""
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter(service=service, environment=environment))

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())
