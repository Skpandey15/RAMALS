# R1 evidence package — calibrated baseline on `perf-standard-01`

**Date (UTC):** 2026-08-21 · **Status of R1: still OPEN** — see [Disposition](#disposition).

This is the authoritative R1 evidence. It supersedes nothing: the MVP-0 measurements in
[`performance-baseline.md`](performance-baseline.md) remain valid as *indicative* numbers taken on a
developer workstation, and are explicitly not a calibrated baseline. What is new here is that the
same measurements were taken on an attested fixed-spec environment with the load generator on a
separate machine.

Two runs were executed. **Both are permanent record. Neither replaces the other.**

---

## 1. Artifact identity

| | |
| --- | --- |
| Release | **`v0.1.0-rc3`** |
| Release commit | `f7fb9efad94fbbebd68ea24cabdf79a1e2f50cb8` |
| Benchmark tooling | `main@84bddbc18e72c5687d9946ced312774653c27e80` |

Deployed by immutable digest, verified on the running containers rather than taken from the
manifest:

| Component | Image and digest |
| --- | --- |
| learning-platform | `ghcr.io/skpandey15/ramals-learning-platform@sha256:c233e3ef7275b2ca63a8613547d69829def6deda150c3d30bdbaa5fdb3f330b9` |
| web-ui | `ghcr.io/skpandey15/ramals-web-ui@sha256:a8a01caea253341bfe37357e53e43ab346f6e548b26cad3442407093cffe2637` |
| ramals-ai | `ghcr.io/skpandey15/ramals-ai@sha256:12105fa07e32e6cca0467cc46cfb3d44fb30e81aefb4f89fae2cee85e1726da0` |

## 2. Environment attestation

`perf-standard-01`, spec version 1, **`conforms: true` with zero failures**, exit 0. The same
attestation was carried to the load generator for both runs and re-validated there.

| Measured | Value | Spec requires |
| --- | --- | --- |
| Host CPUs visible to the container runtime | 8 | ≥ 8 |
| Host memory visible to the container runtime | 30.81 GiB | ≥ 16 |
| `backend` container | running, 4.0 CPU / 4.0 GiB | 4.0 / 4 |
| `postgres` container | running, 2.0 CPU / 2.0 GiB | 2.0 / 2 |
| Load generator off-host | `true` | required |

SUT `m6i.2xlarge`; load generator `c6i.xlarge`, a separate instance in the same subnet. The
attestation is machine-measured, not asserted: `attest.py` reads the container runtime and the
running containers, and the runner refuses a qualified environment id without it.

The spec remains `status: proposed`. These are the first measurements against which its container
limits can be reviewed.

## 3. Workload configuration — identical in both runs

| | |
| --- | --- |
| Scenario | `mixed-learning` |
| Executor | open model, ramping arrival rate |
| Stages | 30 s warm-up at 10 rps → 1 min ramp 10→60 rps → 2 min steady at 60 rps |
| Total duration | 3 m 30 s |
| VUs | 50 preallocated, 300 maximum |
| Load learners | 20, provisioned at runtime and removed afterwards |
| Dataset | `mvp0-baseline-v1` |
| Request mix | skill-map 35%, content 20%, assessment write 15%, diagnostic 10%, recommendation 10%, mastery 5%, auth 5% |
| Thresholds | overall failure < 1%; p95 < 250 ms skill-map/mastery/recommendation; < 400 ms assessment write; < 500 ms diagnostic |

---

## 4. R1 Run A — **VALID / FAIL** — normal rate-limit policy

The canonical result: the platform as configured to run, with **no override of any kind**.
`docker inspect` of the backend showed no `RAMALS_RATE_LIMIT_*` variable, so application defaults
were in force.

| Field | Value |
| --- | --- |
| Iterations | **9,599** |
| Requests | **12,417** |
| Sustained rate | **60 rps** for the full duration |
| `http_req_failed` | **17.33%** (2,153) |
| Checks | 88.73% (8,518 / 9,599) |
| Latency thresholds | **PASS** |
| Error-rate threshold | **FAIL** |

### HTTP status distribution

| Status | Count |
| --- | --- |
| 200 | 9,947 |
| **429** | **2,153** |
| 201 | 342 |
| 401 | 1 |

The 429 count matches `http_req_failed` exactly.

### Latency by request class

| Class | p90 | p95 | Budget | Result |
| --- | --- | --- | --- | --- |
| skill_map_read | 3.91 ms | **5.38 ms** | 250 ms | pass |
| mastery_read | 3.65 ms | **4.71 ms** | 250 ms | pass |
| recommendation_read | 2.59 ms | **3.41 ms** | 250 ms | pass |
| assessment_write | 18.37 ms | **21.37 ms** | 400 ms | pass |
| diagnostic | 3.95 ms | **4.88 ms** | 500 ms | pass |

### 429 attribution

The failures are the **pre-authentication rate-limit tier, keyed on client IP**. Failures
concentrate in the `submit COMPLETED` check (1,077 of 1,399); every other check passed at 99% or
better.

`application.yml` binds that tier to:

```yaml
rate-limit:
  capacity: ${RAMALS_RATE_LIMIT_CAPACITY:120}
  refill-per-second: ${RAMALS_RATE_LIMIT_REFILL_PER_SECOND:60}
```

The run drove **12,417 requests over 210 s = 59.1 req/s from a single load-generator IP** against a
60/s refill with a 120-token burst allowance. Bursts above refill exhaust the bucket and are
refused.

### Restart / OOM evidence — the 429s were policy, not exhaustion

| Service | Restarts | OOM killed |
| --- | --- | --- |
| backend | 0 | false |
| postgres | 0 | false |
| keycloak | 0 | false |
| web-ui | 0 | false |

Successful-request latency stayed in single-digit milliseconds throughout. Rejection is cheap; a
saturated system does not look like this.

---

## 5. R1 Run B — **PASS** — capacity characterization with documented override

**Not a production-policy result and must not be read as one.** Run B answers only the question Run
A cannot: what the application does when the infrastructure protection ceiling is not the binding
constraint.

`performance/compose.perf-override.yml` was applied — the file the repository provides for exactly
this ("reach for this only to probe above the IP ceiling itself").

### Override values, verified on the running container *before* load

```
RAMALS_RATE_LIMIT_CAPACITY=1000000
RAMALS_RATE_LIMIT_REFILL_PER_SECOND=1000000
RAMALS_RATE_LIMIT_SUBJECT_CAPACITY=1000000
RAMALS_RATE_LIMIT_SUBJECT_REFILL_PER_SECOND=1000000
```

Only the first two took effect. `RAMALS_RATE_LIMIT_SUBJECT_*` is not referenced by any resource
file and does not match the `ramals.security.rate-limit` binding prefix, so those two values bound
to nothing: **the per-subject fair-use tier remained at its shipped 120 / 60 throughout Run B.** At
20 learners and 60 rps each learner issued roughly 3 req/s, far under that tier, so it never
engaged. Run B therefore relaxed the IP ceiling only.

That the override was active is recorded in machine-readable form alongside the raw evidence in
[`runB-override-metadata.json`](../../validation/r1-20260821-evidence/run-b/runB-override-metadata.json).

### Results

| Field | Value |
| --- | --- |
| Iterations | **9,599** |
| Requests | **12,519** |
| Throughput | **59.41 req/s** sustained |
| `http_req_failed` | **0.00%** (0 of 12,519) |
| Checks | **100.00%** (9,599 / 9,599) |
| HTTP 429 | **0** |
| All thresholds | **PASS** |

Status distribution: `11,073 × 200`, `1,454 × 201`. No 4xx and no 5xx of any kind.

### Latency by request class

| Class | p95 | Budget | Result |
| --- | --- | --- | --- |
| skill_map_read | 5.58 ms | 250 ms | pass |
| mastery_read | 4.13 ms | 250 ms | pass |
| assessment_write | 25.37 ms | 400 ms | pass |
| diagnostic | 5.28 ms | 500 ms | pass |

### Telemetry — 49 samples across the run

| Service | Peak CPU | Limit | Memory (final) | Limit |
| --- | --- | --- | --- | --- |
| backend | **340.68%** | 400% (4 CPU) | 309.9 MiB | 4 GiB |
| keycloak | 18.63% | — | 844.5 MiB | — |
| postgres | 19.29% | 200% (2 CPU) | 105.3 MiB | 2 GiB |
| web-ui | 2.30% | — | 8.4 MiB | — |

PostgreSQL: 5 backends, ~34,000 commits, **0 rollbacks, 0 deadlocks, 0 temp files, 0 conflicts**.
Restarts and OOM kills: **zero on all four services**.

The backend reached 340% of its 4-CPU allocation at peak — the binding resource, with roughly 15%
headroom. Memory never exceeded 8% of its limit. **This is not a capacity ceiling**: the arrival
rate was capped at 60 rps by the scenario, and no saturation point was searched for.

---

## 6. Evidence that nothing else changed between Run A and Run B

Verified by inspection of the running containers after the override was applied, not by assertion:

| Property | Run A | Run B | Same |
| --- | --- | --- | --- |
| Backend image digest | `sha256:c233e3ef…f330b9` | `sha256:c233e3ef…f330b9` | ✅ |
| Backend CPU limit | 4 000 000 000 ns (4 CPU) | 4 000 000 000 ns | ✅ |
| Backend memory limit | 4 294 967 296 (4 GiB) | 4 294 967 296 | ✅ |
| PostgreSQL CPU limit | 2 000 000 000 ns (2 CPU) | 2 000 000 000 ns | ✅ |
| PostgreSQL memory limit | 2 147 483 648 (2 GiB) | 2 147 483 648 | ✅ |
| Database configuration | unchanged | unchanged | ✅ |
| Application code | unchanged | unchanged | ✅ |
| Scenario, rate, VUs, duration | `mixed-learning`, 60 rps, 50/300, 3m30s | identical | ✅ |
| Latency thresholds | unmodified | unmodified | ✅ |
| Load learners / dataset | 20 / `mvp0-baseline-v1` | 20 / `mvp0-baseline-v1` | ✅ |
| Instance types | `m6i.2xlarge` / `c6i.xlarge` | identical | ✅ |
| OIDC issuer configuration | `http://172.31.11.69:8081/realms/ramals` | identical | ✅ |
| Rate-limit configuration | **application defaults** | **override active** | ⬅ the only change |

The benchmark tooling was also deliberately **not** modified between the two runs, so both measured
the same implementation. The `run-baseline.sh` fix described in §8 was made afterwards.

## 7. Teardown — no chargeable R1 infrastructure remains

`terraform destroy` completed: **`Destroy complete! Resources: 4 destroyed.`**

Independently verified afterwards against AWS, not read from Terraform's own output:

| Check | Result |
| --- | --- |
| Non-terminated instances tagged `Purpose=perf-standard-01` | **0** |
| EBS volumes tagged `Purpose=perf-standard-01` | **0** |
| Security groups tagged `Purpose=perf-standard-01` | **0** |
| Elastic IPs in the account | **0** |
| Terraform state entries | **0** |

Both root volumes were `DeleteOnTermination=true`. Ephemeral runtime credentials were generated on
the SUT only, held at mode `0600`, never written to Terraform state or any repository file, and
destroyed with the volume.

## 8. Corrections and defects recorded during this exercise

**A claim in an earlier version of this exercise was wrong and is corrected here.** The validation
report first stated that this build "has only the IP tier" and that no subject-keyed rate limit
exists. That is false. `RateLimitProperties` defines both tiers and `SubjectRateLimitFilter`
implements the second, applied after token validation, exactly as
[`performance-baseline.md` §4](performance-baseline.md) describes.

What is true, and is the actual defect, is narrower: **`application.yml` binds the
pre-authentication IP tier to `120` / `60`** — the values intended for the per-learner fair-use tier
— while the code's own default for that tier is `600` / `300`. The two-tier fix shipped; the
configuration still carries the pre-fix numbers on the wrong tier. The IP ceiling is therefore five
times tighter than designed, which both caused Run A's 429s and partially reinstates the
shared-egress problem MVP-0 recorded as resolved. Tracked as **TD-R1-01**.

**A failing run produced no baseline, and leaked credentials.** Run A wrote no `.baseline.json`:
k6 exits non-zero on a breached threshold and `run-baseline.sh` ran under `set -e`, aborting before
distillation. Because the `setup_data` scrub lives in that same block, Run A's summary also retained
**20 live bearer tokens** — caught by gitleaks when this evidence was first committed. The tokens
were already invalid (Keycloak destroyed, learners deleted at teardown) and never reached a
published branch; the committed copy has `setup_data` removed with a `$scrub_note`, and no
measurement was altered. Fixed and guarded; tracked as **TD-R1-02**.

## Disposition

**R1 remains OPEN.** It is no longer blocked on hardware — an attested `perf-standard-01`
environment was provisioned, measured and destroyed, and both runs are recorded. What remains is a
decision, not a machine: the canonical 60 rps workload run from a single source IP collides with a
rate-limit ceiling that is itself misconfigured, so **there is not yet a calibrated baseline that is
simultaneously a pass and taken under production policy.**

Closing R1 requires resolving TD-R1-01 and re-running. That is a release-review decision and is
deliberately not taken here.

## Raw evidence

[`docs/validation/r1-20260821-evidence/`](../../validation/r1-20260821-evidence/) — k6 summaries,
the Run B baseline, attestations, console logs, complete backend request logs (gzipped), status
distributions, effective rate-limit readings, restart/OOM records and Run B telemetry. Analysis in
[`r1-20260821-run-a-and-run-b-report.md`](../../validation/r1-20260821-run-a-and-run-b-report.md).
