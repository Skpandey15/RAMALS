"""OpenTelemetry wiring for the AI execution plane.

The Python side **continues** the trace Spring started rather than beginning its own. That is the
whole point of propagating W3C trace context: one learner action produces one trace across both
runtimes, so a support code leads to a waterfall rather than to two unrelated halves.

No exporter is configured here. Trace *context* is mandatory (Doc 06 §6); shipping spans somewhere
is a deployment concern, and hardcoding a collector endpoint would make the service unstartable
wherever that collector is absent.
"""

from __future__ import annotations

from opentelemetry import metrics, trace
from opentelemetry.propagate import extract, inject
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider

from ramals_ai.config.settings import Settings

_TRACER_NAME = "ramals-ai"

# Doc 06 §4. Emitted through the OTel metrics API so they reach whatever exporter an environment
# configures; with no meter provider these are no-ops rather than a startup failure.
_meter = metrics.get_meter(_TRACER_NAME)

correlation_missing = _meter.create_counter(
    "ramals.ai.correlation.missing",
    description="Requests that arrived without an interactionId the caller was expected to supply",
)
correlation_invalid = _meter.create_counter(
    "ramals.ai.correlation.invalid",
    description="Requests rejected because the supplied interactionId was not a canonical UUIDv7",
)
trace_context_missing = _meter.create_counter(
    "ramals.ai.trace.context.missing",
    description="Requests that arrived without W3C trace context, breaking the cross-service trace",
)


def configure_tracing(settings: Settings) -> None:
    """Installs a tracer provider once, tagged with the service identity."""
    if isinstance(trace.get_tracer_provider(), TracerProvider):
        return
    resource = Resource.create(
        {
            "service.name": settings.service_name,
            "service.version": settings.service_version,
            "deployment.environment": settings.environment.value,
        }
    )
    trace.set_tracer_provider(TracerProvider(resource=resource))


def tracer() -> trace.Tracer:
    return trace.get_tracer(_TRACER_NAME)


def context_from_headers(headers: dict[str, str]) -> object:
    """Extracts W3C trace context so this service's spans join the caller's trace."""
    return extract(headers)


def headers_with_context(headers: dict[str, str] | None = None) -> dict[str, str]:
    """Injects the current trace context for an outbound call.

    Used from M1-T05 when the gateway calls a provider: without this the model call becomes an
    orphan trace and the waterfall stops exactly where it gets interesting.
    """
    carrier = dict(headers or {})
    inject(carrier)
    return carrier


def current_trace_id() -> str:
    """The 32-hex trace id, or empty when nothing is being traced."""
    context = trace.get_current_span().get_span_context()
    return format(context.trace_id, "032x") if context.is_valid else ""


def current_span_id() -> str:
    context = trace.get_current_span().get_span_context()
    return format(context.span_id, "016x") if context.is_valid else ""


__all__ = [
    "configure_tracing",
    "context_from_headers",
    "correlation_invalid",
    "correlation_missing",
    "current_span_id",
    "current_trace_id",
    "headers_with_context",
    "trace_context_missing",
    "tracer",
]
