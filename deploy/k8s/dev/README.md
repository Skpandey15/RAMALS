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

The cluster publishes **one** host port, 8080, to the Traefik load balancer. Everything else is
`ClusterIP`, so that single mapping is the only way in and PostgreSQL cannot be reached from the
host at all.

| URL | Serves |
|---|---|
| http://localhost:8080 | the RAMALS UI (its NGINX proxies `/api/` to the platform) |
| http://keycloak.localhost:8080 | Keycloak |

`*.localhost` resolves to 127.0.0.1 in Chrome, Edge and Firefox without a hosts-file entry.

No `kubectl port-forward` is needed for normal use. For a one-off look at something not exposed --
the platform's actuator, say -- forward it deliberately and stop the forward afterwards.

### The single-issuer rule

The browser and the platform must use the **same** Keycloak URL. Keycloak stamps `iss` with the host
it was reached on; the platform fetches JWKS from the issuer it is configured with. Point the
browser at one host and the platform at another and login succeeds while every subsequent API call
fails 401 -- a failure that looks like a permissions problem and is not.

Three things therefore have to agree, and `bootstrap.ps1` sets all three:

1. `VITE_KEYCLOAK_URL=http://keycloak.localhost:8080`, baked into the web-ui image at build time
2. `RAMALS_OIDC_ISSUER_URI` in `configmap.yaml`, the same URL
3. a CoreDNS rewrite mapping `keycloak.localhost` to the in-cluster Keycloak Service

### VITE_API_BASE_URL must be empty

`api.ts` already prefixes every request with `/api/v1/...`. A non-empty base produces
`/api/api/v1/...`, which Spring has no route for; it answers `404 RESOURCE_NOT_FOUND` from
`NoResourceFoundException` and the UI renders "Not found" panels that look like missing data. The
`Dockerfile` asserts the keycloak URL reached the bundle so that class of mistake fails the build
rather than the browser.

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

Contract B must not be activated anywhere until residual S2 is resolved and separately reviewed
(`docs/mvp2-contract-b-approval.md`). That is independent of everything below: live provider
execution and Contract B are separate switches answering different questions.

### Enabling a real provider (opt-in)

Off by default. A fresh checkout deploys with `ci-fake` and makes no provider call, so a developer
without a key gets a working cluster rather than a `CrashLoopBackOff` — `config/settings.py` fails
closed on a live route with no credential, which is correct behaviour and precisely why it must not
be the default.

```powershell
pwsh -File .\deploy\k8s\dev\bootstrap.ps1 -EnableOpenAI
```

That reads `RAMALS_AI_PROVIDER_API_KEY` from the **Windows User/Machine environment**, creates the
`ramals-ai-provider` Secret, and sets `RAMALS_AI_AI_ENABLED=true` with route `diagnostic-default` on
the live Deployment — not in the manifests, so a later `kubectl apply -k` cannot silently re-enable
billable calls for someone else.

**It makes real, billable calls.** Nothing local caps spend; set a limit at
`platform.openai.com/settings/organization/limits`.

Two things worth knowing:

- **The key never enters Git.** Not in a manifest, not in an image, not in Terraform. It exists only
  in your environment and in a cluster Secret created at runtime.
- **Read it from the registry, not `$env:`.** A long-lived shell inherited its environment when it
  started and will hand back a key you rotated an hour ago. That staleness produced a 401 against a
  perfectly valid key during this package's own bring-up, which is why `bootstrap.ps1` uses
  `[Environment]::GetEnvironmentVariable(..., "User")` instead.

Selecting OpenAI is a **model pin**, not a route: every route is `claude-sonnet-5` primary with
`gpt-4.1-2025-04-14` as an alternate, so `RAMALS_AI_MODEL_PINS` points the routes at the alternate.

Enabling a provider also requires the AI plane to reach the internet — see Isolation below.

## Isolation

`network-policy.yaml` allows the AI plane exactly two things: DNS, and TCP/443 to
`0.0.0.0/0` **except** `10.42.0.0/16` and `10.43.0.0/16` — the k3s pod and Service CIDRs.

That `except` list is the whole control. It lets the AI plane reach a model provider while making it
provably unable to open a connection to any pod or Service in this cluster, whatever port that
target listens on. Without it, allowing 443 outbound would be a blanket egress permit that merely
happens not to name PostgreSQL, and the isolation would rest on PostgreSQL not listening on 443
rather than on a rule.

If a cluster is ever created with non-default CIDRs, those two entries must change with it —
otherwise the exclusion silently stops covering the addresses it names.

`smoke.ps1` checks this as a **negative control**: it asserts the connection *fails*, and fails the
run if the AI plane can reach the database. A path that is never exercised looks exactly like a path
that is blocked, so the test has to try it.

## Secrets

`bootstrap.ps1` generates five random passwords in memory per environment and creates the
`ramals-dev-runtime` Secret from them. They are never written to disk, never printed, and never
committed. They are development-only credentials with no meaning outside this cluster.
