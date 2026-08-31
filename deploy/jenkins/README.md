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

## Manual pipeline validation

```powershell
pwsh -NoProfile -File .\deploy\jenkins\deploy-main.ps1 -ValidateOnly
```

The full command deploys and runs smoke tests:

```powershell
pwsh -NoProfile -File .\deploy\jenkins\deploy-main.ps1
```
