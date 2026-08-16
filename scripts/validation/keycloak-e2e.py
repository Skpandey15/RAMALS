#!/usr/bin/env python3
"""End-to-end authorization drill against a real Keycloak-issued token.

Every other authorization test in this repository uses a mock JWT. This drill closes that gap
(risk R3): it provisions a learner in the running realm, obtains a genuine access token from
Keycloak, and drives the API with it — proving signature, issuer, audience, realm-role and custom
claim handling all work against the real identity provider.

It must run inside the compose network so the token's issuer matches the backend's configured
issuer exactly:

    docker run --rm --network ramals_edge -v "$PWD/scripts/validation:/w" \
        python:3.13-alpine python /w/keycloak-e2e.py

Test fixtures are created at runtime through the admin API. The committed realm definition is
never modified: direct access grants are enabled only for the duration of this drill and restored
afterwards, so the shipped realm keeps password grants disabled.
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK = "http://keycloak:8080"
BACKEND = "http://backend:8080"
REALM = "ramals"
CLIENT = "ramals-web-ui"
# Credentials come from the environment so nothing sensitive is embedded in the script.
ADMIN_USER = os.environ.get("RAMALS_KEYCLOAK_ADMIN", "admin")
ADMIN_PASSWORD = os.environ["RAMALS_KEYCLOAK_ADMIN_PASSWORD"]
LEARNER_USER = "e2e-learner"
LEARNER_PASSWORD = "e2e-learner-local-only"
LEARNER_ID = "e2e-learner-001"

failures: list[str] = []


def check(description: str, actual, expected) -> None:
    if actual == expected:
        print(f"ok   {description} ({actual})")
    else:
        print(f"FAIL {description}: expected {expected!r}, got {actual!r}")
        failures.append(description)


def request(method, url, *, data=None, token=None, headers=None, form=False):
    body = None
    hdrs = dict(headers or {})
    if data is not None:
        if form:
            body = urllib.parse.urlencode(data).encode()
            hdrs["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            body = json.dumps(data).encode()
            hdrs["Content-Type"] = "application/json"
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return error.code, None


def decode_claims(token: str) -> dict:
    import base64

    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


# --- provision fixtures via the admin API -------------------------------------------------------
status, tokens = request(
    "POST",
    f"{KEYCLOAK}/realms/master/protocol/openid-connect/token",
    data={"grant_type": "password", "client_id": "admin-cli",
          "username": ADMIN_USER, "password": ADMIN_PASSWORD},
    form=True,
)
if status != 200:
    print(f"FATAL: could not obtain Keycloak admin token ({status})")
    raise SystemExit(1)
admin_token = tokens["access_token"]
print("Provisioning E2E fixtures through the admin API")

status, clients = request("GET", f"{KEYCLOAK}/admin/realms/{REALM}/clients?clientId={CLIENT}",
                          token=admin_token)
client = clients[0]
original_direct_grants = client.get("directAccessGrantsEnabled", False)
client["directAccessGrantsEnabled"] = True
request("PUT", f"{KEYCLOAK}/admin/realms/{REALM}/clients/{client['id']}", data=client,
        token=admin_token)

request("POST", f"{KEYCLOAK}/admin/realms/{REALM}/users", token=admin_token, data={
    "username": LEARNER_USER,
    "enabled": True,
    "emailVerified": True,
    "attributes": {"learner_id": [LEARNER_ID]},
    "credentials": [{"type": "password", "value": LEARNER_PASSWORD, "temporary": False}],
})
status, users = request("GET", f"{KEYCLOAK}/admin/realms/{REALM}/users?username={LEARNER_USER}",
                        token=admin_token)
user_id = users[0]["id"]
status, role = request("GET", f"{KEYCLOAK}/admin/realms/{REALM}/roles/LEARNER", token=admin_token)
request("POST", f"{KEYCLOAK}/admin/realms/{REALM}/users/{user_id}/role-mappings/realm",
        data=[{"id": role["id"], "name": role["name"]}], token=admin_token)

try:
    # --- the drill ------------------------------------------------------------------------------
    print("\n== 1. A real Keycloak-issued token ==")
    status, issued = request(
        "POST", f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token",
        data={"grant_type": "password", "client_id": CLIENT,
              "username": LEARNER_USER, "password": LEARNER_PASSWORD},
        form=True,
    )
    check("Keycloak issues an access token", status, 200)
    if status != 200:
        raise SystemExit(1)
    token = issued["access_token"]
    claims = decode_claims(token)
    check("token issuer matches the backend's expectation",
          claims.get("iss"), f"{KEYCLOAK}/realms/{REALM}")
    audience = claims.get("aud")
    audience = audience if isinstance(audience, list) else [audience]
    check("token carries the ramals-api audience", "ramals-api" in audience, True)
    check("token carries the LEARNER realm role",
          "LEARNER" in claims.get("realm_access", {}).get("roles", []), True)
    check("token carries the learner_id claim", claims.get("learner_id"), LEARNER_ID)

    print("\n== 2. Authentication and authorization enforcement ==")
    status, _ = request("GET", f"{BACKEND}/api/v1/me")
    check("unauthenticated request is rejected", status, 401)

    status, _ = request("GET", f"{BACKEND}/api/v1/me", token="not-a-real-token")
    check("forged token is rejected", status, 401)

    status, identity = request("GET", f"{BACKEND}/api/v1/me", token=token)
    check("real token is accepted", status, 200)
    check("identity resolves to the token subject", identity.get("subject") == claims.get("sub"), True)

    status, _ = request("GET", f"{BACKEND}/api/v1/admin/curricula", token=token)
    check("learner is denied the admin surface", status, 403)

    status, _ = request("GET", f"{BACKEND}/api/v1/admin/security-check", token=token)
    check("learner is denied the MFA-gated admin path", status, 403)

    print("\n== 3. Full learner slice under a real token ==")
    status, attempt = request("POST", f"{BACKEND}/api/v1/diagnostics/kafka/attempts",
                              token=token, headers={"Idempotency-Key": "e2e-key-1"})
    check("diagnostic attempt created", status, 201)
    attempt_id = attempt["attemptId"]

    status, replay = request("POST", f"{BACKEND}/api/v1/diagnostics/kafka/attempts",
                             token=token, headers={"Idempotency-Key": "e2e-key-1"})
    check("retry with the same key returns the same attempt", replay["attemptId"], attempt_id)
    check("retry is not a fresh creation", status, 200)

    status, detail = request("GET", f"{BACKEND}/api/v1/diagnostics/kafka/attempts/{attempt_id}",
                             token=token)
    check("attempt items are served", status, 200)
    body = json.dumps(detail)
    check("answer key never leaves the server", "correct" not in body and "answerKey" not in body, True)

    answers = {"KAFKA_DIAG_BROKER": "B", "KAFKA_DIAG_TOPIC": "C", "KAFKA_DIAG_PARTITION": "B",
               "KAFKA_DIAG_ACKS": "C", "KAFKA_DIAG_CONSUMER_GROUPS": "B"}
    responses = [{"itemId": item["itemId"],
                  "selectedOptions": [answers.get(item["itemCode"], item["options"][0]["id"])]}
                 for item in detail["items"]]
    status, result = request("POST",
                             f"{BACKEND}/api/v1/diagnostics/kafka/attempts/{attempt_id}/submit",
                             data={"responses": responses}, token=token)
    check("submission completes", status, 200)
    check("attempt is COMPLETED", result.get("status"), "COMPLETED")
    check("per-skill scores returned", len(result.get("skillScores", [])) > 0, True)

    status, mastery = request("GET", f"{BACKEND}/api/v1/me/mastery/KAFKA/versions/v1", token=token)
    check("mastery map is readable", status, 200)
    check("mastery recorded for the learner", len(mastery.get("skills", [])) > 0, True)

    status, recommendations = request("GET", f"{BACKEND}/api/v1/me/recommendations", token=token)
    check("recommendations are readable", status, 200)
    check("a recommendation was produced",
          len(recommendations.get("recommendations", [])) > 0, True)

    status, progression = request("GET", f"{BACKEND}/api/v1/me/progression/KAFKA/versions/v1",
                                  token=token)
    check("progression is readable", status, 200)

    print("\n== 4. Correlation is available for support ==")
    status, problem = request("GET", f"{BACKEND}/api/v1/me/goal", token=token)
    check("unset goal returns a problem document", status, 404)
    check("problem carries a support code", bool(problem.get("interactionId")), True)

finally:
    # Restore the shipped security posture regardless of outcome.
    client["directAccessGrantsEnabled"] = original_direct_grants
    request("PUT", f"{KEYCLOAK}/admin/realms/{REALM}/clients/{client['id']}", data=client,
            token=admin_token)
    print("\nRestored directAccessGrantsEnabled="
          f"{original_direct_grants} on {CLIENT}")

print()
if failures:
    print(f"{len(failures)} check(s) FAILED: {failures}")
    sys.exit(1)
print("All Keycloak end-to-end authorization checks passed.")
