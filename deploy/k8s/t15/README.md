# M2-T15.1/T15.2 k3d qualification environment

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

The foundation smoke is separate from the T14 crash gate. The real crash gate is recorded below and
must remain separate from performance or security qualification.

## M2-T15.2 real crash/pod-death qualification — PASS

On 2026-08-24, `crash-qualification.ps1 -Scenario all` ran the ordered matrix against the
digest-pinned topology. The final clean run is recorded in
[`evidence/m2-t15.2-20260824T062227Z/SUMMARY.tsv`](evidence/m2-t15.2-20260824T062227Z/SUMMARY.tsv).
The tested backend and AI image pins were respectively
`sha256:2c37abd7973ea62085700e5588fdd70bc783e991f808a25cea0363b6c1e3e0df` and
`sha256:9af2b6ab6eb1ebe60df9b05ff754cdab43cd8a3835945b6b99b699026c9ed3f6`.

| Scenario | Fault boundary | Result |
| --- | --- | --- |
| `after-claim` | backend pod after workflow claim | `COMPLETED`; evidence step reclaimed at attempt 2 |
| `after-evidence-effect` | backend pod after evidence effect | `COMPLETED`; evidence lineage remains single, attempt 2 |
| `after-mastery-effect` | backend pod after mastery effect | `COMPLETED`; one snapshot at aggregate version 1, attempt 2 |
| `diagnostic-commission` | backend pod after diagnostic commission | fail-closed `FAILED`; one commission, no duplicate dispatch |
| `diagnostic-provider` | AI pod during diagnostic provider execution | fail-closed `FAILED`; one provider boundary/commission |
| `diagnostic-outcome-commit` | backend pod after atomic execution + gate commit | `COMPLETED`; diagnostic step reclaimed at attempt 2 |
| `adaptation-handoff` | backend pod around adaptation outbox handoff | `COMPLETED`; one outbox row, adaptation attempt 2 |
| `adaptation-commission` | backend pod after adaptation commission | `COMPLETED`; outbox terminal abandonment at attempt 2, one failed execution |
| `contention` | two backend replicas contend for one workflow | `COMPLETED`; one winner, all step attempts 1 |

Every scenario asserted one evaluation evidence lineage, one mastery snapshot, monotonic aggregate
version, one diagnostic execution with one `STARTED` and one terminal event, no duplicate adaptation
outbox or execution, stale workflow-token rejection, and complete request/interaction/trace/provenance
reconstruction. Cursor history includes the boundary observation and the recovered terminal state;
the harness rejects any backwards step index. Stale outbox lease CAS also returned zero where an
outbox lease was exercised.

The qualification exposed and then closed a real PostgreSQL commission replay race: duplicate-key handling
previously queried an aborted transaction (`25P02`) under concurrent adaptation recovery. Commission
now uses `ON CONFLICT DO NOTHING` before reading the existing event, and the final rerun passed.
The final namespace posture is two ready backend replicas, two ready AI replicas, zero pending or
claimed outbox rows, and qualification faults disabled.

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
