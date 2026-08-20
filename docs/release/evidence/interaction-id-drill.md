# InteractionId failure drill — M1-T04

Executed 2026-08-17 against running processes on the shared dev host.

The drill answers the question the whole correlation model exists to answer: **given only the
support code from an error screen, can the execution be located across both runtimes, as one
trace?** It follows the procedure written down in
[observability-correlation.md](../../architecture/observability-correlation.md) step by step, rather
than asserting on the pieces the procedure depends on.

Harness: [`scripts/validation/interaction-id-drill.sh`](../../../scripts/validation/interaction-id-drill.sh)

## Result

```
Step 1: obtain the support code from a failing request
  failing request returns a support code                  ok
  support code is a canonical lowercase UUIDv7            ok
    interactionId = 01a00d96-2950-7504-ae15-36392624fddc

Step 2: locate that request in the backend's structured logs
  backend log carries the support code                    ok
  backend log line is structured JSON                     ok
  backend log line carries a traceId to follow            ok
  backend log line leaks no credential                    ok
    traceId = 4b229b12a972838ee0df66cbb056bc84

Step 3: carry the context across the service boundary
  AI service preserves the support code                   ok
  AI service continues the backend's trace                ok

Step 4: locate the same action in the AI service's logs
  AI log carries the same support code                    ok
  AI log line carries the same traceId                    ok
  AI log line leaks no credential                         ok

Step 5: a malformed support code is refused, and the refusal is correlated
  AI service rejects a malformed interactionId            ok
  backend rejects a malformed interactionId identically   ok

Drill passed: a support code locates the action in both runtimes, as one trace.
```

## What the drill found

The first run did not look like this. Two defects surfaced, both of which had been present since
MVP-0 and neither of which any existing test could see.

### 1. The deployed backend emitted no structured logs at all

`deploy/compose.deploy.yml` never set `SPRING_PROFILES_ACTIVE`, so the deployed backend ran the
default profile. `logging.structured.format.console=logstash` is defined only in the `shared` and
`prod` profiles, so the running service used Spring's plain console pattern — which renders the
trace id and nothing else from MDC.

The documented diagnosis procedure could therefore not be carried out against the shared
environment at all. Step 1 of that procedure works (the support code is returned correctly, and
always was); step 2 returns nothing.

Fixed by setting `SPRING_PROFILES_ACTIVE: ${RAMALS_SPRING_PROFILES:-shared}`. Guarded by
`DeploymentLoggingTests`, which asserts against the topology file itself — the defect lived in
deployment configuration, where no application test would have reached it. Verified by perturbation:
removing the line, and setting the default to a profile with no structured encoder, both fail the
guard.

### 2. The one line emitted per request was the one line without the interactionId

With structured logging switched on, the request summary line appeared as JSON carrying `traceId`,
`spanId`, `operation`, `statusCode` and `durationMs` — and no `interactionId`.

`InteractionIdFilter` placed `interactionId`, `requestId` and `http.method` into MDC using
try-with-resources, and wrote its summary line from a `finally` block attached to that same `try`. A
try-with-resources closes its resources *before* the outer `finally` runs, so every one of those
entries had already been removed by the time the line was written. `traceId` and `spanId` survived
only because they are removed further down, by explicit `MDC.remove` calls.

The effect is precise and unfortunate: the single line logged for every HTTP request — exactly the
line the diagnosis procedure tells an engineer to search for — was the only line guaranteed not to
contain the support code.

Fixed by nesting the summary `finally` inside the resource block. Guarded by
`RequestLogCorrelationTests`, which asserts on the MDC captured on the log event rather than on
response headers. The existing correlation tests all asserted on headers, which were correct
throughout; that is why this survived. Verified by perturbation: restoring the original structure
fails four of its five assertions, and passes the fifth (`traceId` present) — matching the defect's
signature exactly.

## Negative control

The same drill was run unchanged against the deployed digest
(`sha256:29bd6bf8…`, which carries the profile fix but not the MDC fix):

```
Step 2: locate that request in the backend's structured logs
  backend log carries the support code                    FAILED
    no backend log line contains 01a00d96-59ff-7306-88c2-7b08562a0a17
  ...
Step 3: carry the context across the service boundary
  AI service continues the backend's trace                FAILED
    expected trace , got '726eacd57efef11be44e19c779ca3d60' -- two unrelated halves
```

This confirms both that the defects were real in a shipped artifact and that the drill detects them
rather than merely describing them.

## Scope

Step 3 performs the service-to-service hop itself rather than having the backend make it. The
backend does not call the AI service until M1-T05; what is under test here is the propagation
contract both sides implement, which is what T05 will depend on. The `→ model` leg of the plan's
"one trace spans Spring→Python→fake model" criterion is deferred to T05 with the model call.

> **Correction, recorded later.** The forward-looking part of that paragraph was wrong: Spring first
> called the AI service in **M1-T08**, not M1-T05, which built the Python-side gateway. The observed
> results above are unaffected — they record what the drill did, not what came next. Left in place
> with this note rather than edited, because an evidence document that is quietly rewritten stops
> being evidence.

## Reproducing

Both services must be running and reachable from wherever the drill executes:

```bash
BACKEND_URL=http://backend:8080 AI_URL=http://ai:8000 BACKEND_CONTAINER=... AI_CONTAINER=... bash scripts/validation/interaction-id-drill.sh
```
