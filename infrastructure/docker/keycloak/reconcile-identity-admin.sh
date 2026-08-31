#!/bin/sh
set -eu

K=/opt/keycloak/bin/kcadm.sh
SERVER=${KEYCLOAK_ADMIN_SERVER:-http://keycloak:8080}
REALM=${RAMALS_ADMIN_IDENTITY_REALM:-ramals}
CLIENT_ID=${RAMALS_ADMIN_IDENTITY_CLIENT_ID:-ramals-identity-admin}
ADMIN_USER=${KC_BOOTSTRAP_ADMIN_USERNAME:?KC_BOOTSTRAP_ADMIN_USERNAME is required}
ADMIN_PASSWORD=${KC_BOOTSTRAP_ADMIN_PASSWORD:?KC_BOOTSTRAP_ADMIN_PASSWORD is required}
SECRET=${RAMALS_ADMIN_IDENTITY_CLIENT_SECRET:-}

# Kubernetes bootstrap supplies the secret on stdin so it never appears in argv or shell history.
if [ -z "$SECRET" ]; then
  IFS= read -r SECRET
  SECRET=$(printf %s "$SECRET" | sed -e 's/[[:cntrl:]]*$//')
fi
if [ -z "$SECRET" ]; then
  echo "identity-admin client secret is required" >&2
  exit 1
fi

kcadm() {
  "$K" "$@" --no-config --server "$SERVER" --realm master \
    --user "$ADMIN_USER" --password "$ADMIN_PASSWORD"
}

client_id=$(kcadm get clients -r "$REALM" -q clientId="$CLIENT_ID" \
  --fields id --format csv --noquotes 2>/dev/null | head -1 || true)

if [ -z "$client_id" ]; then
  client_id=$(kcadm create clients -r "$REALM" -i \
    -s "clientId=$CLIENT_ID" \
    -s 'name=RAMALS interactive identity administration' \
    -s 'description=Dedicated least-privilege identity for server-side staff user administration (M1-ADR-017).' \
    -s enabled=true \
    -s publicClient=false \
    -s serviceAccountsEnabled=true \
    -s standardFlowEnabled=false \
    -s directAccessGrantsEnabled=false \
    -s implicitFlowEnabled=false \
    -s "secret=$SECRET")
else
  kcadm update "clients/$client_id" -r "$REALM" \
    -s enabled=true \
    -s publicClient=false \
    -s serviceAccountsEnabled=true \
    -s standardFlowEnabled=false \
    -s directAccessGrantsEnabled=false \
    -s implicitFlowEnabled=false \
    -s "secret=$SECRET" >/dev/null
fi

service_user="service-account-$CLIENT_ID"
# Service-account creation is synchronous with serviceAccountsEnabled, but query it explicitly so a
# persistent realm with a malformed client fails bootstrap rather than leaving a ready-but-broken API.
service_user_id=$(kcadm get users -r "$REALM" -q username="$service_user" \
  --fields id --format csv --noquotes 2>/dev/null | head -1 || true)
if [ -z "$service_user_id" ]; then
  echo "Keycloak did not create service account $service_user" >&2
  exit 1
fi

# Least privilege: user administration only. Explicitly remove realm-wide administration if an old
# persistent realm ever accumulated it, then converge on the two required client roles.
kcadm remove-roles -r "$REALM" --uusername "$service_user" --cclientid realm-management \
  --rolename realm-admin >/dev/null 2>&1 || true
kcadm remove-roles -r "$REALM" --uusername "$service_user" --cclientid realm-management \
  --rolename manage-realm >/dev/null 2>&1 || true
kcadm add-roles -r "$REALM" --uusername "$service_user" --cclientid realm-management \
  --rolename manage-users >/dev/null
kcadm add-roles -r "$REALM" --uusername "$service_user" --cclientid realm-management \
  --rolename view-users >/dev/null

# Defense in depth for RAMALS policy checks. The backend also identifies Keycloak service accounts
# from their serviceAccountClientId metadata, so absence of this realm role can never make them
# manageable as human staff identities.
kcadm add-roles -r "$REALM" --uusername "$service_user" --rolename SERVICE >/dev/null

echo "reconciled $CLIENT_ID"
