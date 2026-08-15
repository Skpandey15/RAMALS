#!/usr/bin/env bash
set -Eeuo pipefail

identifier_pattern='^[a-z_][a-z0-9_]*$'
for variable_name in KEYCLOAK_DB_NAME KEYCLOAK_DB_USER; do
  value="${!variable_name:-}"
  if [[ ! "$value" =~ $identifier_pattern ]]; then
    echo "$variable_name must be a lowercase PostgreSQL identifier" >&2
    exit 1
  fi
done

if [[ -z "${KEYCLOAK_DB_PASSWORD:-}" ]]; then
  echo 'KEYCLOAK_DB_PASSWORD must be set' >&2
  exit 1
fi

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=keycloak_user="$KEYCLOAK_DB_USER" \
  --set=keycloak_password="$KEYCLOAK_DB_PASSWORD" \
  --set=keycloak_database="$KEYCLOAK_DB_NAME" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'keycloak_user', :'keycloak_password')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = :'keycloak_user')
\gexec

SELECT format('CREATE DATABASE %I OWNER %I', :'keycloak_database', :'keycloak_user')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_database WHERE datname = :'keycloak_database')
\gexec
SQL

