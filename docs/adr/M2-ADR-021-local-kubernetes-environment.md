# M2-ADR-021: k3d is the local Kubernetes environment, and Rancher Desktop's own Kubernetes is off

- **Status:** Accepted — 2026-08-29.
- **Decides:** which Kubernetes a developer runs locally, what supplies the container runtime, and
  what the local environment is and is not evidence of.
- **Relates to:** `deploy/k8s/dev` (this decision's implementation), `deploy/k8s/t15` (the M2-T15
  qualification environment this is deliberately separate from), `docs/architecture/aws-dev-foundation.md`
  (AWS DEV, which shares the architecture but not the mechanism), M2-ADR-017 §1 (the deterministic
  Spring core is authoritative), M2-ADR-016 (Contract B capability gate).

## Context

Three Kubernetes-shaped things now exist in this project, and they had been distinguished only by
folder name:

| | What it is | What it proves |
|---|---|---|
| `deploy/k8s/dev` | ordinary local development | that a change runs |
| `deploy/k8s/t15` | M2-T15 qualification | that an **approved candidate** behaves correctly |
| `infrastructure/terraform` | AWS DEV | that the deployed estate holds its invariants |

Conflating the first two is the expensive mistake. The T15 package pins images by digest to an
approved commit, and `candidate-integrity.ps1` chains *approved commit → lock digests → rendered
manifest hashes → live pod imageIDs*. That chain is what makes its results evidence. Building a
working-tree image into that cluster does not "update T15" — it silently retires the candidate and
invalidates the Contract A S1–S4 qualification held there, without anything failing at the time.

Developers still need somewhere to run the whole stack. Without a sanctioned local environment, the
qualification cluster is the only one that exists, so it becomes the one people deploy into.

## Decision

**k3d is the local Kubernetes implementation for RAMALS.** Rancher Desktop supplies the container
runtime (`moby`/dockerd) and nothing else; **its built-in Kubernetes is disabled.**

```
Windows -> Rancher Desktop -> moby/dockerd -> k3d -> k3s -> cluster ramals-dev -> namespace ramals-dev
```

### Why not Rancher Desktop's Kubernetes

It is a perfectly good single-node cluster. The problem is that it is *implicit*: it is a checkbox,
its version moves when Rancher Desktop updates, there is exactly one of it, and it installs a
`rancher-desktop` kubeconfig context alongside whatever else is present. Two Kubernetes
distributions on one workstation compete for contexts and host ports, and the cluster you did not
mean to address is the one you spend the afternoon debugging.

k3d is explicit. The cluster is created by a command in a script that is reviewed, its topology and
k3s version are arguments, it can be destroyed and rebuilt in a minute, and a developer can run more
than one. The environment becomes a repository artefact rather than a property of somebody's laptop.

### What follows from it

- **Cluster `ramals-dev`, namespace `ramals-dev`, 1 server + 1 agent.** Two nodes rather than one so
  that scheduling across nodes is exercised locally at all, which a single node hides.
- **A dedicated registry, `k3d-ramals-registry`.** Images are tagged with the short commit sha.
  `:latest` and `:local` are never deploy targets: a tag that moves cannot be traced to a commit,
  and the first question about a misbehaving local pod is which commit it is.
- **No host port mappings.** Every Service is `ClusterIP`; there is no Ingress and no NodePort.
  Developer access is `kubectl port-forward`, which is explicit, per-developer, and cannot
  accidentally publish PostgreSQL.
- **Contract B is off and asserted, not assumed.** `smoke.ps1` reads the deployed objects and fails
  if any of the three switches, or durable execution, or external provider execution, is on. An
  assertion that restates a literal — rather than reading the deployed state — is not a control;
  that mistake was made once already and removed in PR #193.
- **The AI plane's isolation is enforced by the cluster.** `network-policy.yaml` restricts
  `ramals-ai` egress to DNS, so it has no path to PostgreSQL. `smoke.ps1` verifies this as a
  negative control — it attempts the connection and fails the run if it succeeds.

## Consequences

**Local DEV is not qualification evidence.** It runs working-tree code, its images are not
digest-pinned to an approved candidate, and no integrity gate binds them. Nothing produced here may
be cited as M2-T15 qualification.

**Local DEV is not AWS DEV.** The architecture is the same and the invariants are the same, but the
mechanisms differ: NetworkPolicy here, security groups there; k3s here, ECS Fargate there. A control
proven in one is not thereby proven in the other, and the AWS DEV plan is verified separately.

**The runtime is Rancher Desktop's responsibility, and it is a real dependency.** Rancher Desktop's
WSL networking sits underneath everything here; when its host-switch tunnel fails, DNS inside the
runtime distribution stops resolving and image builds fail with errors that look like Docker
problems. That is worth naming, because the failure surfaces far from its cause.

**Nothing here changes application architecture.** No manifest weakens a boundary to make local
deployment easier. Where local DEV must diverge — currently `RAMALS_AI_WORKLOAD_AUTH_ENABLED=false`,
because no Keycloak workload-client bootstrap exists in this package — it is recorded as a known
divergence rather than quietly normalised.
