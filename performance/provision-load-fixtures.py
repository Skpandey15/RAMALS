#!/usr/bin/env python3
"""Provision (and tear down) Keycloak fixtures for a k6 load run.

The shipped realm deliberately has direct access grants DISABLED on `ramals-web-ui` and defines no
users, so the k6 scenarios cannot authenticate against a stock deployment. Rather than weakening the
committed realm, this script provisions what a load run needs at runtime and restores the original
posture afterwards — the same approach scripts/validation/keycloak-e2e.py takes.

    provision-load-fixtures.py provision --learners 20 --state /tmp/perf-fixtures.json
    provision-load-fixtures.py restore   --state /tmp/perf-fixtures.json

`restore` is idempotent and safe to run from a trap. Credentials come from the environment:

    RAMALS_KEYCLOAK_ADMIN, RAMALS_KEYCLOAK_ADMIN_PASSWORD   Keycloak admin
    RAMALS_LOAD_PASSWORD                                    password given to the load learners

A pool of distinct learners matters for validity, not just for auth. Every scenario previously
shared one token, so all mastery/progression writes serialised on a single learner's rows behind
`SELECT ... FOR UPDATE`. That measures lock contention on one row, not system capacity.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK = os.environ.get("RAMALS_KEYCLOAK_URL", "http://keycloak:8080")
REALM = os.environ.get("RAMALS_REALM", "ramals")
CLIENT = os.environ.get("RAMALS_CLIENT_ID", "ramals-web-ui")
USER_PREFIX = os.environ.get("RAMALS_LOAD_USER_PREFIX", "load-learner")
# Prefix that lets a wrapper pick the state record out of stdout when --state is "-".
STATE_MARKER = "FIXTURE_STATE="


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


def admin_token():
    status, tokens = request(
        "POST",
        f"{KEYCLOAK}/realms/master/protocol/openid-connect/token",
        data={
            "grant_type": "password",
            "client_id": "admin-cli",
            "username": os.environ.get("RAMALS_KEYCLOAK_ADMIN", "admin"),
            "password": os.environ["RAMALS_KEYCLOAK_ADMIN_PASSWORD"],
        },
        form=True,
    )
    if status != 200:
        sys.exit(f"FATAL: could not obtain Keycloak admin token ({status})")
    return tokens["access_token"]


def get_client(token):
    status, clients = request(
        "GET", f"{KEYCLOAK}/admin/realms/{REALM}/clients?clientId={CLIENT}", token=token
    )
    if status != 200 or not clients:
        sys.exit(f"FATAL: client {CLIENT} not found in realm {REALM} ({status})")
    return clients[0]


def provision(args):
    password = os.environ.get("RAMALS_LOAD_PASSWORD")
    if not password:
        sys.exit("FATAL: set RAMALS_LOAD_PASSWORD (never hard-code load credentials)")

    token = admin_token()
    client = get_client(token)
    original = client.get("directAccessGrantsEnabled", False)

    # Record BEFORE mutating, so a crash mid-provision can still be rolled back. `--state -` emits
    # the record on stdout instead, so the wrapper can keep it host-side when this runs in a
    # throwaway container with no writable mount.
    record = {"client_id": client["id"], "original_direct_grants": original,
              "user_prefix": USER_PREFIX, "learners": args.learners}
    if args.state == "-":
        print(STATE_MARKER + json.dumps(record))
    else:
        with open(args.state, "w") as handle:
            json.dump(record, handle, indent=2)

    client["directAccessGrantsEnabled"] = True
    request("PUT", f"{KEYCLOAK}/admin/realms/{REALM}/clients/{client['id']}",
            data=client, token=token)

    status, role = request("GET", f"{KEYCLOAK}/admin/realms/{REALM}/roles/LEARNER", token=token)
    if status != 200:
        sys.exit(f"FATAL: LEARNER realm role not found ({status})")

    created = 0
    for index in range(args.learners):
        username = f"{USER_PREFIX}-{index:03d}"
        status, _ = request("POST", f"{KEYCLOAK}/admin/realms/{REALM}/users", token=token, data={
            "username": username,
            "enabled": True,
            "emailVerified": True,
            "attributes": {"learner_id": [f"{username}-id"]},
            "credentials": [{"type": "password", "value": password, "temporary": False}],
        })
        # 409 means the user survived a previous run; reuse it rather than failing the whole run.
        if status not in (201, 409):
            sys.exit(f"FATAL: could not create {username} ({status})")
        status, users = request(
            "GET", f"{KEYCLOAK}/admin/realms/{REALM}/users?username={username}&exact=true",
            token=token)
        if status != 200 or not users:
            sys.exit(f"FATAL: could not read back {username} ({status})")
        request("POST", f"{KEYCLOAK}/admin/realms/{REALM}/users/{users[0]['id']}/role-mappings/realm",
                data=[{"id": role["id"], "name": role["name"]}], token=token)
        created += 1

    print(f"Provisioned {created} load learner(s) '{USER_PREFIX}-000..{args.learners - 1:03d}' "
          f"and enabled direct access grants on {CLIENT}.")


def restore(args):
    inline = os.environ.get("RAMALS_LOAD_FIXTURE_STATE_JSON")
    if inline:
        state = json.loads(inline)
    else:
        try:
            with open(args.state) as handle:
                state = json.load(handle)
        except FileNotFoundError:
            print("No fixture state file; nothing to restore.")
            return

    token = admin_token()
    client = get_client(token)
    client["directAccessGrantsEnabled"] = state["original_direct_grants"]
    request("PUT", f"{KEYCLOAK}/admin/realms/{REALM}/clients/{client['id']}",
            data=client, token=token)

    removed = 0
    for index in range(state.get("learners", 0)):
        username = f"{state['user_prefix']}-{index:03d}"
        status, users = request(
            "GET", f"{KEYCLOAK}/admin/realms/{REALM}/users?username={username}&exact=true",
            token=token)
        if status == 200 and users:
            request("DELETE", f"{KEYCLOAK}/admin/realms/{REALM}/users/{users[0]['id']}", token=token)
            removed += 1

    if not inline and args.state != "-":
        os.remove(args.state)
    print(f"Restored directAccessGrantsEnabled={state['original_direct_grants']} on {CLIENT}; "
          f"removed {removed} load learner(s).")


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("action", choices=["provision", "restore"])
parser.add_argument("--learners", type=int, default=int(os.environ.get("RAMALS_LOAD_LEARNERS", 20)))
parser.add_argument("--state", default=os.environ.get("RAMALS_LOAD_FIXTURE_STATE",
                                                      "/tmp/ramals-perf-fixtures.json"))
args = parser.parse_args()
(provision if args.action == "provision" else restore)(args)
