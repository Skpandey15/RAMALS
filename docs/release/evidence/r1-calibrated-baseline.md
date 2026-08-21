# R1 — calibrated performance baseline on `perf-standard-01`

**Status: R1 CLOSED.** The authoritative calibrated baseline is the `v0.1.0-rc4` run of 2026-08-21,
executed under normal production rate-limit policy with no override of any kind.

Three runs exist and all three are permanent record. The rc3 pair is retained as historical
qualification evidence — it is what proved the environment, found the defect, and established that
the defect was configuration rather than capacity. Nothing in this document supersedes or relabels
them.

| Run | Release | Policy | Disposition |
| --- | --- | --- | --- |
| **RC4 canonical** | `v0.1.0-rc4` | production defaults | **PASS — authoritative baseline** |
| RC3 Run A | `v0.1.0-rc3` | production defaults (defective) | VALID / FAIL — historical |
| RC3 Run B | `v0.1.0-rc3` | capacity override | PASS — capacity characterization, historical |

---

## 1. Artifact identity

| | |
| --- | --- |
| Release | **`v0.1.0-rc4`** |
| Release commit | `86d1033b366b75e2258cc10fd5be80a591bbfe8d` |
| Benchmark tooling | the same commit — tooling and application ship from one tree |

Verified on the running containers, not read from the manifest:

| Component | Image and digest |
| --- | --- |
| learning-platform | `ghcr.io/skpandey15/ramals-learning-platform@sha256:5f04c3db1e7d4894005f78ae89213eb20ca97080c2e3e04df7af953097355e56` |
| web-ui | `ghcr.io/skpandey15/ramals-web-ui@sha256:d9aa1c00d33d000c539f4e6e134535ba2aecd2f7f222263a7919f135cf8d7fb6` |
| ramals-ai | `ghcr.io/skpandey15/ramals-ai@sha256:91f5cc91b87da94da4afa86d75b89ff5786f25f342078c9b5d70f53aed9f338c` |

Published by the trusted release pipeline from tag `v0.1.0-rc4`; every job passed, including
Contract, Backend, Frontend, Security and Python CI. Digests were cross-checked against GHCR
independently of the pipeline's own report and agree exactly.

**Why rc3 could not be used.** `application.yml` is packaged into the backend jar. Reading it out of
the published rc3 digest shows `capacity: 120 / refill-per-second: 60` on the pre-authentication
tier and no subject binding — the TD-R1-01 defect, frozen into the artifact. A run against rc3 would
have reproduced Run A exactly.

## 2. Environment attestation

`perf-standard-01`, spec version 1, **`conforms: true`, zero failures, exit 0**. Carried to the load
generator and re-validated there.

| Measured | Value | Spec |
| --- | --- | --- |
| Host CPUs visible to the container runtime | 8 | ≥ 8 |
| Host memory visible to the container runtime | 30.81 GiB | ≥ 16 |
| `backend` container | running, 4.0 CPU / 4.0 GiB | 4.0 / 4 |
| `postgres` container | running, 2.0 CPU / 2.0 GiB | 2.0 / 2 |
| Load generator off-host | `true` | required |

SUT `m6i.2xlarge`; load generator `c6i.xlarge`, separate instance, same subnet.

## 3. Effective rate-limit configuration — production policy, verified before load

This is the point of the run, so it was established before any traffic was generated:

```
RAMALS_RATE_LIMIT_* variables on the backend container : none (0)
compose files in effect                                : deploy/compose.deploy.yml
                                                         performance/compose.perf-fixed.yml
                                                         performance/compose.perf-two-host.yml
```

`compose.perf-override.yml` was **not** applied, and `deploy/.env` contains no rate-limit variable,
so the packaged defaults were in force:

| Tier | Capacity | Refill/s |
| --- | --- | --- |
| Pre-authentication, keyed on client IP | **600** | **300** |
| Post-authentication, keyed on token subject | **120** | **60** |

## 4. Pre-flight gates, all passed before traffic

Every check introduced in response to an earlier R1 failure, run in order:

| Gate | Result |
| --- | --- |
| Runner and fixture scripts executable (the exit-126 class) | pass |
| Two-host network contract — 6 checks | **holds** |
| Backend and Keycloak reachable from the load generator over the private interface | pass |
| Neither benchmark port answers on the public address | pass |
| `compose.deploy.yml` alone still binds loopback only | pass |
| Database migrations | **24 applied, schema at v024** |
| Health gates | pass |
| Authentication smoke — token pool acquired *and spent on the backend* | **21 requests, 0 failures** |
| `perf-standard-01` attestation | **exit 0, zero failures** |

## 5. The canonical run

Committed workload, unmodified: `mixed-learning`, 20 learners, 30 s at 10 rps → 1 min ramp 10→60 rps
→ 2 min steady at 60 rps, 50 preallocated / 300 maximum VUs, existing thresholds.

| Field | Value |
| --- | --- |
| Iterations | **9,599** |
| Requests | **12,455** |
| Throughput | **59.11 req/s** sustained |
| `http_req_failed` | **0.00%** (0 of 12,455) |
| Checks | **100.00%** (9,599 / 9,599) |
| HTTP 429 | **0** |
| Thresholds | **all passed**, including `rate<0.01` at 0.00% |
| k6 exit status | 0 |

### HTTP status distribution

| Status | Count |
| --- | --- |
| 200 | 11,049 |
| 201 | 1,426 |
| 401 | 1 |

No 429 and no 5xx. The single 401 is the unauthenticated probe the harness issues before
authenticating.

### Latency

Overall: **p50 2.59 ms**, **p95 20.00 ms**.

| Class | med | p90 | p95 | Budget | Result |
| --- | --- | --- | --- | --- | --- |
| skill_map_read | 2.75 ms | 3.54 ms | **4.17 ms** | 250 ms | pass |
| mastery_read | 2.75 ms | 3.44 ms | **4.27 ms** | 250 ms | pass |
| recommendation_read | 1.83 ms | 2.31 ms | **2.84 ms** | 250 ms | pass |
| assessment_write | 16.35 ms | 22.21 ms | **23.99 ms** | 400 ms | pass |
| diagnostic | 3.04 ms | 3.83 ms | **4.42 ms** | 500 ms | pass |

**p99 is not recorded, and is not available.** k6's `--summary-export` emits only
`avg/min/med/max/p(90)/p(95)` for `http_req_duration`; the harness does not configure additional
percentiles. This is a gap in the harness rather than a measurement that was taken and omitted, and
it is recorded here as such rather than filled with a substitute.

### Resource telemetry — 50 samples across the run

| Service | Peak CPU | Limit | Memory (final) | Limit |
| --- | --- | --- | --- | --- |
| backend | **237.79%** | 400% (4 CPU) | 301.8 MiB | 4 GiB |
| postgres | 21.60% | 200% (2 CPU) | 96.5 MiB | 2 GiB |
| keycloak | 4.34% | — | 778.2 MiB | — |
| web-ui | 2.17% | — | 7.6 MiB | — |

The backend peaked at 237% of its 4-CPU allocation — roughly 40% headroom, and notably lower than
the 340% RC3 Run B reached, because that run was serving the requests this one no longer has to
refuse and retry around. Memory never exceeded 8% of its limit.

### PostgreSQL

Across 50 samples: 2–3 backends, commits rising 152 → 17,980, **0 rollbacks, 0 deadlocks, 0 temp
files, 0 conflicts**. Buffer reads 538 against 2.64 million cache hits — the working set is
resident.

### Restarts and OOM

| Service | Restarts | OOM killed |
| --- | --- | --- |
| backend | 0 | false |
| postgres | 0 | false |
| keycloak | 0 | false |
| web-ui | 0 | false |

## 6. Historical qualification evidence — the rc3 pair

Retained in full, not superseded. Detail in
[`r1-20260821-run-a-and-run-b-report.md`](../../validation/r1-20260821-run-a-and-run-b-report.md)
and [`r1-20260821-evidence/`](../../validation/r1-20260821-evidence/).

**RC3 Run A — VALID / FAIL, production policy.** 9,599 iterations, 12,417 requests, 60 rps
sustained, 2,153 HTTP 429, `http_req_failed` 17.33%. Latency thresholds passed comfortably; the
error-rate threshold failed. Zero restarts and zero OOM kills — policy, not saturation.

**RC3 Run B — PASS, capacity characterization** with `compose.perf-override.yml` active and recorded
as active. 12,519 requests, 0.00% failures. Established that the refusals were the ceiling and not
the application.

Together they located TD-R1-01: `application.yml` bound the pre-authentication IP tier to the
per-subject tier's numbers and left the subject tier unbound, so the two-tier design existed in code
and in no deployed system. The RC4 run above is the same workload against the corrected
configuration.

## 7. Teardown

`terraform destroy` completed: **`Destroy complete! Resources: 4 destroyed.`** Verified directly
against AWS afterwards, not from Terraform's output:

| Check | Result |
| --- | --- |
| Non-terminated instances tagged `Purpose=perf-standard-01` | **0** |
| EBS volumes tagged `Purpose=perf-standard-01` | **0** |
| Security groups tagged `Purpose=perf-standard-01` | **0** |
| Elastic IPs in the account | **0** |
| Terraform state entries | **0** |

Ephemeral runtime credentials were generated on the SUT only, at mode `0600`, never written to
Terraform state or any repository file, and destroyed with the root volume.

## 8. Disposition

**R1 is closed.** There is now a calibrated baseline taken on an attested fixed-spec environment,
with the load generator on a separate machine, against an immutable release candidate, under the
platform's own production rate-limit policy, passing every committed threshold.

The `perf-standard-01` spec remains `status: proposed`. This run is the first calibrated measurement
against which its container limits can be reviewed: the backend used 237% of four CPUs at peak and
under 8% of four GiB, which suggests the memory allocation is generous and the CPU allocation is
about right. Revising the spec is a separate decision and is not taken here.

This baseline does not establish a capacity ceiling. The arrival rate was capped at 60 rps by the
scenario and no saturation point was searched for.

## Raw evidence

[`r1-calibrated-baseline-evidence/`](r1-calibrated-baseline-evidence/) — k6 summary and distilled
baseline, attestation, console log, complete backend request log (gzipped), status distribution,
effective rate-limit readings, running digests, restart/OOM records and telemetry.
