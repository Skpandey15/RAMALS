# Jenkins local k3d continuous deployment

Jenkins deploys qualified `main` commits to the local `ramals-dev` k3d cluster. GitHub Actions
continues to own tests, security checks, and merge qualification; Jenkins owns deployment only.
GitHub Actions builds the application images once and publishes them to GHCR. For local/dev, Jenkins
waits for the images tagged for its exact checked-out `main` commit, resolves them to immutable
digests, verifies their OCI revision, and mirrors them into the local k3d registry,
builds only the PostgreSQL and Keycloak development infrastructure images, and deploys with
`-SkipBuild` so application code is never rebuilt during deployment.

## Safety contract

- The job checks out only `Skpandey15/RAMALS` `main`.
- `deploy-main.ps1` independently verifies the origin and exact `origin/main` SHA.
- The trusted `main` SHA is both the deployment-configuration identity and local/dev application
  identity. Jenkins refuses an image whose OCI revision differs from that SHA.
- `deploy/desired-version.json` remains the explicit promotion boundary for controlled pull-based
  environments; the local Jenkins path deliberately does not use it.
- Deployments are serialized with `disableConcurrentBuilds()`.
- The canonical `deploy/k8s/dev/bootstrap.ps1` and `smoke.ps1` remain the deployment authority.
- No repository credential is needed because the repository is public.
- Runtime secrets remain in Kubernetes/Windows/Jenkins storage and are never archived.
- Jenkins archives only pod, workload, image, smoke, and commit evidence.

## Failure contract

Deployment is not just "apply and hope". The push path runs the same state machine the pull-based
`deploy/deploy-controller.sh` runs for the shared environment:

```text
APPROVED -> DEPLOYING -> HEALTHY
                     \-> FAILED -> ROLLBACK -> ROLLED_BACK -> RELEASE_HELD
```

- The image reference every release-managed workload is running is captured **before** anything is
  applied. A commit alone is not a rollback target: by the time a rollback is needed the workspace
  already describes the bad version.
- A release becomes the known-good rollback target **only after its smoke suite passes**. A version
  that has not proved itself is never something to return to.
- If the deployment or the smoke suite fails, the captured workloads are restored with
  `kubectl set image` against the exact recorded references — not `rollout undo`, which walks back
  one revision and is only correct if exactly one deployment happened since.
- A partial restore is reported as a **failure**, never as a rollback. A cluster half on the old
  version and half on the new one is precisely what this exists to avoid.
- The failed commit is then **HELD** and is not deployed again automatically. Without this, a bad
  commit at the head of `main` would be redeployed by the two-minute poll, fail, roll back, and
  repeat indefinitely — taking the environment down and up on every cycle.
- Overriding a hold is a deliberate act: re-run the job with `FORCE_HELD_RELEASE`, which is for the
  case where the environment was at fault rather than the commit.

State lives at `%LOCALAPPDATA%\RAMALS\cd-state\<cluster>-<namespace>.json`, deliberately outside the
Jenkins workspace — `deleteDir()` empties that workspace on every build, so state kept there would
be destroyed exactly when it is needed: on the run after the one that failed.

`summary.json` carries `state`, `rolledBack` and `rolledBackTo` alongside the existing evidence.

## Local controller

The local installation runs under the Windows user that owns Rancher Desktop and the kubeconfig.
This is intentional: a Windows service account would not automatically have access to that desktop
Docker daemon or Kubernetes context. The controller binds to `127.0.0.1:8090`.
After sign-in, Blue Ocean is available at `http://127.0.0.1:8090/blue/`.

The `RAMALS-main` pipeline polls GitHub every two minutes. A webhook can replace polling when the
machine has a safely authenticated inbound endpoint.

### Start Jenkins automatically after Windows sign-in

After the controller has been installed once, register its user-mode Windows logon task:

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\deploy\jenkins\configure-autostart.ps1 -StartNow
```

This creates or reconciles the `RAMALS-Jenkins-Local` Scheduled Task for the current Windows user.
The task runs with limited interactive-user privileges, starts Jenkins only when the loopback
controller is not already responding, and therefore preserves access to Rancher Desktop and the
current user's kubeconfig without creating duplicate Jenkins controllers.

After that one-time setup the normal flow is automatic:

```text
Windows sign-in
  -> RAMALS-Jenkins-Local starts Jenkins
  -> RAMALS-main polls GitHub main every two minutes
  -> a new main commit triggers the Jenkins pipeline
  -> deploy-main.ps1 validates the exact trusted main commit
  -> ramals-admin approves or rejects the local/dev k3d deployment
  -> Jenkins waits for and verifies GHCR images for that exact main commit
  -> deploy/k8s/dev/bootstrap.ps1 deploys to ramals-dev
  -> deploy/k8s/dev/smoke.ps1 validates the deployment
```

You do not need to rerun `install-local.ps1` for normal deployments. Use it only to install or
reconcile the Jenkins controller itself.

## Manual pipeline validation

```powershell
pwsh -NoProfile -File .\deploy\jenkins\deploy-main.ps1 -ValidateOnly
```

The full command deploys and runs smoke tests:

```powershell
pwsh -NoProfile -File .\deploy\jenkins\deploy-main.ps1
```
