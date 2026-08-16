# Live-stack evidence: Keycloak authorization and immutable-image deployment

Executed against a running Docker Compose stack on a local validation host, against the
`v0.1.0-rc1` published artefacts. This narrows risks **R2** and closes **R3**.

| | |
| --- | --- |
| Release under test | `v0.1.0-rc1`, commit `36645cf` |
| Backend image | `ghcr.io/skpandey15/ramals-learning-platform@sha256:a31d830a…` |
| Web UI image | `ghcr.io/skpandey15/ramals-web-ui@sha256:e527dd43…` |
| Environment | local validation host — **not** the authoritative performance environment |

## 1. Fresh install from the real image — pass

`docker compose up` on an empty volume: the published backend image applied **all 13 migrations**
(`V001` → `V013`) against a freshly initialised PostgreSQL, then reported healthy. Keycloak imported
the realm and became healthy; the web UI served its health endpoint.

## 2. Keycloak authorization end to end — pass (R3 closed)

`scripts/validation/keycloak-e2e.py`, run inside the compose network so the token issuer matches the
backend's configured issuer exactly. **26 of 26 checks passed** against a genuine Keycloak-issued
token:

- **Token shape** — issuer matches, `ramals-api` audience present, `LEARNER` realm role present,
  `learner_id` claim present.
- **Enforcement** — unauthenticated rejected (401), forged token rejected (401), real token accepted
  (200), learner denied the admin surface (403) and the MFA-gated admin path (403).
- **Full learner slice under a real token** — attempt created (201), retry with the same
  `Idempotency-Key` returned the same attempt (200, not a new creation), items served with **no
  answer key in the payload**, submission COMPLETED with per-skill scores, mastery map, recommendation
  and progression all readable.
- **Support correlation** — the error path returned a problem document carrying an `interactionId`.

### Defect found and fixed by this drill

The first run failed one check: **`learner_id` was absent from the token**. Root cause: Keycloak's
declarative user profile did not declare `learner_id`, and unmanaged attributes are disabled by
default, so the attribute was **silently discarded on write** and the M0-T18 protocol mapper was
inert. No mock-JWT test could have caught this — it is the same shape as the original review finding.

Fixed by declaring `learner_id` in the realm user profile as admin-view/admin-edit only, so a learner
can never edit their own identifier. All 26 checks pass after the fix.

The product was never broken by this: ADR 0001 moved learner identity to the OIDC subject, so the
`/me` surface does not depend on `learner_id`. The fault was confined to the legacy claim path.

## 3. Immutable-image deployment — partially demonstrated (R2 narrowed)

`deploy/compose.deploy.yml` was added: unlike the development compose it **never builds**, consuming
only the digests the controller exports from the approved manifest.

Verified live:

- **Published images pull by digest** from GHCR and run: all four services reported healthy from the
  exact immutable artefacts that CI built, scanned and attested.
- **All six health gates pass** against the deployed images — backend liveness, readiness, database
  health, OIDC issuer reachability, web UI, and a security smoke probe confirming a protected
  endpoint still requires authentication.
- **Digest guard works** — compose refuses to start when the controller has not exported immutable
  image references (`RAMALS_BACKEND_IMAGE is missing`), so a mutable tag can never be deployed.
- **State machine observed live** — `APPROVED → DEPLOYING → FAILED → RELEASE_HELD` (exit 3), including
  the "no known-good version recorded" branch, and **bounded transient retry with backoff** on a
  registry failure.

### Not demonstrated, and why

The full happy-path sequence — reconcile to `HEALTHY`, record a known-good digest, then deliberately
deploy a bad version and observe rollback **to that known-good** — was **not completed**. The host's
Docker Desktop repeatedly failed with `timed out dialing Hyper-V socket` and could not route
host→container published ports (the backend answered `200` from inside the network while the host got
connection refused). This is environmental instability on the validation host, not a defect in the
controller: the same sequence passes 14/14 in `scripts/ci/test-deploy-controller.sh`.

**R2 therefore remains open.** What is now proven is that the published digests deploy and pass health
gates; what remains is the rollback-to-known-good sequence on a stable host.

## 4. Known limitation discovered in the performance harness

The M0-T20 k6 scenarios authenticate with `grant_type=password` against `ramals-web-ui`. The shipped
realm has **direct access grants disabled** on that client and defines **no users**, so the k6
scenarios cannot authenticate against a stock deployment as written. They need the same runtime
fixture provisioning this drill performs. Recorded as a gap against R1.

## Reproducing

```bash
# Bring the stack up (ports overridable via RAMALS_*_PORT in .env)
cd infrastructure/docker && docker compose up -d --build

# Keycloak authorization drill, run inside the compose network
cat scripts/validation/keycloak-e2e.py | docker run --rm -i --network ramals_edge \
  -e RAMALS_KEYCLOAK_ADMIN -e RAMALS_KEYCLOAK_ADMIN_PASSWORD python:3.13-alpine python -

# Deployment from published digests
cd deploy && bash deploy-controller.sh
```
