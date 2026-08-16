# ADR 0002: The adaptive pipeline runs synchronously inside diagnostic submission

- **Status:** Accepted
- **Date:** 2026-08-16
- **Tasks:** M0-T09, M0-T10, M0-T11, M0-T12, M0-T13, M0-T20

## Context

Submitting a diagnostic must produce scored responses, immutable evidence, a mastery snapshot, an
evidence-confidence value, a recommendation and a decision record. The Performance Matrix defines
**Adaptive Decision Latency (ADL)** as the elapsed time from acceptance of a submission until the
authoritative mastery snapshot and resulting recommendation are available, implying these steps
could be asynchronous.

## Decision

Execute the entire pipeline **synchronously within the submission transaction**:

```
submit -> validate -> persist responses -> finalize attempt
       -> append evidence -> recompute mastery + confidence
       -> produce recommendation + decision record   [one transaction]
```

Consequently **ADL is the submit response time**, and M0-T20 measures it as a Trend on that request
rather than by polling for eventual completion.

## Alternatives considered

1. **Asynchronous worker after commit.** Better tail latency under load, but the learner can observe
   a completed attempt with no mastery or recommendation, and a crash between commit and processing
   leaves durable state that no longer matches the ledger. MVP-0 explicitly prioritises correctness
   over throughput.
2. **Outbox plus poller.** Sound, but introduces infrastructure MVP-0 does not otherwise need and
   defers the consistency benefit without removing the complexity.

## Consequences

- Evidence, mastery snapshot, recommendation and decision record commit **atomically or not at all**;
  a failure mid-pipeline rolls the whole submission back, verified by the M0-T09 rollback test.
- Retry safety comes free: attempt state makes a duplicate submit a no-op, so the pipeline cannot run
  twice for one attempt.
- Submission latency carries the full pipeline cost. This is acceptable at MVP-0 scale and is exactly
  what the ADL metric is designed to expose; if ADL breaches its budget on the authoritative
  environment, moving to an outbox is the documented next step.

## Verification

- `DiagnosticSubmissionPersistenceIntegrationTests` — failure rolls back, leaving no partial state.
- `MvpZeroValidationTests` — one submission yields evidence, snapshot, recommendation and decision.
- `performance/scenarios/diagnostic.js` — records `adaptive_decision_latency`.
