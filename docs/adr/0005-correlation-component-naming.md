# ADR 0005: Correlation components keep repository-idiomatic names

- **Status:** Accepted
- **Date:** 2026-08-16
- **Relates to:** Implementation Master Plan §16, M0-T03, M0-T23

## Context

Master Plan §16 lists a set of standard Spring and frontend components for correlation:

```
observability/  InteractionIdFilter.java  InteractionIdContext.java  CorrelationHeaders.java
                ProblemDetailsFactory.java  LoggingMdcConfiguration.java  TraceResponseFilter.java
                ObservabilityConstants.java
frontend/       interaction-id.ts  api-client.ts  error-support-code.ts
```

The implementation provides every one of those capabilities, but five of the seven Java files and
all three frontend files carry different names. A conformance audit flagged this as a deviation from
the plan, and M0-T23's Definition of Done requires that the review find *no undocumented*
deviations.

## Decision

Keep the implemented names. Record the mapping here rather than renaming files to match the plan.

| Master Plan §16 | Implemented as | Note |
| --- | --- | --- |
| `InteractionIdFilter.java` | `observability/InteractionIdFilter.java` | same |
| `CorrelationHeaders.java` | `observability/CorrelationHeaders.java` | same; also holds the header-name constants |
| `InteractionIdContext.java` | `observability/CorrelationContext.java` | holds interactionId, traceId and requestId, not interactionId alone |
| `ProblemDetailsFactory.java` | `observability/ApiProblem.java` + `ApiExceptionHandler.java` | the record and the advice that builds it |
| `LoggingMdcConfiguration.java` | inside `InteractionIdFilter` | MDC is populated where the context is established |
| `TraceResponseFilter.java` | `observability/TraceContextAccessor` + `InteractionIdFilter` | trace ids are read and echoed on the existing filter pass |
| `ObservabilityConstants.java` | `observability/CorrelationHeaders.java` | one constants holder rather than two |
| `interaction-id.ts` | `web-ui/src/platform/correlation.ts` | |
| `api-client.ts` | `web-ui/src/platform/apiClient.ts` | |
| `error-support-code.ts` | `web-ui/src/components/ErrorBanner.tsx` | the support code is rendered where the error is shown |

## Rationale

The §16 list is a reference shape for the correlation mechanism, not an interface contract: nothing
outside the application imports these names, and no test, document or deployment artefact depends on
them. Renaming to match the plan would touch a lot of files, invalidate existing review history and
buy no behaviour.

Three of the differences are also genuine simplifications rather than arbitrary renames.
`CorrelationContext` carries the full correlation triple, so naming it after `interactionId` alone
would be misleading. Splitting MDC population into a separate configuration class would separate it
from the filter that establishes the context it projects, creating an ordering dependency between
two classes that currently cannot get out of step. A second `TraceResponseFilter` would add a filter
pass to set a header the existing filter is already positioned to set.

What matters for the plan's intent is the behaviour, and that is verified independently of naming:
`X-Interaction-ID` is accepted, validated, generated when absent and echoed; `X-Trace-ID` is
returned; every Problem Details response carries both `interactionId` and `traceId`; and the
frontend mints a UUIDv7 per action and surfaces it as a support code on failure.

## Consequences

- The §16 file list is a design reference; this table is the authoritative mapping.
- New correlation components follow the repository's existing package and naming conventions.
- If an external contract ever depends on these names, this decision is revisited.
