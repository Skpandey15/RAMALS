# R1 authoritative benchmark attempt 2 — INVALID

**Attempt date (UTC):** 2026-08-21

**Disposition:** **INVALID — workload ran, but no request reached the application**
**Release effect:** none. This record does not close R1 and does not release MVP-1.

## Executive finding

The workload started this time. k6 ran the full `mixed-learning` profile — 3m30s, 9,599 completed
iterations, 60 iterations/second sustained — and every stage executed as specified.

It measured nothing. The backend returned **401 to 9,599 of 9,619 requests**. The twenty that
succeeded were the `setup()` token acquisitions against Keycloak, not workload requests.

Keycloak derives the `iss` claim from the address a token is requested through, and the canonical
topology runs it with `KC_HOSTNAME_STRICT=false`. On a single host this is invisible, because every
participant uses the in-network name. A two-host run splits them:

| Party | Value |
| --- | --- |
| Issuer in tokens k6 obtained | `http://172.31.1.205:8081/realms/ramals` |
| Issuer the backend validated against | `http://keycloak:8080/realms/ramals` |

Every token was well-formed, correctly signed, and refused.

## Why this was not caught earlier, and why the thresholds passed

The run reported **passing latency thresholds on every request class** — `p(95)=4.06ms` against a
250 ms budget for skill-map reads, and similar elsewhere — for the single reason that refusing a
token takes one to two milliseconds. Only `http_req_failed` crossed. A reader scanning the latency
columns would have seen the best numbers the platform has ever produced.

`preflight-r1.sh` passed immediately beforehand. It provisioned twenty learners and acquired twenty
tokens, all successfully, because Keycloak was never the component that disagreed. The preflight
stopped at acquisition and never presented a token to the backend, so the one question that mattered
went unasked.

## Immutable baseline identity

| Identity | Recorded value |
| --- | --- |
| RC3 tag | `v0.1.0-rc3` |
| RC3 commit | `f7fb9efad94fbbebd68ea24cabdf79a1e2f50cb8` |
| Backend image | `ghcr.io/skpandey15/ramals-learning-platform@sha256:c233e3ef7275b2ca63a8613547d69829def6deda150c3d30bdbaa5fdb3f330b9` |
| Web image | `ghcr.io/skpandey15/ramals-web-ui@sha256:a8a01caea253341bfe37357e53e43ab346f6e548b26cad3442407093cffe2637` |
| Benchmark tooling | `main@e437466` |
| SUT | `i-09a298491c021d907`, `m6i.2xlarge`, 8 vCPU, 30.8 GiB |
| Load generator | `i-0…`, `c6i.xlarge`, separate host |
| Environment | `perf-standard-01`, attestation PASS, exit 0, zero failures |
| Off-host flag | `true` |

Instance types, container limits, security groups, thresholds, workload configuration and the RC3
images were not modified. No RC4 was cut.

## Required result fields

| Field | Evidence |
| --- | --- |
| Total requests | 9,619 issued; 9,599 refused before reaching application logic |
| Total iterations | 9,599 completed, 0 interrupted |
| Throughput | 60 iterations/second sustained — of rejections |
| p50 / p95 / p99 latency | Recorded but meaningless: the cost of a 401, not of serving a request |
| Error/failure rate | 99.79% |
| HTTP failure breakdown | 401 Unauthorized, uniformly, `durationMs` 1–2 |
| Authentication failures | Token acquisition succeeded 20/20; token *acceptance* failed universally |
| k6 thresholds | Latency thresholds passed vacuously; `http_req_failed` crossed |
| Result artifacts | Summary and attestation written; **no `.baseline.json` produced** |

The latency figures are deliberately not reported as results. They are real measurements of an
error path.

## What ran correctly

Everything the previous attempt could not reach:

- both instances provisioned; `perf-standard-01` attestation passed with exit 0 and zero failures;
- RC3 pulled and deployed by immutable digest, verified on the running containers;
- the two-host network contract held — backend and Keycloak reachable from the load generator over
  the private interface, neither answering publicly, `compose.deploy.yml` still loopback-only;
- `run-baseline.sh` and `fixtures.sh` both executed (the exit-126 class is closed);
- the Keycloak base URL derived correctly from the token endpoint;
- fixtures provisioned twenty learners and restored the realm afterwards, including on failure.

## Teardown evidence

`terraform destroy` completed: `Resources: 4 destroyed.` Independent verification found no
non-terminated instance tagged `Purpose=perf-standard-01`, zero matching volumes, zero matching
security groups, zero Elastic IPs, and zero Terraform state entries. Approximate cost of the
attempt: 35 minutes at ~$0.62/hour.

Ephemeral runtime credentials were generated on the SUT only and destroyed with its root volume.

## Release-board disposition

R1 remains open. There is no performance PASS or FAIL to interpret. A separately reviewed tooling
change is required before any new authorised attempt: the issuer must be pinned so it does not
depend on the route taken to Keycloak, and the preflight must spend a token rather than merely
obtain one.
