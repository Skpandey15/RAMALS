# ADR 0003: Migration numbering follows implementation order

- **Status:** Accepted
- **Date:** 2026-08-16
- **Tasks:** M0-T07 through M0-T17

## Context

The Database Architecture Design sketches an illustrative migration roadmap
(`V004 evidence`, `V005 mastery`, `V006 recommendation_decision`, `V007 security_audit`). The
Implementation Master Plan sequences the work differently — the learner domain (M0-T07) lands before
assessment (M0-T08), which lands before evidence (M0-T10). Flyway versions are immutable once
applied, so numbering must be decided when each migration is written, not retrofitted.

## Decision

Number migrations by **implementation order**, so each task's migration is the next integer:

| Version | Contents | Task |
| --- | --- | --- |
| V001–V003 | baseline schemas, roles/grants, curriculum | T05, T06 |
| V004 | learner domain | T07 |
| V005 | assessment and attempts | T08 |
| V006 | assessment responses | T09 |
| V007 | evidence ledger | T10 |
| V008 | mastery engine | T11 |
| V009 | evidence confidence | T12 |
| V010 | recommendation and decision record | T13 |
| V011 | progression and retention | T14 |
| V012 | learning session | T15 |
| V013 | admin audit | T17 |

## Alternatives considered

1. **Reserve gaps to match the doc's illustrative numbering.** Produces unapplied placeholder
   versions and a fragile mapping the moment sequencing changes again.
2. **Renumber retrospectively.** Impossible without breaking checksum validation on any environment
   that has already migrated.

## Consequences

- The doc's roadmap is illustrative, not normative; this table is the authoritative mapping.
- Every migration is contiguous, so `validate-migration-naming` and forward-upgrade checks hold.
- Each new migration must bump the expected count in `PostgresMigrationIntegrationTests`, which has
  functioned as a deliberate tripwire against accidental migration additions.

## Verification

- `PostgresMigrationIntegrationTests` — fresh install then forward upgrade, count asserted.
- Per-migration content contract tests for V003–V013.
