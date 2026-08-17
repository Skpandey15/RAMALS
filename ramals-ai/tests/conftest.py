"""Shared test fixtures.

The meter provider is installed here, before any test module imports the application. OpenTelemetry
permits setting it exactly once per process — a per-test provider is silently ignored with
"Overriding of current MeterProvider is not allowed", and the counters keep pointing at whichever
provider won the race. Installing it at collection time and measuring deltas is the only arrangement
that actually observes what the code records.
"""

from __future__ import annotations

from collections.abc import Callable, Iterator

import pytest
from opentelemetry import metrics
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader, NumberDataPoint

_reader = InMemoryMetricReader()
metrics.set_meter_provider(MeterProvider(metric_readers=[_reader]))


def _total(name: str) -> int:
    data = _reader.get_metrics_data()
    total = 0
    for resource_metric in data.resource_metrics if data else []:
        for scope_metric in resource_metric.scope_metrics:
            for metric in scope_metric.metrics:
                if metric.name != name:
                    continue
                for point in metric.data.data_points:
                    # Counters record NumberDataPoints; anything else is a different instrument
                    # under the same name, which would make the reading meaningless.
                    assert isinstance(point, NumberDataPoint), f"{name} is not a counter"
                    total += int(point.value)
    return total


@pytest.fixture
def counter_delta() -> Iterator[Callable[[str], int]]:
    """Returns a function giving the increase in a counter since the test began.

    Counters are cumulative and process-wide, so an absolute reading would depend on which tests ran
    first. A delta does not.
    """
    baseline: dict[str, int] = {}

    def delta(name: str) -> int:
        if name not in baseline:
            raise AssertionError(f"call snapshot('{name}') before measuring its delta")
        return _total(name) - baseline[name]

    def snapshot(name: str) -> None:
        baseline[name] = _total(name)

    delta.snapshot = snapshot  # type: ignore[attr-defined]
    yield delta
