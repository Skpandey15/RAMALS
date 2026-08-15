# M0-T05 — PostgreSQL schemas, roles and Flyway baseline

## Authority and migration order

The Database Architecture v1.1 amendment controls this baseline. Flyway is the
only normal schema mutation mechanism. Applied versioned migrations are never
rewritten; changes are forward-only.

1. `V001__baseline_schemas.sql` creates `core`, `ledger`, and `audit` under the
   migration owner and removes public schema creation/access.
2. `V002__roles_and_grants_foundation.sql` establishes current and default
   privileges for the runtime identity.

## Identity separation

- `ramals_core_migration`: database/schema owner used only by Flyway.
- `ramals_core_runtime`: Spring request-time identity; never owns schemas or
  tables and has no schema `CREATE` privilege.
- `RAMALS_DB_ADMIN_USER`: local PostgreSQL bootstrap identity; not supplied to
  the backend container.

The backend uses one JDBC URL with separate runtime and Flyway credentials.
Secrets are injected through environment variables and are not stored in source.

## Grant invariants

- `core`: runtime receives table DML, refined per table as domain migrations
  arrive. The Flyway schema-history table is explicitly excluded from runtime
  access.
- `ledger` and `audit`: runtime receives only `SELECT` and `INSERT`; `UPDATE`,
  `DELETE`, `TRUNCATE`, `REFERENCES`, and `TRIGGER` are revoked.
- Default privileges apply these rules to tables and sequences created by later
  migrations, preventing a future feature migration from silently weakening the
  boundary.

## Identifier and numeric rules

Domain-visible identifiers use PostgreSQL `UUID`; high-write append-only tables
will use the repository's approved UUIDv7 generator. Authoritative mastery,
confidence, weights, scores, and thresholds use PostgreSQL `NUMERIC` mapped to
Java `BigDecimal`. `real` and `double precision` are prohibited on that path.

## Evidence

`PostgresMigrationIntegrationTests` runs only when an isolated PostgreSQL test
URL is explicitly provided and `RAMALS_TEST_POSTGRES_ALLOW_RESET=true` confirms
that its schemas may be reset. CI supplies a fresh PostgreSQL service and proves:

- installation from an empty database through V001;
- a forward upgrade through V002 and a test-only V003;
- Flyway validation;
- runtime DDL denial;
- runtime ledger UPDATE and DELETE denial;
- migration-role schema evolution;
- UUID and exact `NUMERIC(8,6)` persistence conventions;
- absence of floating-point columns in authoritative schemas.

Normal local unit tests do not start Docker or connect to the RAMALS database.
The existing local volume is intentionally outside this task's automated test
scope.
