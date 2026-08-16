# Live-stack evidence: Keycloak authorization and immutable-image deployment

Executed against a running Docker Compose stack on a local validation host, against the
`v0.1.0-rc1` published artefacts. This closes risks **R2** and **R3**.

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

## 3. Immutable-image deployment and rollback — pass (R2 closed)

`deploy/compose.deploy.yml` never builds: it consumes only the digests the controller exports from
the approved manifest.

The full sequence was executed against real containers and the real health gates:

| Step | Expected | Observed |
| --- | --- | --- |
| Reconcile approved version | `HEALTHY`, known-good recorded | exit 0, `HEALTHY`, digests recorded |
| Reconcile again | no-op | exit 0, "already reconciled" |
| Deploy a bad version | health gates fail, roll back, hold | exit 3, `RELEASE_HELD` |
| Verify what is running | the known-good digest | `ramals-learning-platform@sha256:a31d830a…` |
| Reconcile the held version | refuse | exit 2, nothing redeployed |
| Correct the manifest | deploy normally | exit 0, `HEALTHY` |

Also verified: **published images pull by digest** and run; **all six health gates pass**; the
**digest guard** refuses to start when the controller has not exported immutable references; and
bounded transient retry with backoff on a registry failure.

The bad version was constructed as a *pullable artefact that is the wrong application* — the web UI
image published under the backend's component — which is the realistic shape of a bad build. A
digest that simply fails to pull would only have exercised the transient-retry path.

### Defect found and fixed by this drill: the rollback never happened

The state machine printed `ROLLBACK → ROLLED_BACK` and wrote `current_commit: 36645cfc`, and all of
it was false. The container actually running was the **bad** artefact:

```
state file  current_commit : 36645cfc                                  <- known-good
actually running           : ghcr.io/skpandey15/ramals-web-ui@sha256:e527dd43…   <- the BAD image
```

**Root cause.** `deploy-controller.sh` exported `RAMALS_BACKEND_IMAGE`/`RAMALS_WEBUI_IMAGE` for the
desired (failing) version, then the rollback branch re-ran the same `pull`/`up` commands **without
changing those variables** — so it redeployed the version it was supposed to be backing out of.

Underneath that was a design gap: state recorded only `known_good_commit`, a git SHA. By the time a
rollback is needed the manifest already describes the bad version, so the controller had **no record
of which images to return to**. It could not have rolled back correctly however the branch was
written.

**Why the test suite missed it.** `scripts/ci/test-deploy-controller.sh` stubbed
`RAMALS_PULL_CMD`/`RAMALS_UP_CMD` as `true` and health as a constant. Those stubs never read the
image environment, so the suite passed 14/14 while asserting only on state-file *labels* — the exact
field the bug falsified.

**Fix.**

- State records `known_good_backend_image` / `known_good_webui_image` alongside the commit.
- The rollback re-exports those digests before `pull`/`up`.
- The rollback is **verified** by re-running the health gates; `ROLLED_BACK` is only claimed if they
  pass.
- `current_commit` now describes what is *running*: if the rollback did not happen or did not
  verify, the state file says the failed version is still deployed rather than reporting a success.
- A recorded known-good commit with no digests is reported as unrecoverable instead of silently
  producing a false rollback.

The test harness was rewritten so this cannot regress: the stubs now record the image reference they
were handed, health is a function of the artefact rather than a constant, and the suite asserts
which digest was actually deployed last. Against the unfixed controller the new suite fails 5 checks,
including `known-good digest was actually redeployed` returning the bad digest. It passes 20/20
against the fix.

### A near-miss worth recording

While the bad backend was deployed, `GET /actuator/health/liveness` returned **HTTP 200** — nginx's
SPA fallback serves `index.html` for any unmatched path. The gate failed correctly only because it
matches on the `"status":"UP"` body rather than the status code. A status-code-only probe would have
declared the wrong application healthy.

## 4. Health gates were run from inside the compose network

On this validation host (Rancher Desktop on Windows) `wsl-proxy` cannot forward a published port to
a container whose attachment includes an `internal` network. `backend` and `keycloak` are on both
`data` (internal) and `edge`, so their published ports are unreachable from Windows while `web-ui`,
which is on `edge` only, works. Confirmed by experiment: setting `data.internal: false` made Keycloak
reachable from the host immediately.

This is a limitation of the local port-forwarder, **not** of the compose topology — a Linux deploy
host routes to internal bridges normally, and `internal` restricts only external routing. The
topology was therefore left unchanged rather than weakening network isolation to suit local tooling.
The gates were instead run from inside `edge`, exercising the same script against the same endpoints
with the host forwarder removed from the measurement.

Earlier notes attributing this symptom to general Docker instability were wrong; the cause is
specific and now understood.

## 5. Known limitation discovered in the performance harness

The M0-T20 k6 scenarios authenticate with `grant_type=password` against `ramals-web-ui`. The shipped
realm has **direct access grants disabled** on that client and defines **no users**, so the k6
scenarios cannot authenticate against a stock deployment as written. They need the same runtime
fixture provisioning this drill performs. Recorded as a gap against R1.

## Reproducing

```bash
# Bring the stack up (ports overridable via RAMALS_*_PORT in .env)
cd infrastructure/docker && docker compose up -d --build

# Keycloak authorization drill, run inside the compose network
docker run --rm -i --network ramals-deploy_edge \
  -e RAMALS_KEYCLOAK_ADMIN -e RAMALS_KEYCLOAK_ADMIN_PASSWORD \
  python:3.13-alpine python - < scripts/validation/keycloak-e2e.py

# Deployment state machine, no container runtime required
bash scripts/ci/test-deploy-controller.sh

# Deployment from published digests
cd deploy && bash deploy-controller.sh
```
