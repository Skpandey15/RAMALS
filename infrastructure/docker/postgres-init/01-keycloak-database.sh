#!/usr/bin/env bash
set -Eeuo pipefail

identifier_pattern='^[a-z_][a-z0-9_]*$'
for variable_name in POSTGRES_DB KEYCLOAK_DB_NAME KEYCLOAK_DB_USER; do
  value="${!variable_name:-}"
  if [[ ! "$value" =~ $identifier_pattern ]]; then
    echo "$variable_name must be a lowercase PostgreSQL identifier" >&2
    exit 1
  fi
done

for variable_name in KEYCLOAK_DB_PASSWORD RAMALS_DB_MIGRATION_PASSWORD RAMALS_DB_RUNTIME_PASSWORD; do
  if [[ -z "${!variable_name:-}" ]]; then
    echo "$variable_name must be set" >&2
    exit 1
  fi
done

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=ramals_database="$POSTGRES_DB" \
  --set=migration_password="$RAMALS_DB_MIGRATION_PASSWORD" \
  --set=runtime_password="$RAMALS_DB_RUNTIME_PASSWORD" \
  --set=keycloak_user="$KEYCLOAK_DB_USER" \
  --set=keycloak_password="$KEYCLOAK_DB_PASSWORD" \
  --set=keycloak_database="$KEYCLOAK_DB_NAME" <<'SQL'
SELECT format('CREATE ROLE ramals_core_migration LOGIN PASSWORD %L', :'migration_password')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ramals_core_migration')
\gexec

SELECT format('CREATE ROLE ramals_core_runtime LOGIN PASSWORD %L', :'runtime_password')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ramals_core_runtime')
\gexec

-- MVP-1 AI workload identity. Created NOLOGIN with no password: it exists so V015 has something to
-- revoke privilege from, making the boundary an enforceable database fact before the Python runtime
-- exists. Credentials, if that service is ever granted any access at all, belong to the
-- environment's secret management rather than to a provisioning script in source control.
SELECT 'CREATE ROLE ramals_ai_runtime NOLOGIN'
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'ramals_ai_runtime')
\gexec

ALTER ROLE ramals_core_runtime SET search_path = core, pg_catalog;
ALTER ROLE ramals_core_migration SET search_path = core, ledger, audit, pg_catalog;

SELECT format('ALTER DATABASE %I OWNER TO ramals_core_migration', :'ramals_database')
\gexec
SELECT format('REVOKE CONNECT ON DATABASE %I FROM PUBLIC', :'ramals_database')
\gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO ramals_core_migration, ramals_core_runtime', :'ramals_database')
\gexec

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'keycloak_user', :'keycloak_password')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = :'keycloak_user')
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'keycloak_database', :'keycloak_user')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_database WHERE datname = :'keycloak_database')
\gexec
SQL
