# M1-ADR-000: MVP-1 engineering may begin before R1 is closed

- **Status:** Accepted
- **Date:** 2026-08-16
- **Relates to:** MVP-1 Canonical Package v1.3 Docs 01/07/09/10, M1-T00, M1-T18, risk R1
- **Supersedes:** the sequencing in [MVP-1 entry criteria](../release/mvp0-release-candidate.md#6-mvp-1-entry-criteria) item 1

## Context

MVP-0's recorded entry criteria require a calibrated performance baseline from the authoritative
fixed-spec environment (**R1**) before MVP-1 begins. R1 is not closed: the harness works and produces
clean data, but only from a developer workstation, and no fixed-spec environment is available yet.

Holding all MVP-1 engineering behind an environment procurement stalls work that cannot affect the
deterministic control at all — the Python service does not exist, and the boundary constraining it
(`ramals_ai_runtime`, denied everything by `V015`) is already in place and tested.

The MVP-1 package proposed relaxing this. Changing an MVP-0 exit criterion is load-bearing, so it is
recorded here rather than asserted inside a dependent document.

## Decision

Isolated MVP-1 engineering may begin before R1 closes.

R1 remains **mandatory** before any of:

- an MVP-1 release candidate,
- a calibrated deterministic-versus-agentic comparison,
- any research or performance claim,

unless a **named owner** accepts the risk in writing, with explicit scope and an expiry date.

R1 stays open and owned. It is not reclassified, deferred silently, or absorbed into MVP-1 scope.

## Consequences

- MVP-1 work proceeds on its own branch; the deterministic control is unaffected because no MVP-1
  task may alter a frozen `_V1` identifier (enforced by `EngineVersionFreezeTests`).
- The MVP-0 release record references this ADR as the approved sequencing exception, so the
  criterion is visibly changed rather than quietly unmet.
- M1-T18 cannot complete without either R1 evidence or a named, scoped, expiring risk acceptance.
- If R1 is never captured, MVP-1 can be built but cannot be released or used to make a comparative
  claim — which is the outcome this ADR is designed to make explicit rather than accidental.

## Verification

- `EngineVersionFreezeTests` pins all seven deterministic identifiers by behaviour hash.
- `Mvp0ReleaseRecordConformanceTests` asserts the release record lists all seven and references this
  ADR.
- The deterministic control commit is recorded in the release record.
