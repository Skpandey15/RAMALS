# R1 authoritative benchmark — Run A and Run B

**Date (UTC):** 2026-08-21
**Environment:** `perf-standard-01`, spec version 1, attestation PASS with zero failures, off-host
load generator
**Release under test:** `v0.1.0-rc3` @ `f7fb9efad94fbbebd68ea24cabdf79a1e2f50cb8`

Two runs were executed against the same environment. **Both are part of the R1 evidence package.
Neither replaces the other.** They answer different questions, and only Run A answers the one R1
asks about production behaviour.

---

## R1 Run A — VALID / FAIL — production rate-limit policy active

**Disposition: VALID / FAIL.** The workload executed in full against the platform as it is
configured to run. This is the canonical result.

| Field | Value |
| --- | --- |
| Iterations executed | **9,599** |
| Requests executed | **12,417** |
| Canonical workload | **60 rps sustained** for the full 3m30s |
| HTTP 429 responses | **2,153** |
| `http_req_failed` | **17.33%** (2,153 of 12,417) |
| Checks | 88.73% succeeded (8,518 of 9,599) |
| Latency thresholds | **passed, comfortably** |
| Error-rate threshold | **FAILED** (`rate<0.01`, observed 0.1733) |

### Latency by request class — every class far inside budget

| Class | p90 | p95 | Budget | Margin |
| --- | --- | --- | --- | --- |
| skill_map_read | 3.91 ms | **5.38 ms** | 250 ms | 46× |
| mastery_read | 3.65 ms | **4.71 ms** | 250 ms | 53× |
| recommendation_read | 2.59 ms | **3.41 ms** | 250 ms | 73× |
| assessment_write | 18.37 ms | **21.37 ms** | 400 ms | 19× |
| diagnostic | 3.95 ms | **4.88 ms** | 500 ms | 102× |

### The IP rate-limit tier caused the 429 responses

Backend status distribution for the run:

```
9947  200
2153  429
 342  201
   1  401
```

The 429 count matches `http_req_failed` exactly. The failures are concentrated in the
`submit COMPLETED` check (1,077 of 1,399 attempts refused); every other check passed at 99% or
better.

The cause is the pre-authentication ceiling, keyed on client IP, at its committed defaults in
`learning-platform/src/main/resources/application.yml`:

```yaml
rate-limit:
  capacity: ${RAMALS_RATE_LIMIT_CAPACITY:120}
  refill-per-second: ${RAMALS_RATE_LIMIT_REFILL_PER_SECOND:60}
```

The benchmark drove **12,417 requests over 210 s = 59.1 req/s from a single load-generator IP**
against a 60/s refill with a 120-token burst allowance. Every burst above refill exhausts the
bucket and is refused. No override was present — `docker inspect` of the backend showed no
`RAMALS_RATE_LIMIT_*` variable, so application defaults were in force.

Corroborating evidence that this was policy rather than exhaustion: **zero restarts and zero
OOM kills across all four services**, and successful-request latency remained in single-digit
milliseconds throughout.

### Note on a stale claim in the repository

`performance/compose.perf-override.yml` states that since R9 rate limiting "has two tiers" — a
generous IP ceiling plus a post-authentication tier keyed on token subject — and that the override
is "usually NOT needed" because provisioning enough learners keeps each under the fair-use limit.

That is not true of this build. `application.yml` exposes only `capacity` and `refill-per-second`,
both IP-keyed. There is no subject tier. **Adding load learners cannot reduce these 429s**, because
the limit is per-IP and the load generator is one IP by design. The override file's guidance should
be corrected.

---

## R1 Run B — capacity characterization / perf rate-limit override

**Disposition: VALID / PASS — capacity characterization only. This is NOT a production-policy
result and must not be read as one.**

Run B changed exactly one thing: it applied `performance/compose.perf-override.yml`, the file the
repository provides for this purpose ("reach for this only to probe above the IP ceiling itself").
Workload shape, 60 rps target, VUs, duration, latency thresholds, test data, RC3 images, instance
sizes, CPU/memory limits, database configuration and application code were all unchanged.

The effective configuration was verified on the running container **before** load:

```
RAMALS_RATE_LIMIT_CAPACITY=1000000
RAMALS_RATE_LIMIT_REFILL_PER_SECOND=1000000
RAMALS_RATE_LIMIT_SUBJECT_CAPACITY=1000000
RAMALS_RATE_LIMIT_SUBJECT_REFILL_PER_SECOND=1000000
```

(The two `SUBJECT_*` values are set by the override file but not read by this build; only the IP
tier was actually relaxed.)

| Field | Value |
| --- | --- |
| Iterations executed | **9,599** |
| Requests executed | **12,519** |
| Throughput | **59.41 req/s** sustained |
| `http_req_failed` | **0.00%** (0 of 12,519) |
| Checks | **100.00%** (9,599 of 9,599) |
| HTTP 429 responses | **0** |
| All thresholds | **passed**, including `rate<0.01` at 0.00% |

### Latency by request class

| Class | p95 | Budget |
| --- | --- | --- |
| skill_map_read | 5.58 ms | 250 ms |
| mastery_read | 4.13 ms | 250 ms |
| recommendation_read | — | 250 ms |
| assessment_write | 25.37 ms | 400 ms |
| diagnostic | 5.28 ms | 500 ms |

Status distribution: `11073 × 200`, `1454 × 201`, **no 4xx and no 5xx of any kind**.

### Resource evidence (49 samples across the run)

| Service | Peak CPU | Limit | Memory (final) | Limit |
| --- | --- | --- | --- | --- |
| backend | **340.68%** | 400% (4 CPU) | 309.9 MiB | 4 GiB |
| keycloak | 18.63% | — | 844.5 MiB | — |
| postgres | 19.29% | 200% (2 CPU) | 105.3 MiB | 2 GiB |
| web-ui | 2.30% | — | 8.4 MiB | — |

PostgreSQL across 49 samples: 5 backends, ~34,000 commits, **0 rollbacks, 0 deadlocks, 0 temp
files, 0 conflicts**. Restarts and OOM kills: **zero on all four services**.

The backend reached 340% of its 4-CPU allocation at peak — the binding resource, with roughly 15%
headroom remaining. Memory was never a constraint at under 8% of its limit. This is the first
measurement that bears on whether `perf-standard-01`'s container limits are sized correctly; the
spec remains `status: proposed` until reviewed against it.

---

## What the two runs mean together

Run A is the honest statement of what the platform does under its own protection policy at the
canonical workload: **latency is excellent, and 17.33% of requests are refused by the IP rate
limiter.** Run B establishes that those refusals were entirely the ceiling and not the application:
with the ceiling lifted and nothing else altered, **the same workload runs at 0.00% failure with
comparable latency**.

The open question R1 does not answer, and which neither run should be stretched to cover: whether a
60 rps benchmark from a single source IP is a realistic proxy for 60 rps of production traffic from
many client addresses. Run A's failure is a property of the benchmark's topology meeting a
correctly-functioning control, not a defect in either.

No tuning was performed on the basis of Run B, and no further run was executed.

## Teardown

`terraform destroy` completed: `Resources: 4 destroyed.` Independent verification found zero
non-terminated instances tagged `Purpose=perf-standard-01`, zero matching volumes, zero matching
security groups, zero Elastic IPs and zero Terraform state entries. Ephemeral runtime credentials
were generated on the SUT only and destroyed with its root volume.

## Tooling defect observed during these runs — and a credential leak behind it

Run A produced **no `.baseline.json`**; Run B produced one. `run-baseline.sh` runs under `set -e`,
k6 exits non-zero when a threshold is breached, and the script aborts before the distillation step.

The more serious consequence surfaced only when this evidence was first committed: **gitleaks
refused the commit, having found 20 live access tokens in Run A's `summary.json`.**

k6 writes `setup()`'s return value into the summary export, and for these scenarios that value is
the learner token pool. `run-baseline.sh` strips it — but the strip lives at the top of the
distillation block, the same block a breached threshold skips. So the defect is not only that a
failing run loses its measurement; it is that a failing run **leaves bearer credentials in an
artifact intended for archival**, and does so precisely when an operator is most likely to be
copying files around by hand to work out what went wrong.

Run B's summary has no `setup_data`, because its distillation ran.

The tokens were already dead when found — the Keycloak instance was destroyed, the load learners
deleted and direct access grants disabled at teardown — and they never reached a published branch.
The committed copy of Run A's summary has had `setup_data` removed and carries a `$scrub_note`
recording that, exactly the scrub the harness should have applied. **No measurement data was
altered:** `metrics` and `root_group` are untouched, and `setup_data` contains no measurements.

Both halves are fixed and guarded: the status is captured rather than fatal, so distillation and its
scrub always run, and the CI suite drives `run-baseline.sh` with a stub k6 that fails after writing
a `setup_data` block, asserting that the tokens are gone and the baseline exists. Reverting the fix
fails both checks.

A legitimate FAIL therefore cannot produce a baseline artifact — the exact result most worth
preserving is the one the tooling discards. Run A's evidence survives only because it was captured
by hand. This is tracked and fixed separately; the tooling was deliberately **not** modified between
Run A and Run B, so that both runs measured the same benchmark implementation.
