# Database implementation records

Schema diagrams, migration notes, database ADRs, and reproducibility evidence belong here. PostgreSQL is authoritative; immutable learning artifacts require structural enforcement.

## M0-T05 baseline

The implementation record is [m0-t05-flyway-baseline.md](m0-t05-flyway-baseline.md).

## M0-T06 curriculum graph

The implementation record is [m0-t06-curriculum-graph.md](m0-t06-curriculum-graph.md).

## Records after M0-T06

There are none, and that is a gap rather than a decision. The practice of writing an implementation
record per database change lapsed after M0-T06 while migrations continued to V023. What the schema
does is recorded in the migrations themselves — which carry substantial commentary — and in the ADRs
that gate them, so nothing is undocumented; it is just not indexed here. Resuming the practice, or
retiring it deliberately, is worth deciding rather than leaving to drift.
