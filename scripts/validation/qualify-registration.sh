#!/usr/bin/env bash
# Real-boundary qualification for M1-PROF-01 registration and verification.
#
# The unit and contract suites run against doubles, so they cannot show that the Keycloak admin
# grant is sufficient, that the realm's SMTP settings resolve, that the abuse ceiling is shared
# across application instances, or that OTP verification meets its latency budget. This brings up
# the real dependencies and runs the suites that need them.
#
# Not part of `gradlew check`: it needs Docker, it builds the Keycloak image, and the latency
# assertion is not stable on a shared CI runner. Run it deliberately.
#
#   ./scripts/validation/qualify-registration.sh
#   ./scripts/validation/qualify-registration.sh --keep     # leave containers running
#
# Expected evidence on success, per suite:
#   KeycloakRegistrationBoundaryIntegrationTests  8 tests  - identity creation, LEARNER-only role
#                                                            assignment, realm password policy
#                                                            enforcement, duplicate detection,
#                                                            operation-stamp reconciliation,
#                                                            verification request + reconciliation,
#                                                            and least-privilege negative controls.
#   MailpitEmailVerificationIntegrationTests      2 tests  - Keycloak delivers to the SMTP sink from
#                                                            the configured sender, carrying a live
#                                                            action-token link on the configured
#                                                            issuer. Redeeming the link is NOT
#                                                            covered: Keycloak 26 requires an
#                                                            interactive browser session.
#   MultiReplicaRateLimitIntegrationTests         1 test   - four separate JVMs, one shared ceiling.
#   OtpVerificationLatencyIntegrationTests        1 test   - prints p50/p95/p99; asserts p95 <= 250ms
#                                                            and p99 <= 500ms.
set -euo pipefail

KEEP=false
[[ "${1:-}" == "--keep" ]] && KEEP=true

NETWORK=ramals-qual
PG=qual-pg
KC=qual-keycloak
MP=qual-mailpit
PG_PORT=55432
KC_PORT=58081
MP_PORT=58025

ADMIN_USER=ramals_admin
ADMIN_PASSWORD=pra-test-admin
KC_ADMIN=admin
KC_ADMIN_PASSWORD=qual-admin-pw

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

cleanup() {
  if [[ "$KEEP" == "false" ]]; then
    echo "--> removing qualification containers"
    docker rm -f "$KC" "$MP" "$PG" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
  else
    echo "--> leaving containers running (--keep)"
  fi
}
trap cleanup EXIT

echo "--> network"
docker network create "$NETWORK" >/dev/null 2>&1 || true
docker rm -f "$PG" "$KC" "$MP" >/dev/null 2>&1 || true

echo "--> postgres"
docker run -d --name "$PG" --network "$NETWORK" \
  -e POSTGRES_DB=ramals -e POSTGRES_USER="$ADMIN_USER" -e POSTGRES_PASSWORD="$ADMIN_PASSWORD" \
  -p "${PG_PORT}:5432" postgres:18.1-alpine >/dev/null
until docker exec "$PG" pg_isready -U "$ADMIN_USER" -d ramals >/dev/null 2>&1; do sleep 1; done
docker exec "$PG" psql -U "$ADMIN_USER" -d ramals -c "CREATE DATABASE keycloak;" >/dev/null 2>&1 || true

echo "--> mailpit"
docker run -d --name "$MP" --network "$NETWORK" --network-alias mailpit \
  -p "${MP_PORT}:8025" axllent/mailpit:v1.27.11 >/dev/null

echo "--> keycloak image (imports infrastructure/docker/keycloak/ramals-realm.json)"
docker build -q -f infrastructure/docker/keycloak/Dockerfile -t ramals-qual-keycloak:local . >/dev/null

echo "--> keycloak"
docker run -d --name "$KC" --network "$NETWORK" \
  -e KC_DB=postgres -e KC_DB_URL="jdbc:postgresql://${PG}:5432/keycloak" \
  -e KC_DB_USERNAME="$ADMIN_USER" -e KC_DB_PASSWORD="$ADMIN_PASSWORD" \
  -e KC_BOOTSTRAP_ADMIN_USERNAME="$KC_ADMIN" -e KC_BOOTSTRAP_ADMIN_PASSWORD="$KC_ADMIN_PASSWORD" \
  -e KC_HEALTH_ENABLED=true -e KC_HTTP_ENABLED=true -e KC_HOSTNAME_STRICT=false \
  -p "${KC_PORT}:8080" ramals-qual-keycloak:local start-dev --import-realm >/dev/null

for _ in $(seq 1 120); do
  if [[ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${KC_PORT}/realms/ramals" || true)" == "200" ]]; then
    break
  fi
  sleep 2
done

echo "--> running real-boundary suites"
export RAMALS_TEST_POSTGRES_URL="jdbc:postgresql://localhost:${PG_PORT}/ramals"
export RAMALS_TEST_POSTGRES_ADMIN_USER="$ADMIN_USER"
export RAMALS_TEST_POSTGRES_ADMIN_PASSWORD="$ADMIN_PASSWORD"
export RAMALS_TEST_POSTGRES_ALLOW_RESET=true
export RAMALS_TEST_KEYCLOAK_URL="http://localhost:${KC_PORT}"
export RAMALS_TEST_KEYCLOAK_ADMIN="$KC_ADMIN"
export RAMALS_TEST_KEYCLOAK_ADMIN_PASSWORD="$KC_ADMIN_PASSWORD"
export RAMALS_TEST_MAILPIT_URL="http://localhost:${MP_PORT}"
export RAMALS_TEST_OTP_LATENCY=true

./gradlew --no-daemon --max-workers=1 :learning-platform:integrationTest \
  --tests '*KeycloakRegistrationBoundary*' \
  --tests '*MailpitEmailVerification*' \
  --tests '*MultiReplicaRateLimit*' \
  --tests '*OtpVerificationLatency*'

echo
echo "--> measured OTP latency"
grep -ho "OTP verify latency[^<]*" \
  learning-platform/build/test-results/integrationTest/*OtpVerificationLatency*.xml || true
echo
echo "Qualification complete."
