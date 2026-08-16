# Runbook: Investigate a UI-reported failure by interactionId

**Goal:** diagnose a learner- or admin-reported failure from its support code alone, without
reproducing it first. Every consequential request is correlated by an `interactionId`, and the UI
shows it as the **Support code** on any error banner.

## The correlation model

RAMALS uses three distinct identifiers; they answer different questions and must not be conflated.

| Identifier | Scope | Where it appears | Answers |
| --- | --- | --- | --- |
| `interactionId` | one logical user action (survives safe retries) | UI support code, `X-Interaction-ID` response header, every structured log line, `ApiProblem.interactionId`, `ledger.decision_record.interaction_id`, `audit.admin_activity.interaction_id`, `core.learning_session_transition.interaction_id` | "everything that happened for this action" |
| `traceId` | one HTTP request execution (W3C trace context) | `X-Trace-ID` response header, structured logs, the tracing backend | "the span waterfall for one attempt" |
| `spanId` | one operation within a trace | structured logs | "which component failed" |
| `requestId` | one HTTP request (RAMALS-local) | `X-Request-ID` response header, logs | de-duplicating retries |

An `interactionId` fans out to one or more `traceId`s (one per retry/request); a `traceId` narrows to
the failing `spanId`. This is the search path below.

## Steps

1. **Get the `interactionId`** from the learner's screenshot or ticket (the "Support code"), or from
   the failing response's `X-Interaction-ID` header / body.
2. **List every request in that action:** search the structured logs for the `interactionId` MDC
   field. Each matching line carries `traceId`, `spanId`, `http.method`, `statusCode`, `durationMs`,
   and — on failures — a stable `errorCode`.
   ```
   interactionId="019..." | select traceId, statusCode, errorCode, http.method
   ```
3. **Pick the failed request** (non-2xx `statusCode`, or an `errorCode`) and take its `traceId`.
4. **Open the trace** in the tracing backend by `traceId` and read the waterfall to the failing
   `spanId`: security filter, controller, application service, JDBC, or database.
5. **Classify the failure** with the metrics/dashboards: `ramals_api_errors_total{code="..."}` and
   `http_server_requests_seconds_count{status="..."}` show whether this class of failure is
   widespread or isolated. HTTP `409` indicates an optimistic-retry conflict; `429` indicates rate
   limiting; `4xx` indicates a client/authorization problem; `5xx` indicates a server/DB fault.
6. **For a learning decision** (not an infrastructure fault), read the provenance keyed by the same
   `interactionId`: `ledger.decision_record` (the exact snapshot, evidence set, and policy versions
   behind a recommendation) and `ledger.mastery_snapshot`. For a privileged content operation, read
   `audit.admin_activity`.
7. **Record the root cause** and add a regression test before closing.

## Forced-failure discoverability

Failures are discoverable at each layer without reproduction:

- **Security** (401/403): the request-completion log records `statusCode` with the `interactionId`;
  `ramals_api_errors_total{code="ACCESS_DENIED"}` and `http_server_requests{status="401|403"}`
  increment.
- **Service** (404/422/409): the handled `errorCode` is logged and counted
  (`ramals_api_errors_total{code=...}`); the response carries the `interactionId`.
- **Database** (5xx): `DATABASE_OPERATION_FAILED` is logged with the cause and counted; the response
  detail is generic (see redaction).

## Trace sampling and redaction policy

- **Sampling:** MVP-0 samples all traces (`RAMALS_TRACE_SAMPLE=1.0`) so every failure is traceable.
  Production lowers this via `RAMALS_TRACE_SAMPLE`; error-path traces should remain retained where
  the backend supports tail-based sampling.
- **Redaction — never emitted to logs, spans, metrics, or error responses:**
  - bearer/access/refresh tokens, passwords, client secrets, private keys;
  - answer keys and per-item correctness;
  - learner free-text and other PII;
  - raw SQL, stack traces, database hostnames, or other internals in client-facing responses.
- Error responses expose only a stable `code`, a generic `detail`, and the safe correlation ids
  (`interactionId`, `traceId`). Trace and span ids are opaque, safe identifiers.
- Metrics are low-cardinality (stable `code`/`status`/`uri` tags only) — no ids, subjects, or PII.
