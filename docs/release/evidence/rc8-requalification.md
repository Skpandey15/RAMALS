# v0.1.0-rc8 — requalification

Requalification of the deployment candidate after `fix(ai): ship the provider SDK and approve a
second vendor per route`, which superseded `v0.1.0-rc7`.

RC7 could not make a live model call at all: its image was built with `pip install .`, which omits
optional extras, so `litellm` was absent. Every health gate passed regardless, because `ci-fake`
never imports it. RC8 exists to correct that, so requalification has to prove the deployed artifact
is the published one and that every shipped gate still passes against it.

## Candidate

| | |
| --- | --- |
| Version | `v0.1.0-rc8` |
| Commit | `0c01c8518abae97bf986700a9af616e17a656aea` |
| Release run | `v0.1.0-rc8` tag build, all nine jobs green (including Trivy on the enlarged `ramals-ai` image) |

| Component | Digest |
| --- | --- |
| learning-platform | `sha256:cc1af7a112f3f108fd750b646ab5c32ee2fb030adb6139a7836067b8c91fd73f` |
| web-ui | `sha256:29fb2685818dc4394d51a3ad4699f1b516edc5dbeec3ecf840d8f54b8d05803a` |
| ramals-ai | `sha256:5c0809289f2d02f5a1f62979ddf6f9f0fa92d58c31d21cadb8cd83768b12692e` |

## Method

Deployed through `deploy/deploy-controller.sh` on the real path: manifest → digest pull → compose up
→ health gates → state transition. Nothing about the candidate was rebuilt, retagged or patched.

Only the gate **invocation** was redirected, using the controller's existing `RAMALS_HEALTH_CMD`
hook. `deploy/health-gates.sh` itself was piped into the runner verbatim — not edited, not
re-implemented, not partially executed — with three service URLs overridden to the deployment
network's own DNS names:

```
RAMALS_BACKEND_URL=http://backend:8080
RAMALS_WEBUI_URL=http://web-ui:8080
RAMALS_OIDC_ISSUER_URI=http://keycloak:8080/realms/ramals
AI_URL=http://ramals-ai:8000
```

The runner is a container attached to `ramals-deploy_edge`, the deployment's own network.

Before any gate ran, the running containers' image digests were checked against the published RC8
artifacts. That check sits on the gated path deliberately: a requalification that validated a
locally-built image would attest to bytes nobody published.

```
[rc8-gate] ok   learning-platform runs the published RC8 digest sha256:cc1af7a1…c91fd73f
[rc8-gate] ok   web-ui runs the published RC8 digest sha256:29fb2685…8d05803a
[rc8-gate] ok   ramals-ai runs the published RC8 digest sha256:5c080928…8b12692e
```

## Gate results

Every gate in the shipped `health-gates.sh`, in the order the script runs them.

| # | Gate | Result |
| --- | --- | --- |
| 1 | backend liveness | ✅ PASS |
| 2 | backend readiness | ✅ PASS |
| 3 | backend health (db) | ✅ PASS |
| 4 | oidc issuer (`.well-known/openid-configuration` carries `jwks_uri`) | ✅ PASS |
| 5 | web ui responds | ✅ PASS |
| 6 | ai plane liveness | ✅ PASS |
| 7 | ai plane readiness | ✅ PASS |
| 8 | smoke: AI agent endpoint requires workload identity (401) | ✅ PASS |
| 9 | smoke: AI plane reports its route table (`ROUTE_TABLE_V1`) | ✅ PASS |
| 10 | smoke: the rollback took effect in the running service | ⚪ not applicable |
| 11 | backend readiness is independent of the AI plane | ✅ PASS |
| 12 | smoke: protected endpoint requires authentication (401 on `/api/v1/me`) | ✅ PASS |

Gate 10 is conditional on `AI_EXPECTED_ROUTE_TABLE` being set, which the script does by design: an
unset expectation is a deployment that is not rolling anything back, and defaulting it would either
fail every ordinary release or pass every rollback. This deployment pins no route, and the plane
correctly reported the unpinned `ROUTE_TABLE_V1`. Recorded as not applicable rather than as a pass,
because it did not execute.

Controller outcome: `HEALTHY 0c01c8518abae97bf986700a9af616e17a656aea`, exit 0. `known_good` advanced
to RC8; `held_versions` empty.

**Outcome: PASS**

Subject to the qualification limitation recorded below.

## Qualification limitation — host-published JVM ports

> Host-published JVM ports were not exercised during this requalification because the local
> Windows/container runtime exposes the JVM services only on the IPv6 wildcard while host forwarding
> does not provide a host-reachable IPv4 path. The identical condition reproduces on RC7 and is
> therefore not attributable to RC8. In-container service-network health and functional gates were
> executed instead.

Supporting observations, recorded so the limitation can be re-tested rather than re-argued:

| Container | Runtime | Listener | Host-published port |
| --- | --- | --- | --- |
| backend | JVM | `::` 8080 only | unreachable |
| keycloak | JVM | `::` 8080 only | unreachable |
| web-ui | nginx | `0.0.0.0` 8080 | reachable |
| ramals-ai | uvicorn | `0.0.0.0` 8000 | reachable |

Isolated with a controlled pair of throwaway containers on the same host — identical image and
publish syntax, differing only in bind family. The IPv6-only listener was unreachable from the host;
the IPv4 listener returned 200. Two competing explanations were tested and disproved: a stale
forwarder (the condition survived a full container-engine restart with a new forwarder process) and
multi-homing (a deliberately multi-homed probe was reachable).

**This limitation is not resolved and is not claimed to be.** It is a gap in what this
requalification exercised, not a defect that was fixed. Host port publishing for the JVM services
remains unverified on this host.

Follow-up is tracked as **TD-RC8-01**. It is deliberately not implemented here: changing how the JVM
binds would change deployment configuration, which would discard RC8 and require a new candidate —
burning a qualified artifact to address a host-side condition.

## What this requalification does not cover

* Host port publishing for the JVM services, per the limitation above.
* **TD-T18-02** remains open. RC8 is the first candidate that *can* reach a provider, but closing it
  needs a live-provider adaptation run through the deployed stack with workload authentication on
  and an `ai_execution` row in PostgreSQL. That needs `RAMALS_AI_PROVIDER_API_KEY` present in the
  deployment environment; it is not set. The service reads only that variable and never
  `OPENAI_API_KEY`.
* **TD-T18-01** (`AFTER_COMMIT` delivery is not durable) is unchanged.
