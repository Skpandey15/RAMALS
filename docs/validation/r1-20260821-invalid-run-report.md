# R1 authoritative benchmark attempt — INVALID

**Attempt date (UTC):** 2026-08-21  
**Disposition:** **INVALID — workload did not start**  
**Release effect:** none. This record does not close R1 and does not release MVP-1.

## Executive finding

The first approved authoritative R1 invocation failed before fixture provisioning, k6 startup, or
any benchmark request. The canonical AWS runbook invokes:

```text
./performance/run-baseline.sh mixed-learning
```

At tooling commit `8840a90f72c7dd0c81b0d0ba9401d3b4c5d19559`,
`performance/run-baseline.sh` is tracked with Git mode `100644` and was present on the load
generator with mode `664`. Bash returned exit code `126`:

```text
bash: line 1: ./performance/run-baseline.sh: Permission denied
```

The invalid-run rule was applied: the failure was preserved and no permission correction, alternate
`bash performance/run-baseline.sh` invocation, repair, or second run was attempted.

## Immutable baseline identity

| Identity | Recorded value |
| --- | --- |
| RC3 tag | `v0.1.0-rc3` |
| RC3 commit | `f7fb9efad94fbbebd68ea24cabdf79a1e2f50cb8` |
| Backend image | `ghcr.io/skpandey15/ramals-learning-platform@sha256:c233e3ef7275b2ca63a8613547d69829def6deda150c3d30bdbaa5fdb3f330b9` |
| Web image | `ghcr.io/skpandey15/ramals-web-ui@sha256:a8a01caea253341bfe37357e53e43ab346f6e548b26cad3442407093cffe2637` |
| AI image input | `ghcr.io/skpandey15/ramals-ai@sha256:12105fa07e32e6cca0467cc46cfb3d44fb30e81aefb4f89fae2cee85e1726da0` |
| Benchmark tooling | `main@8840a90f72c7dd0c81b0d0ba9401d3b4c5d19559` |
| SUT | `i-08df7c3cd751264d0`, `m6i.2xlarge`, 8 vCPU, 30.81 GiB |
| Backend limit | 4 CPU / 4 GiB |
| PostgreSQL limit | 2 CPU / 2 GiB |
| Load generator | `i-03de3da07c4bc5bc4`, `c6i.xlarge`, separate host |
| Environment | `perf-standard-01`, spec version 1, attestation PASS with zero failures |
| Off-host flag | `true` |

The canonical `deploy/compose.deploy.yml`, RC3 application images, instance types, container
limits, security groups, thresholds, test data, and workload configuration were not modified.

## Workload and fixed acceptance criteria

The intended canonical scenario was `mixed-learning`, an open
`ramping-arrival-rate` workload with committed defaults:

- 20 load learners; 50 preallocated VUs and 300 maximum VUs.
- 30 seconds at 10 rps (warm-up).
- 1 minute ramp from 10 to 60 rps.
- 2 minutes steady at 60 rps.
- Request mix: skill-map 35%, content 20%, assessment write 15%, diagnostic 10%,
  recommendation 10%, mastery 5%, authentication 5%.
- Overall HTTP failure rate below 1%.
- p95 below 250 ms for skill-map, mastery, and recommendation reads.
- p95 below 400 ms for assessment writes.
- p95 below 500 ms for diagnostic requests.

None of these parameters or thresholds were overridden. They were not evaluated because k6 never
started.

## Required result fields

| Field | Evidence |
| --- | --- |
| Total requests | Not executed |
| Total iterations | Not executed |
| Throughput | Not executed |
| p50 / p95 / p99 latency | Not executed |
| Error/failure rate | Not executed |
| HTTP failure breakdown | Not executed |
| Authentication failures | Authentication setup was not invoked |
| k6 thresholds | Not evaluated |
| Workload duration/stages | Intended 3m30s; no stage began |
| Result artifacts | No new `performance/results` artifact was created |

These values are deliberately not represented as zero: there was no measurement.

## System evidence around the failed invocation

Passive telemetry observed the still-idle qualified stack after the pre-load failure:

| Metric | Observed |
| --- | --- |
| Backend CPU | 0.05%–2.42% |
| Backend memory | 275.5–276.8 MiB of 4 GiB |
| PostgreSQL CPU | 0.00%–2.80% |
| PostgreSQL memory | 60.86–61.86 MiB of 2 GiB |
| DB connections / active / idle-in-transaction | max 2 / max 1 / max 0 |
| DB rollback / temp-file / deadlock / conflict deltas | 0 / 0 / 0 / 0 |
| Health samples | 16/16 backend 200; 16/16 Keycloak 200 |
| Backend/PostgreSQL error lines during evidence window | 0 / 0 |
| Container restarts / OOM | all four services 0 / false |
| Migration errors | none |

Container start timestamps remained the original RC3 deployment timestamps, confirming no restart
or rebuild occurred.

## Teardown evidence

The first `terraform destroy` attempt made no changes because Terraform could not consume the AWS
CLI login credential provider. The same active AWS account session was exported only into the
Terraform process environment without printing or storing credential values. The unchanged destroy
then completed:

```text
Destroy complete! Resources: 4 destroyed.
```

Independent verification at `2026-08-21T06:49:56Z` found:

- both R1 EC2 instances terminated and no active R1 instance;
- both Terraform-managed EBS volume IDs absent;
- both Terraform-managed security-group IDs absent;
- no Elastic IP tagged `Purpose=perf-standard-01`;
- zero Terraform state entries.

No R1 chargeable infrastructure remains.

## Release-board disposition

R1 remains open. There is no performance PASS or FAIL result to interpret. A separately reviewed
tooling change is required before any new authoritative attempt can be authorized. This report
records the invalid attempt and teardown only; it does not declare MVP-1 released.
