# M2-T15.1/T15.2 k3d qualification environment

This directory is the isolated Kubernetes qualification environment for M2-T15. It is not a
production deployment and it does not activate a production trigger. Docker Compose remains the
local developer smoke/integration environment and is intentionally unchanged.

The manifests consume the exact reviewed-candidate artifacts recorded in
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
$lock = Get-Content .\deploy\k8s\t15\images.lock.json -Raw | ConvertFrom-Json
$approvedCommit = [string]$lock.sourceCommit
$approvedRef = "origin/main"
pwsh -File .\deploy\k8s\t15\bootstrap.ps1
pwsh -File .\deploy\k8s\t15\smoke.ps1 -ApprovedCommit $approvedCommit -ApprovedRef $approvedRef
```

For a new candidate, first resolve and review one full commit, then build from that commit's
detached worktree. `publish-images.ps1` builds and pushes the backend, AI, web, PostgreSQL, and
Keycloak artifacts with immutable digests and can update the lock/manifests together:

```powershell
$reviewedCandidateCommit = Read-Host "Enter the reviewed 40-character candidate commit"
pwsh -File .\deploy\k8s\t15\publish-images.ps1 -Commit $reviewedCandidateCommit -UpdateLock
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
  -ApprovedRef $approvedRef `
  -SelfTest
```

For an existing deployment, `approvedCommit` must come from the reviewed lock (or be supplied as an
explicit reviewed SHA); `origin/main` is only the `ApprovedRef` used for ancestry validation. The
smoke helper defaults `-ApprovedCommit` to `images.lock.json.sourceCommit` for this reason.

`-SelfTest` deliberately exercises candidate/ref A, candidate A with descendant ref B (including the
post-merge lock-candidate-A/origin-main-descendant-B case), an unreachable
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

The two-replica contention case has an additional deterministic qualification boundary. It
pre-creates the target step as `PENDING`, holds that exact PostgreSQL row with the
`qualification-coordinator.ps1` helper, and observes `pg_stat_activity` claim sessions blocked by
the helper transaction. Each session is mapped from its client IP to a live backend pod UID. The
scenario is allowed to pass only when the evidence contains exactly two distinct PostgreSQL claim
sessions from two distinct backend pod UIDs, one durable `WON` token and one `LOST` CAS result. The
raw barrier and durable proof are retained as `claim-barrier.json`, `claim-attempts.json`,
`postgres-claim-after.json`, and `postgres-claim-final.json`; pod count alone is not evidence of a
second attempt. `contention-proof.tests.ps1` includes the negative perturbation that removes the
second attempt and verifies that the proof fails.

## Deterministic stale-worker race design — qualification required

The stale-worker scenario uses two independently releasable PostgreSQL advisory gates rather than
elapsed-time inference. A temporary T15-only trigger is scoped to the exact evaluation-evidence
lineage for the generated run. Its control row initially routes the first blocked application PID to
gate A; after that PID is captured, every distinct PID routes to gate B.

1. Backend replica A claims the step and its real evidence insert blocks on gate A before any
   authoritative effect. The harness immediately persists `claimant-a.json` and A's logs, then
   confirms PostgreSQL still shows A's token and attempt as `RUNNING`.
2. One qualification update makes only that run/step/token/attempt lease reclaimable. Replica B
   claims the same row with a new token and incremented attempt, blocks independently on gate B, and
   is persisted to `claimant-b.json`.
3. Gate A alone is released. The original application transaction resumes through normal code and
   must emit the superseded-completion result from the production token CAS while B remains blocked.
4. Gate B is released only after the stale-A snapshot is durable. Final PostgreSQL proof requires
   one B completion, bounded attempts, one effect per authoritative lineage, monotonic cursor state,
   and no duplicate provider or outbox work.

Both gates, the trigger, its one control row, and helper pods are removed during evidence-first
teardown. None is present in application manifests or enabled outside the isolated T15 run.

## Observability remediation status

The focused trace-ID remediation makes MDC the sole structured owner of `interactionId`, `traceId`,
and `spanId`. Workflow and AI hand-off workers rebind persisted correlation around their log events;
they do not add a second fluent field. The serialized-log regression exercises workflow start,
success, rejection, failure, exception, asynchronous worker, and valid W3C-parent paths, including
the original duplicate-field mutation.

This does not qualify any T15.2 crash scenario. A new candidate must be cut and attested after the
application change is reviewed and merged; the frozen T15 lock is unchanged.

## Deferred Phase-G prerequisites

NetworkPolicy coverage and `TD-M2-SEC-01` remain deferred to Phase G. The isolated namespace is
restricted and locally scoped for this harness, but this task does not claim the broader security
qualification.

## Qualification-only fault coordination

[`qualification-coordinator.ps1`](qualification-coordinator.ps1) can start a temporary PostgreSQL
client pod using the already pinned PostgreSQL artifact and hold an explicitly selected row, table,
or advisory lock. It uses database-admin credentials from the runtime Secret, never application
credentials. The helper is intentionally outside the application manifests. Qualification runners
may install a temporary, lineage-scoped trigger solely to route a real application statement to an
advisory gate; teardown removes it before restoring the namespace. This does not change business
logic or assert that a lock alone is an exact lifecycle boundary. Later T15 crash
scenarios must pair the lock with trace/log evidence, PostgreSQL lock evidence, an actual pod death,
recovery checks, and a clean teardown.

Missing host tools such as `psql`, `k6`, `trivy`, `cosign`, `syft`, and `grype` should be run from
pinned helper containers or CI. They are not installed into application images.
