# RAMALS local Kubernetes development environment

The ordinary environment for local development and integration testing. Two things it is **not**:

- Not `deploy/k8s/t15`, which is the M2-T15 qualification environment — digest-pinned to an approved
  candidate commit, gated by `candidate-integrity.ps1`, and carrying evidence harnesses. Do not run
  qualification machinery here, and do not treat results from here as qualification evidence.
- Not AWS DEV (`infrastructure/terraform`). That environment is ECS Fargate behind an ALB with
  security groups; this one is k3s in containers. They agree on architecture, not on mechanism.

## Architecture

```
Windows
  -> Rancher Desktop            (built-in Kubernetes DISABLED)
    -> moby / dockerd           (container runtime)
      -> k3d                    (k3s in Docker)
        -> cluster ramals-dev   (1 server, 1 agent)
          -> namespace ramals-dev
             |- postgres            StatefulSet, PVC, ClusterIP
             |- keycloak            OIDC issuer
             |- learning-platform   authoritative Spring core
             |- ramals-ai           non-authoritative AI plane
             `- web-ui              NGINX
```

Rancher Desktop supplies the container runtime **only**. Its own Kubernetes stays off: two
Kubernetes distributions on one workstation compete for kubeconfig contexts and ports, and the one
you did not mean to talk to is the one you end up debugging. See
`docs/adr/M2-ADR-021-local-kubernetes-environment.md`.

## Prerequisites

| Tool | Purpose | Check |
|---|---|---|
| Rancher Desktop | container runtime (engine: `moby`, Kubernetes: **disabled**) | `docker info` |
| `k3d` | creates the k3s cluster | `k3d version` |
| `kubectl` | matched to the cluster's k3s minor version | `kubectl version --client` |
| `helm` | not required by this package; used elsewhere | `helm version` |

`kubectl` and `k3d` must be on `PATH`. Rancher Desktop only places tools in `~/.rd/bin` when its
path-management setting is not `manual`; if that directory is empty, install `kubectl` yourself and
put it on `PATH` rather than relying on Rancher Desktop to provide it.

## Usage

```powershell
pwsh -File .\deploy\k8s\dev\bootstrap.ps1     # registry + cluster + images + deploy
pwsh -File .\deploy\k8s\dev\smoke.ps1         # prove it works
pwsh -File .\deploy\k8s\dev\teardown.ps1      # remove the cluster
```

`bootstrap.ps1` is safe to re-run: it creates only what is missing and keeps the existing Secret so
a redeploy does not invalidate the passwords PostgreSQL initialised its roles with. Pass
`-SkipBuild` to redeploy manifests without rebuilding images.

It **refuses to run against a dirty working tree**. Images are tagged with the short commit sha, so
a dirty tree would produce an image labelled with a commit whose contents it does not have — a tag
that lies is worse than no tag.

## Images

Built from the checked-out commit and served by a dedicated k3d registry:

| Image | Built from |
|---|---|
| `ramals-postgres` | `infrastructure/docker/postgres-init/Dockerfile` |
| `ramals-keycloak` | `infrastructure/docker/keycloak/Dockerfile` |
| `ramals-ai` | `ramals-ai/Dockerfile` |
| `ramals-web-ui` | `web-ui/Dockerfile` |
| `ramals-learning-platform` | `learning-platform/Dockerfile` |

All five build from the **repository root** as context. Tags are the short git sha; `:latest` and
`:local` are never deploy targets, because a moving tag cannot be traced back to a commit.

Push goes to `localhost:5000`; the cluster pulls the same repositories as
`k3d-ramals-registry:5000/...`. Both names address one registry, so one push serves both.

## Developer access

Every Service is `ClusterIP`. There is no Ingress, no NodePort, and the cluster is created with no
host port mappings — so nothing is reachable from the host until you ask for it:

```powershell
kubectl -n ramals-dev port-forward svc/web-ui 5173:8080            # http://localhost:5173
kubectl -n ramals-dev port-forward svc/learning-platform 8080:8080 # http://localhost:8080
kubectl -n ramals-dev port-forward svc/keycloak 8081:8080          # http://localhost:8081
kubectl -n ramals-dev port-forward svc/ramals-ai 8000:8000         # http://localhost:8000/health/ready
```

**Do not port-forward PostgreSQL as a matter of course.** It is cluster-internal by design; forward
it only for a specific debugging task and stop the forward afterwards.

## Contract B and AI providers

Off, and asserted by `smoke.ps1` rather than assumed:

```
RAMALS_CONTRACT_B_ENABLED=false
RAMALS_CONTRACT_B_RECONCILIATION_ENABLED=false
RAMALS_CONTRACT_B_PURGE_ENABLED=false
RAMALS_AI_DURABLE_EXECUTION_ENABLED=false
RAMALS_AI_AI_ENABLED=false
RAMALS_AI_MODEL_ROUTE=ci-fake
```

No Anthropic or OpenAI credential is configured, and none belongs in this environment. Contract B
must not be activated anywhere until residual S2 is resolved and separately reviewed
(`docs/mvp2-contract-b-approval.md`).

## Isolation

`network-policy.yaml` restricts the AI plane's egress to DNS only. It therefore cannot open a
connection to PostgreSQL — the architectural invariant that the AI plane is non-authoritative and
has no path to the authoritative database, enforced by the cluster rather than by convention.

`smoke.ps1` checks this as a **negative control**: it asserts the connection *fails*, and fails the
run if the AI plane can reach the database. A path that is never exercised looks exactly like a path
that is blocked, so the test has to try it.

## Secrets

`bootstrap.ps1` generates five random passwords in memory per environment and creates the
`ramals-dev-runtime` Secret from them. They are never written to disk, never printed, and never
committed. They are development-only credentials with no meaning outside this cluster.
