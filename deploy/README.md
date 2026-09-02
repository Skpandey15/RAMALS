# RAMALS MVP-0 deployment (controlled pull-based)

Build once, promote the same artifact. CI publishes immutable images and **never pushes to an
environment**; controlled environments **pull** an approved desired-version manifest. There is no deployment
credential in CI, so a pull request cannot reach an environment even if it could run code.

The local Jenkins development path is intentionally faster: after human approval it deploys the
qualified images for the exact current `main` commit, verifies their OCI revisions, and records their
resolved immutable digests. It does not use `desired-version.json`; staging/production-style paths do.

## Pipeline

```
PR (untrusted, read-only, no secrets)
        |  merge
        v
main / v* tag  ->  Release workflow (trusted ref only)
        |            build -> push GHCR (sha-<commit>) -> SBOM -> Trivy gate -> provenance attestation
        v
approved desired-version.json   (human approval; explicit immutable digests)
        |            pull
        v
dev host: deploy-controller.sh  ->  health gates  ->  HEALTHY
                                        \-> FAILED -> ROLLBACK -> ROLLED_BACK -> RELEASE_HELD
```

## Desired version is explicit

`desired-version.json` pins every component to an immutable `sha256:` digest. The controller
**refuses to deploy** anything that is not a digest — `:latest` and `:main` are never deploy targets.
`:main` exists only as a human convenience pointer.

## Release hold and anti-flapping

A version that fails health gates is rolled back to the last known-good digest and recorded in
`held_versions`. On the next reconcile the controller sees the desired commit is held and **exits
without redeploying** (exit code `3` on the failing run, `2` on a subsequent held reconcile). This
prevents a broken release from flapping every polling interval.

To release the hold, a human corrects the manifest to a new digest, or explicitly re-approves the
failed one by removing it from `held_versions` in the state file. Bounded retry with backoff applies
**only** to transient registry/network pull failures — never to a health-gate failure.

## State

`deploy/.deploy-state.json` (git-ignored) records:

| Field | Meaning |
| --- | --- |
| `state` | `APPROVED` / `DEPLOYING` / `HEALTHY` / `FAILED` / `RELEASE_HELD` |
| `current_commit` | what is running |
| `known_good_commit` | rollback target |
| `held_versions` | commits blocked from automatic redeploy |
| `failure_count` | consecutive failures |

## Running

```bash
# On the dev host, from a timer/cron:
RAMALS_DESIRED_MANIFEST=deploy/desired-version.json ./deploy/deploy-controller.sh
```

## M2-T15 qualification

The qualification environment is separate from the Compose and pull-based deployment paths. It is
an isolated k3d cluster with digest-pinned artifacts from the explicitly reviewed lock candidate:

```powershell
$lock = Get-Content .\deploy\k8s\t15\images.lock.json -Raw | ConvertFrom-Json
$approvedCommit = [string]$lock.sourceCommit
$approvedRef = "origin/main"
pwsh -File .\deploy\k8s\t15\bootstrap.ps1
pwsh -File .\deploy\k8s\t15\smoke.ps1 -ApprovedCommit $approvedCommit -ApprovedRef $approvedRef
```

This path does not replace Docker Compose or add production trigger wiring. The candidate-integrity
gate accepts a frozen candidate when it is reachable from the approved main ref; baseline smoke is the
only M2-T15 check in the first qualification task. Crash, performance, and Phase-G security
qualification remain pending. See [`k8s/t15/README.md`](k8s/t15/README.md) for the candidate lock,
evidence schema, topology, secret handling, and deferred qualification rules. `smoke.ps1` also reads
the lock's `sourceCommit` when `-ApprovedCommit` is omitted.

Exit codes: `0` reconciled/healthy · `1` transient failure exhausted · `2` desired version is held ·
`3` deployed but failed health gates, rolled back and now held.

## Database safety

Application rollback is not database rollback. Migrations are expand/contract and
backward-compatible: add nullable/new structures first, migrate usage, remove old structures only in
a later controlled release. Do not assume rolling an image back reverses a destructive schema change.
