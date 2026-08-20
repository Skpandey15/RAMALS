# M1-T14 resilience, latency and cost evidence

Status: complete for the bounded MVP-1 AI-plane slice.

## Implemented controls

- The request `interactionClass` is carried from every agent envelope into `AgentState` and then into
  the governed gateway call.
- `ramals.ai.latency` records completed model-call latency in milliseconds by interaction class and
  effective route. `ramals.ai.cost` records actual provider-reported cost in USD with the same bounded
  labels. A fallback is therefore measured under the route that served the request.
- Gateway token counts, cached input counts, output counts, actual cost and latency are accumulated
  across bounded graph calls and emitted in the proposal `usage` object.
- The graph checks its model-call ceiling immediately before gateway dispatch. A call that would
  exceed the ceiling cannot contact a provider; the existing post-call check remains as a defensive
  invariant.
- The Java learning-platform clients apply the caller's remaining deadline to both connect and read
  transport timeouts. Tutor, adaptation and assessment calls use the same guard; assessment
  workload-token acquisition remains inside the single deadline scope, so identity latency cannot
  renew the model-call budget.

## Verification

The Python AI-plane suite covers the pre-dispatch ceiling, class propagation, accumulated usage and
class/effective-route histogram labels. The complete M1-T14 checks are:

```text
python -m pytest
python -m ruff check src tests
python -m mypy src tests

cd learning-platform
../gradlew.bat :learning-platform:check --no-daemon
```

The Java check completed successfully, including unit, governance, architecture, integration and
JaCoCo tasks. Focused deadline tests cover timeout clamping, tutor deadline normalization and the
assessment token-plus-proposal shared budget.

R1 remains open on the release board. This evidence establishes instrumentation and enforcement, not
a calibrated latency or throughput claim from the authoritative fixed-spec environment.
