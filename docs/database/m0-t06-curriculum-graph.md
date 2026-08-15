# M0-T06 — Curriculum, skills and versioned graph

## Model

`learning_domain` owns stable domain identity. `skill` owns a stable skill code
inside a domain. Educational meaning and policy live in `skill_version`, scoped
to an exact `curriculum_version`. Objectives and prerequisite edges are also
version-scoped so historical decisions remain reconstructable.

Authoritative thresholds and proficiency values use PostgreSQL `NUMERIC` and
Java `BigDecimal`. Each skill version records mastery threshold, confidence
threshold, required evidence count, accepted evidence types, and required
difficulty bands. No mastery input uses floating-point storage.

## Graph and publication invariants

- Stable codes are unique within their domain.
- Every prerequisite endpoint belongs to the same curriculum version.
- Self-edges and direct or transitive cycles are rejected by PostgreSQL.
- Java rejects unknown nodes, duplicate codes, self-edges, and cycles again.
- Publication requires at least one skill and one required objective per skill.
- Skill versions, objectives, and edges cannot change after publication.
- `PUBLISHED` may transition only to `RETIRED`; both remain readable by version.
- Foreign keys use `RESTRICT` to protect historical references.

## Kafka curriculum v1

Migration `V003__curriculum_and_versioning.sql` deterministically seeds one
published Kafka curriculum containing 15 stable skills, 15 required objectives,
and 16 acyclic prerequisite edges. It covers fundamentals, producer delivery,
consumer coordination, and replication/failure recovery. Fixed UUIDv7 values
make the seed reproducible across environments.

## API

Authenticated `LEARNER`, `INSTRUCTOR`, and `CONTENT_AUTHOR` identities may read:

```text
GET /api/v1/curricula/{domainCode}/versions/{versionCode}/skills
GET /api/v1/curricula/{domainCode}/versions/{versionCode}/skills/{skillCode}/prerequisites
```

Only `PUBLISHED` and `RETIRED` versions are exposed. The repository uses three
bounded queries—skills, all objectives, and all edges—rather than one query per
skill.

## Evidence

Unit/API tests cover graph cycles, unknown prerequisites, authenticated access,
role denial, and prerequisite output. The isolated PostgreSQL CI test proves seed
counts, stable-code uniqueness, transitive-cycle rejection, published-row
immutability, exact numeric columns, and queryability after retirement.
