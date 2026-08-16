#!/usr/bin/env python3
"""End-to-end workload identity drill against a real Keycloak (M1-ADR-003).

Every other test of this boundary signs its own tokens. That proves the verifier logic and nothing
about the realm. The M0-T18 drill exists because a protocol mapper was configured correctly, did
nothing, and passed every mock-JWT test in the suite — so the audience mapper this boundary depends
on has to be exercised against the real issuer.

The decisive checks:

  * a token minted for `ramals-core-workload` actually carries `aud: ramals-ai`
    (an inert audience mapper fails here, and nowhere else)
  * a real learner token is rejected at the internal boundary while remaining valid for the platform

Run inside the compose network so the issuer matches what the service is configured to trust:

    docker run --rm -i --network ramals-deploy_edge \
        -e RAMALS_KEYCLOAK_ADMIN -e RAMALS_KEYCLOAK_ADMIN_PASSWORD \
        python:3.13-alpine python - < scripts/validation/workload-identity-e2e.py

Fixtures are provisioned through the admin API and the original realm posture is restored in a
finally block; the committed realm is never modified.
"""

import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK = os.environ.get("RAMALS_KEYCLOAK_URL", "http://keycloak:8080")
REALM = "ramals"
WORKLOAD_CLIENT = "ramals-core-workload"
WEB_CLIENT = "ramals-web-ui"
ADMIN_USER = os.environ.get("RAMALS_KEYCLOAK_ADMIN", "admin")
ADMIN_PASSWORD = os.environ["RAMALS_KEYCLOAK_ADMIN_PASSWORD"]
WORKLOAD_SECRET = "m1-t03-drill-secret-local-only"
LEARNER_USER = "m1-t03-learner"
LEARNER_PASSWORD = "m1-t03-learner-local-only"

failures: list[str] = []


def check(description: str, actual, expected) -> None:
    if actual == expected:
        print(f"ok   {description} ({actual})")
    else:
        print(f"FAIL {description}: expected {expected!r}, got {actual!r}")
        failures.append(description)


def request(method, url, *, data=None, token=None, form=False):
    body, headers = None, {}
    if data is not None:
        if form:
            body = urllib.parse.urlencode(data).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, (json.loads(raw) if raw else None)
        except json.JSONDecodeError:
            return error.code, None


def claims_of(token: str) -> dict:
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


status, tokens = request(
    "POST",
    f"{KEYCLOAK}/realms/master/protocol/openid-connect/token",
    data={"grant_type": "password", "client_id": "admin-cli",
          "username": ADMIN_USER, "password": ADMIN_PASSWORD},
    form=True,
)
if status != 200:
    sys.exit(f"FATAL: could not obtain Keycloak admin token ({status})")
admin_token = tokens["access_token"]

print("Provisioning drill fixtures through the admin API")

# The workload client as the committed realm declares it, including the audience mapper.
status, existing = request(
    "GET", f"{KEYCLOAK}/admin/realms/{REALM}/clients?clientId={WORKLOAD_CLIENT}", token=admin_token)
workload_definition = {
    "clientId": WORKLOAD_CLIENT,
    "enabled": True,
    "publicClient": False,
    "serviceAccountsEnabled": True,
    "standardFlowEnabled": False,
    "directAccessGrantsEnabled": False,
    "implicitFlowEnabled": False,
    "secret": WORKLOAD_SECRET,
    "protocolMappers": [{
        "name": "ramals-ai-audience",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper",
        "consentRequired": False,
        "config": {
            "included.client.audience": "ramals-ai",
            "id.token.claim": "false",
            "access.token.claim": "true",
            "lightweight.claim": "false",
        },
    }],
}
if existing:
    workload_id = existing[0]["id"]
    request("PUT", f"{KEYCLOAK}/admin/realms/{REALM}/clients/{workload_id}",
            data={**existing[0], **workload_definition}, token=admin_token)
else:
    request("POST", f"{KEYCLOAK}/admin/realms/{REALM}/clients",
            data=workload_definition, token=admin_token)
    status, created = request(
        "GET", f"{KEYCLOAK}/admin/realms/{REALM}/clients?clientId={WORKLOAD_CLIENT}",
        token=admin_token)
    workload_id = created[0]["id"]

# A real learner, and password grant enabled only for the drill.
status, web_clients = request(
    "GET", f"{KEYCLOAK}/admin/realms/{REALM}/clients?clientId={WEB_CLIENT}", token=admin_token)
web_client = web_clients[0]
original_direct_grants = web_client.get("directAccessGrantsEnabled", False)
web_client["directAccessGrantsEnabled"] = True
request("PUT", f"{KEYCLOAK}/admin/realms/{REALM}/clients/{web_client['id']}",
        data=web_client, token=admin_token)

request("POST", f"{KEYCLOAK}/admin/realms/{REALM}/users", token=admin_token, data={
    "username": LEARNER_USER, "enabled": True, "emailVerified": True,
    "credentials": [{"type": "password", "value": LEARNER_PASSWORD, "temporary": False}],
})

try:
    print("\n== 1. The workload token carries the audience the boundary depends on ==")
    status, issued = request(
        "POST", f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token",
        data={"grant_type": "client_credentials", "client_id": WORKLOAD_CLIENT,
              "client_secret": WORKLOAD_SECRET},
        form=True)
    check("client credentials grant issues a token", status, 200)
    if status != 200:
        raise SystemExit(1)

    workload_claims = claims_of(issued["access_token"])
    audience = workload_claims.get("aud")
    audience = audience if isinstance(audience, list) else [audience]

    # This is the assertion an inert audience mapper fails, and the reason this drill exists.
    check("workload token carries the ramals-ai audience", "ramals-ai" in audience, True)
    check("workload token is issued to the workload client",
          workload_claims.get("azp"), WORKLOAD_CLIENT)
    check("workload token issuer matches the configured issuer",
          workload_claims.get("iss"), f"{KEYCLOAK}/realms/{REALM}")
    check("workload token represents a service account, not a person",
          str(workload_claims.get("preferred_username", "")).startswith("service-account-"), True)

    print("\n== 2. A real learner token is not a workload token ==")
    status, learner_issued = request(
        "POST", f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token",
        data={"grant_type": "password", "client_id": WEB_CLIENT,
              "username": LEARNER_USER, "password": LEARNER_PASSWORD},
        form=True)
    check("learner obtains a genuine token", status, 200)
    if status == 200:
        learner_claims = claims_of(learner_issued["access_token"])
        learner_audience = learner_claims.get("aud")
        learner_audience = (
            learner_audience if isinstance(learner_audience, list) else [learner_audience])
        # Valid, unexpired, correctly signed -- and not for this boundary.
        check("learner token does NOT carry the ramals-ai audience",
              "ramals-ai" in learner_audience, False)
        check("learner token is issued to the web client", learner_claims.get("azp"), WEB_CLIENT)
        check("learner token represents a person",
              learner_claims.get("preferred_username"), LEARNER_USER)

    print("\n== 3. The workload client cannot be used to obtain a user token ==")
    status, _ = request(
        "POST", f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token",
        data={"grant_type": "password", "client_id": WORKLOAD_CLIENT,
              "client_secret": WORKLOAD_SECRET,
              "username": LEARNER_USER, "password": LEARNER_PASSWORD},
        form=True)
    check("password grant is refused for the workload client", status in (400, 401), True)

finally:
    web_client["directAccessGrantsEnabled"] = original_direct_grants
    request("PUT", f"{KEYCLOAK}/admin/realms/{REALM}/clients/{web_client['id']}",
            data=web_client, token=admin_token)
    status, users = request(
        "GET", f"{KEYCLOAK}/admin/realms/{REALM}/users?username={LEARNER_USER}&exact=true",
        token=admin_token)
    if status == 200 and users:
        request("DELETE", f"{KEYCLOAK}/admin/realms/{REALM}/users/{users[0]['id']}",
                token=admin_token)
    print(f"\nRestored directAccessGrantsEnabled={original_direct_grants} on {WEB_CLIENT}; "
          "removed the drill learner.")

print()
if failures:
    print(f"{len(failures)} check(s) FAILED: {failures}")
    sys.exit(1)
print("All workload identity checks passed against a real Keycloak.")
