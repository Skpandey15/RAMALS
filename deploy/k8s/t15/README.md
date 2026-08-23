# M2-T15.1 k3d qualification foundation

This directory is the isolated Kubernetes qualification environment for M2-T15. It is not a
production deployment and it does not activate a production trigger. Docker Compose remains the
local developer smoke/integration environment and is intentionally unchanged.

The manifests consume the current-main artifacts recorded in [`images.lock.json`](images.lock.json)
by digest. The `current-main` image names in `kustomization.yaml` are only Kustomize transformer
keys; the rendered and applied objects contain `image@sha256:...` references.

## Topology

The initial topology is deliberately small but exercises the real multi-process coordination path:

| Component | Replicas | Role |
| --- | ---: | --- |
| `learning-platform` | 2 | authoritative Spring core and M2-T14 pollers |
| `ramals-ai` | 2 | non-authoritative AI proposal plane, `test` + `ci-fake` |
| `postgres` | 1 | authoritative database with a persistent volume |
| `keycloak` | 1 | OIDC issuer and workload-client authority |
| `web-ui` | 1 | immutable NGINX UI artifact |
| `keycloak-client-bootstrap` | Job | qualification-only secret bootstrap |
| `workload-identity-smoke` | Job | live OIDC/workload-boundary check |

The `backend` Service is an alias for the two `learning-platform` pods because the existing NGINX
configuration proxies `/api/` to `backend:8080`. PostgreSQL is cluster-internal; the supported
operator access path is a port-forward, not an externally exposed database port.

## Prerequisites and startup

Install Docker/Rancher Desktop, `k3d`, `kubectl`, and PowerShell. From the repository root:

```powershell
pwsh -File .\deploy\k8s\t15\bootstrap.ps1
pwsh -File .\deploy\k8s\t15\smoke.ps1
```

For a new qualification artifact set, use `publish-images.ps1` to build and push all five images to
the local registry. It reports digests but intentionally does not rewrite the lock file; review the
new digest set, then update the lock/manifests together.

`bootstrap.ps1` creates an isolated `t15` cluster with two k3d agents and a local registry on
`localhost:5111` when they do not already exist. It creates the `ramals-t15-runtime` Secret only
when the namespace does not have one. Passwords are generated in memory; they are not committed,
printed, or written to an evidence file. For a repeatable local run, the following environment
variables may be set before bootstrap (the names are intentionally qualification-specific):

```text
RAMALS_T15_DB_ADMIN_PASSWORD
RAMALS_T15_DB_MIGRATION_PASSWORD
RAMALS_T15_DB_RUNTIME_PASSWORD
RAMALS_T15_KEYCLOAK_DB_PASSWORD
RAMALS_T15_KEYCLOAK_ADMIN_PASSWORD
RAMALS_T15_WORKLOAD_CLIENT_SECRET
```

The imported realm intentionally contains no workload-client secret. The bootstrap Job sets the
runtime-only value on `ramals-core-workload` through `kcadm.sh`; the same value is injected into the
Spring core. It is never placed in the realm JSON or a manifest.

The AI plane is configured as `RAMALS_AI_ENVIRONMENT=test`, `RAMALS_AI_AI_ENABLED=true`, and
`RAMALS_AI_MODEL_ROUTE=ci-fake`. This makes the real authenticated provider path deterministic and
non-billable. It is not a production model configuration.

## Evidence and checks

`smoke.ps1` records the rendered manifest, its SHA-256, cluster/node state, workload state, pod
image IDs, rollout output, events, and health responses under `evidence/<UTC-stamp>/`. It checks:

- two ready Spring replicas and two ready AI replicas;
- ready PostgreSQL, Keycloak, and web UI;
- successful workload-client bootstrap;
- a real Keycloak client-credentials token with the `ramals-ai` audience, unauthenticated refusal,
  and authenticated proposal response;
- deployment image intent and resolved container digest equality with `images.lock.json`;
- Spring readiness, AI readiness/capabilities, Keycloak management readiness, and NGINX health.

Evidence directories are ignored by Git. Do not copy Secret objects or decoded Secret data into
evidence.

The first T15 gate is still the T14 crash qualification. This foundation smoke is not that gate and
does not close T14 activation prerequisite #3. The crash gate must still kill real processes/pods at
the claim/effect/marker/cursor boundaries and verify the durable invariants before performance or
security qualification begins.

## Qualification-only fault coordination

[`qualification-coordinator.ps1`](qualification-coordinator.ps1) can start a temporary PostgreSQL
client pod using the already pinned PostgreSQL artifact and hold an explicitly selected row or table
lock. It uses database-admin credentials from the runtime Secret, never application credentials.
The helper is intentionally outside the application manifests. It does not add trigger wiring,
change business logic, or assert that a lock alone is an exact lifecycle boundary. Later T15 crash
scenarios must pair the lock with trace/log evidence, PostgreSQL lock evidence, an actual pod death,
recovery checks, and a clean teardown.

Missing host tools such as `psql`, `k6`, `trivy`, `cosign`, `syft`, and `grype` should be run from
pinned helper containers or CI. They are not installed into application images.
