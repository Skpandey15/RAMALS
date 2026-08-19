"""Structured logs must carry correlation and never carry secrets.

Both halves matter. Logs without correlation cannot answer "what happened to this learner's
request". Logs with a bearer token in them turn the log store into a credential store, and log
retention is usually far longer than token lifetime.
"""

from __future__ import annotations

import io
import json
import logging
import uuid
from typing import Any

import pytest
from fastapi.testclient import TestClient

from ramals_ai.config.settings import Environment, ModelRoute, Settings
from ramals_ai.main import create_app
from ramals_ai.telemetry.correlation import bind, reset
from ramals_ai.telemetry.logging import JsonFormatter, business_event


class _CapturedLogStream:
    """Redirects the configured JSON handler's stream so the emitted text can be inspected."""

    def __init__(self) -> None:
        self.text = ""
        self._buffer = io.StringIO()
        self._handler: logging.StreamHandler[Any] | None = None
        self._original: Any = None

    def __enter__(self) -> _CapturedLogStream:
        root = logging.getLogger()
        handlers = [h for h in root.handlers if isinstance(h, logging.StreamHandler)]
        if handlers:
            self._handler = handlers[0]
            self._original = self._handler.stream
            self._handler.stream = self._buffer
        return self

    def __exit__(self, *exc: object) -> None:
        if self._handler is not None:
            self.text = self._buffer.getvalue()
            self._handler.stream = self._original


def _record(message: str, **extra: object) -> logging.LogRecord:
    record = logging.LogRecord("ramals_ai.test", logging.INFO, __file__, 1, message, None, None)
    for key, value in extra.items():
        setattr(record, key, value)
    return record


def _format(record: logging.LogRecord) -> dict[str, object]:
    formatter = JsonFormatter(service="ramals-ai", environment="test")
    parsed: dict[str, object] = json.loads(formatter.format(record))
    return parsed


def test_log_lines_carry_correlation_from_the_request_context() -> None:
    """No call site has to remember to attach them, so none can forget."""
    interaction_id = str(uuid.uuid7())
    request_id = str(uuid.uuid4())
    tokens = bind(interaction_id, request_id)
    try:
        rendered = _format(_record("something happened"))
    finally:
        reset(tokens)

    assert rendered["interactionId"] == interaction_id
    assert rendered["requestId"] == request_id


def test_log_lines_outside_a_request_omit_correlation_rather_than_inventing_it() -> None:
    """A startup line has no interaction. An empty or fabricated id would be worse than absence."""
    rendered = _format(_record("ramals-ai starting"))
    assert "interactionId" not in rendered
    assert "requestId" not in rendered


def test_domain_context_passed_by_a_caller_is_preserved() -> None:
    rendered = _format(_record("call failed", operation="llm.call", errorCode="PROVIDER_TIMEOUT"))
    assert rendered["operation"] == "llm.call"
    assert rendered["errorCode"] == "PROVIDER_TIMEOUT"


def test_the_request_log_never_contains_the_authorization_header() -> None:
    """The middleware logs every request; it must log none of the credential.

    Captures the real handler output rather than inspecting the record, because a formatter that
    serialises the whole request would leak here and nowhere else.
    """
    app = create_app(Settings(environment=Environment.TEST))
    with TestClient(app) as client, _CapturedLogStream() as captured:
        client.get(
            "/internal/v1/capabilities",
            headers={
                "X-Interaction-ID": str(uuid.uuid7()),
                "Authorization": "Bearer super-secret-workload-token",
            },
        )

    assert "super-secret-workload-token" not in captured.text
    assert captured.text, "the request should have produced a log line to inspect"


def test_provider_credential_never_reaches_a_log_line() -> None:
    """Settings hold the key; its repr must not carry it into a crash dump or startup line."""
    settings = Settings(
        environment=Environment.TEST,
        ai_enabled=True,
        model_route=ModelRoute.TUTOR_DEFAULT,
        provider_api_key="super-secret-provider-key",
    )
    rendered = _format(_record("configuration resolved", settings=repr(settings)))
    assert "super-secret-provider-key" not in json.dumps(rendered)


def test_business_event_has_common_schema_and_redacts_sensitive_fields(
    caplog: pytest.LogCaptureFixture,
) -> None:
    interaction_id = str(uuid.uuid7())
    request_id = str(uuid.uuid4())
    tokens = bind(interaction_id, request_id)
    try:
        with caplog.at_level(logging.INFO, logger="ramals_ai.test"):
            business_event(
                logging.getLogger("ramals_ai.test"),
                level=logging.INFO,
                operation="agent.proposal.generated",
                message="proposal generated",
                fields={
                    "entityType": "AgentProposal",
                    "outcome": "SUCCESS",
                    "prompt": "do not log this",
                    "promptVersion": "tutor-v1",
                    "accessToken": "do not log this either",
                },
            )
        event = next(
            record for record in caplog.records if record.getMessage() == "proposal generated"
        )
        rendered = _format(event)
    finally:
        reset(tokens)

    assert rendered["operation"] == "agent.proposal.generated"
    assert rendered["entityType"] == "AgentProposal"
    assert rendered["outcome"] == "SUCCESS"
    assert rendered["prompt"] == "[REDACTED]"
    assert rendered["accessToken"] == "[REDACTED]"
    assert rendered["promptVersion"] == "tutor-v1"
    assert rendered["interactionId"] == interaction_id
    assert rendered["requestId"] == request_id
