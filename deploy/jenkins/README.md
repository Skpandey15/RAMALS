# Jenkins local k3d continuous deployment

Jenkins deploys qualified `main` commits to the local `ramals-dev` k3d cluster. GitHub Actions
continues to own tests, security checks, and merge qualification; Jenkins owns deployment only.

## Safety contract

- The job checks out only `Skpandey15/RAMALS` `main`.
- `deploy-main.ps1` independently verifies the origin and exact `origin/main` SHA.
- Deployments are serialized with `disableConcurrentBuilds()`.
- The canonical `deploy/k8s/dev/bootstrap.ps1` and `smoke.ps1` remain the deployment authority.
- No repository credential is needed because the repository is public.
- Runtime secrets remain in Kubernetes/Windows/Jenkins storage and are never archived.
- Jenkins archives only pod, workload, image, smoke, and commit evidence.

## Local controller

The local installation runs under the Windows user that owns Rancher Desktop and the kubeconfig.
This is intentional: a Windows service account would not automatically have access to that desktop
Docker daemon or Kubernetes context. The controller binds to `127.0.0.1:8090`.

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
