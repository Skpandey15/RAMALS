# Evidence: workload identity against a real Keycloak (M1-T03)

Executed against the running `v0.1.0-rc2` stack. Closes the M1-T03 acceptance criterion that a user
token alone cannot impersonate the service workload.

## Why a live drill and not only unit tests

The unit suite signs genuine RS256 tokens and drives the real verifier, which proves the verification
logic. It proves nothing about the **realm**.

M0-T18 is the precedent: the `learner_id` protocol mapper was configured correctly, was completely
inert because the attribute was undeclared in the user profile, and passed every mock-JWT test in
the suite. The boundary here depends on exactly that kind of object — an audience mapper — so it has
to be exercised against the real issuer.

## Result — 10 of 10 checks passed

| Check | Result |
| --- | --- |
| Client credentials grant issues a token | 200 |
| **Workload token carries the `ramals-ai` audience** | **true** |
| Token is issued to `ramals-core-workload` | `azp = ramals-core-workload` |
| Issuer matches the configured issuer | `http://keycloak:8080/realms/ramals` |
| Token represents a service account, not a person | `service-account-…` |
| Learner obtains a genuine token | 200 |
| **Learner token does NOT carry the `ramals-ai` audience** | **false** |
| Learner token is issued to `ramals-web-ui` | `azp = ramals-web-ui` |
| Learner token represents a person | `preferred_username = m1-t03-learner` |
| Password grant refused for the workload client | 400/401 |

The two bolded rows are the decision. The learner token is correctly signed, unexpired and issued by
the same realm — it is perfectly valid at the platform API. It is refused at the internal boundary
solely because it was not minted for this audience. An inert audience mapper fails the first of them
and nothing else in the suite would notice.

The drill provisions fixtures through the admin API and restores the original posture in a `finally`
block, so the committed realm keeps password grants disabled and gains no user.

## Defect found: the realm guards never ran

While confirming the new realm assertions actually catch a regression, all three perturbations —
removing the audience mapper, enabling direct access grants on the workload client, and committing a
client secret — left the build **green**.

The cause was not the assertions. `RealmHardeningTests` reads
`infrastructure/docker/keycloak/ramals-realm.json`, which is outside the Gradle module and was not a
declared task input, so Gradle treated the test task as up to date and skipped it. With
`--rerun-tasks` every perturbation was caught.

This affected the **pre-existing** `learner_id` and MFA realm assertions too: a realm regression could
have passed a cached build since M0-T18.

Fixed by declaring the external files the suites actually read as test inputs — the realm, the AI
contract and its golden fixtures, the ADRs, the release record and `.env.example`. All three
perturbations are now caught without `--rerun-tasks`.

## Not done here, deliberately

`ramals_ai_runtime` remains `NOLOGIN` with no `CONNECT`. Doc 01 §9 makes the login lifecycle
conditional on AI execution persistence being enabled, and persistence arrives in **M1-T13**. Turning
the credential on now would create a usable database identity months before anything needs it.

The database boundary itself is unchanged and still asserted: `AiRuntimeBoundaryIntegrationTests`
proves `42501` on read, write and DDL across all eight platform tables.

## Reproducing

```bash
docker run --rm -i --network ramals-deploy_edge \
  -e RAMALS_KEYCLOAK_ADMIN -e RAMALS_KEYCLOAK_ADMIN_PASSWORD \
  python:3.13-alpine python - < scripts/validation/workload-identity-e2e.py
```
