# Local Docker infrastructure

The M0-T02 local platform contains PostgreSQL 18.1, Keycloak 26.7.1, the Spring backend, and the React web UI. Image versions and Dockerfile base-image digests are pinned. Host ports bind only to `127.0.0.1`.

## First start

From the repository root:

```powershell
Copy-Item .env.example .env
```

Fill every value in `.env`. Use unique, randomly generated local-development passwords; do not commit the file. Recommended non-secret identifiers and URLs are:

```dotenv
RAMALS_DB_NAME=ramals
RAMALS_DB_ADMIN_USER=ramals_bootstrap
RAMALS_DB_RUNTIME_USER=ramals_core_runtime
RAMALS_DB_MIGRATION_USER=ramals_core_migration
KEYCLOAK_DB_NAME=keycloak
KEYCLOAK_DB_USER=keycloak_local
RAMALS_KEYCLOAK_ADMIN=choose-a-nondefault-admin-name
RAMALS_OIDC_ISSUER_URI=http://keycloak:8080/realms/ramals
RAMALS_WEB_ORIGIN=http://localhost:5173
```

Use different generated values for `RAMALS_DB_ADMIN_PASSWORD`,
`RAMALS_DB_MIGRATION_PASSWORD`, and `RAMALS_DB_RUNTIME_PASSWORD`. The bootstrap
identity creates the two least-privilege identities only during initialization;
the backend uses the runtime identity for requests and the migration identity
only for Flyway.

An existing PostgreSQL volume is never modified automatically because image
initialization scripts run only for a new data directory. Reconcile an existing
development database during a controlled maintenance window; do not delete the
volume merely to apply M0-T05.

Then start the platform:

```powershell
docker compose --env-file .env -f infrastructure/docker/compose.yml up --build --wait
docker compose --env-file .env -f infrastructure/docker/compose.yml ps
```

- Web UI: `http://localhost:5173`
- Backend readiness: `http://localhost:8080/actuator/health/readiness`
- Keycloak: `http://localhost:8081`
- Realm: `ramals`
- Public PKCE client: `ramals-web-ui`

Keycloak imports the local realm only when it does not already exist. No application users are pre-created. Sign in to the administration console with the bootstrap identity supplied through `.env`, immediately create a separate named administrator, configure MFA as supported by the local environment, and remove or disable the bootstrap administrator. Never reuse the database or bootstrap credentials outside local development.

## Stop and restart

Preserve PostgreSQL data:

```powershell
docker compose --env-file .env -f infrastructure/docker/compose.yml down
docker compose --env-file .env -f infrastructure/docker/compose.yml up --wait
```

To prove persistence, create a marker in PostgreSQL, restart, and query it again:

```powershell
docker compose --env-file .env -f infrastructure/docker/compose.yml exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "CREATE TABLE IF NOT EXISTS local_persistence_probe(id integer primary key); INSERT INTO local_persistence_probe VALUES (1) ON CONFLICT DO NOTHING;"'
docker compose --env-file .env -f infrastructure/docker/compose.yml restart postgres
docker compose --env-file .env -f infrastructure/docker/compose.yml exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT * FROM local_persistence_probe;"'
```

Destructive reset for disposable local data only:

```powershell
docker compose --env-file .env -f infrastructure/docker/compose.yml down --volumes
```

The last command permanently removes the local PostgreSQL volume. It is never part of normal stop/restart or M0-T05 migration.
