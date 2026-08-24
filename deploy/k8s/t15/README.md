# M2-T15.1/T15.2 k3d qualification environment

This directory is the isolated Kubernetes qualification environment for M2-T15. It is not a
production deployment and it does not activate a production trigger. Docker Compose remains the
local developer smoke/integration environment and is intentionally unchanged.

The manifests consume the exact approved-main artifacts recorded in
[`images.lock.json`](images.lock.json) by digest. The `current-main` image names in
`kustomization.yaml` are only Kustomize transformer keys; the rendered and applied objects contain
`image@sha256:...` references.

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
$approvedCommit = (git rev-parse --verify origin/main).Trim()
pwsh -File .\deploy\k8s\t15\bootstrap.ps1
pwsh -File .\deploy\k8s\t15\smoke.ps1 -ApprovedCommit $approvedCommit
```

For a new candidate, first resolve and review one full commit, then build from that commit's
detached worktree. `publish-images.ps1` builds and pushes the backend, AI, web, PostgreSQL, and
Keycloak artifacts with immutable digests and can update the lock/manifests together:

```powershell
$approvedCommit = (git rev-parse --verify origin/main).Trim()
pwsh -File .\deploy\k8s\t15\publish-images.ps1 -Commit $approvedCommit -UpdateLock
```

Do not replace the explicit commit with `HEAD` or a mutable image tag. The lock is a reviewable
candidate declaration; deployment is not qualified until the candidate-integrity gate passes.

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

## Candidate-integrity gate — M2-T15.1

[`candidate-integrity.ps1`](candidate-integrity.ps1) is the first substantive qualification check.
It requires a full 40-character approved commit and fails with a non-zero exit code on any mismatch.
The approved ref may advance after the candidate is frozen: it must resolve successfully and contain
the candidate commit as an ancestor.
The gate proves this chain:

```text
approved candidate commit + tree, reachable from approved main ref
        -> reviewed image lock digests
        -> rendered Kustomize manifest and hashes
        -> live deployment intent and resolved pod imageIDs
```

It independently compares the approved migration set with PostgreSQL Flyway history, rejects every
failed migration, and requires V034 to be successful. A passing
`candidate-integrity.json` records the commit/tree, lock and manifest hashes, all image digests,
live pod imageIDs and UIDs, Kubernetes/k3d versions, namespace, replica counts, safe feature/config
values, migration history, checks, and UTC capture time.

Run the gate directly when establishing or reviewing a candidate:

```powershell
pwsh -File .\deploy\k8s\t15\candidate-integrity.ps1 `
  -ApprovedCommit $approvedCommit `
  -SelfTest
```

`-SelfTest` deliberately exercises candidate/ref A, candidate A with descendant ref B, an unreachable
candidate B with ref A, and a lock source-commit mismatch, in addition to mutating the migration set,
backend image, and rendered manifest in temporary copies. Each mutation must fail for its expected
check. It also bypasses the ancestry guard in a temporary script and verifies that the unreachable
candidate case would otherwise pass, proving the negative test detects guard removal. The committed
shape is defined by [`evidence-schema.json`](evidence-schema.json), with a redacted structural example
in [`evidence-example.json`](evidence-example.json). The example is documentation only and cannot be
used as qualification evidence.

When `publish-images.ps1` is run without `-UpdateLock`, it writes the proposed lock to
`images.lock.proposed.json` by default. Use `-ProposedLockPath` to choose another persistent review
location; the temporary build metadata directory is still cleaned up after the run.

## Evidence and checks

`smoke.ps1` invokes the candidate gate before health assertions and records the gate output alongside
the rendered manifest, cluster/node state, workload state, pod image IDs, rollout output, events, and
health responses under `evidence/<UTC-stamp>/`. It checks:

- two ready Spring replicas and two ready AI replicas;
- ready PostgreSQL, Keycloak, and web UI;
- successful workload-client bootstrap;
- a real Keycloak client-credentials token with the `ramals-ai` audience, unauthenticated refusal,
  and authenticated proposal response;
- deployment image intent and resolved container digest equality with `images.lock.json`;
- Spring readiness, AI readiness/capabilities, Keycloak management readiness, and NGINX health.

Evidence directories are ignored by Git. Do not copy Secret objects or decoded Secret data into
evidence. A PASS is valid only when the evidence directory contains a passing candidate gate for
the same run; a stale or drifted candidate fails before smoke or crash work starts.

The foundation smoke is separate from the T14 crash gate and remains separate from performance or
security qualification.

## M2-T15.2 real crash/pod-death qualification — not claimed

The real crash matrix, performance qualification, and Phase-G security work have not been run by this
candidate-integrity task. The pre-existing `evidence/m2-t15.2-*` directories are historical local
artifacts from an earlier candidate and are not evidence for the approved commit above. They must not
be used to claim T15.2 completion.

`crash-qualification.ps1` now requires `-ApprovedCommit`, reruns the same candidate gate before
creating a fixture or arming a fault, and emits one structured scenario record with the following
links for each future perturbation:

- scenario ID and candidate identity;
- PostgreSQL pre/post state, claim owner/token/attempt count, cursor history, decision/outbox/AI
  execution correlation;
- exact perturbation, deleted and replacement pod names/UIDs, scoped Kubernetes events, and
  correlation-filtered surviving/deleted-pod logs;
- expected invariant, observed invariant, and PASS/FAIL result.

The generated scenario shape is `m2-t15.scenario-evidence.v1`. No real crash scenario is a PASS from
this task.

## Deterministic stale-worker race design — not qualified

The eventual stale-worker run must use a qualification-only barrier, not pod deletion timing:

1. Backend replica A claims the workflow and is held at `WORKFLOW_AFTER_CLAIM`; record A's pod UID,
   token, and attempt count from PostgreSQL.
2. Expire only A's lease through the qualification PostgreSQL control, then enable replica B and
   wait for PostgreSQL to show B's distinct token and incremented attempt.
3. Release A at an explicit barrier after B owns the row; A resumes and submits completion with its
   old token.
4. Assert PostgreSQL rejects A's token CAS, while B is the sole owner and sole authoritative-effect
   producer. Capture both owners, tokens, attempts, cursor, and the rejected row count.

The current pod-death harness records the primitives needed for this run, but pod deletion alone
cannot prove the stale-worker race and no such qualification claim is made here.

## Known observability follow-up

The trace-ID investigation found that `LearningWorkflowOrchestrator.log()` adds `traceId` while the
structured Logstash encoder also serializes the MDC `traceId`. In affected workflow transition
records this can raise `IllegalStateException: The name 'traceId' has already been written` and make
observability reconstruction incomplete. No application code is changed in this task; a separate
focused remediation PR is required before relying on those records for full crash qualification.

## Deferred Phase-G prerequisites

NetworkPolicy coverage and `TD-M2-SEC-01` remain deferred to Phase G. The isolated namespace is
restricted and locally scoped for this harness, but this task does not claim the broader security
qualification.

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
