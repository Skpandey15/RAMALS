# Performance evidence: MVP-0 mixed-learning baseline

> **These numbers are indicative, not an SLA.** They were measured on a developer workstation
> (Rancher Desktop, containers and load generator on the same host), which is explicitly **not** the
> authoritative fixed-spec performance environment. **R1 remains open**: what is closed is the
> harness gap that made any run impossible, not the calibration itself.

| | |
| --- | --- |
| Scenario | `mixed-learning` — the documented MVP-0 request-class mix |
| Executor | open model, ramping arrival rate: 30 s warm-up → 1 min ramp → 2 min steady at 60 rps |
| Release under test | `v0.1.0-rc1`, published GHCR digests |
| Learner pool | 20 distinct learners, provisioned at runtime |
| Environment label | `local-unqualified` |

## The label was a claim, not evidence

That `local-unqualified` was honest, but only because whoever ran it chose to be. `RAMALS_PERF_ENV`
was free text copied straight into the baseline, so the same run with the variable set to
`perf-standard-01` would have produced a file asserting it was calibrated, on this same workstation,
with nothing anywhere to disagree.

Provisioning the authoritative environment would not have closed that. The claim was never checked
against anything, so a conforming host and a laptop would have produced equally confident labels.

The environment is now declared in `performance/environment/perf-standard-01.json` and measured by
`attest.py`: a run that does not conform is recorded as `local-unqualified` whatever was asked for,
and `baseline.schema.json` rejects a baseline whose label and attestation disagree. Run it against
any candidate machine and it prints what that machine lacks.

R1 is still open, and still needs hardware. What it no longer needs is trust in the label.

## 1. The harness could not run at all — four defects

**a. It could not authenticate.** The scenarios use `grant_type=password` against `ramals-web-ui`,
but the shipped realm has direct access grants **disabled** on that client and defines **no users**.
Fixed with `performance/provision-load-fixtures.py`, which provisions a learner pool at runtime and
restores the original posture in a trap — the committed realm is never modified.

**b. Every VU shared one learner.** All scenarios called `acquireAccessToken()` once and handed the
same token to every VU. Every mastery and progression write therefore serialised on a single
learner's rows behind `SELECT ... FOR UPDATE`. That measures lock contention on one row, not system
capacity. Fixed with a token pool spread across VUs by `__VU`.

**c. The heaviest request class measured nothing.** `progression()` — 35% of the mix — assigned
`response.request.tags` *after* the request had completed. That is a no-op: the sample is already
recorded. So `skill_map_read` emitted untagged samples and its `p(95)<250` threshold was evaluated
against **no data at all**. Fixed by passing tags in the request options.

**d. Baselines were written as nulls.** `run-baseline.sh` read percentiles from
`.metrics.<name>.values["p(95)"]`. The k6 0.5x summary export writes those flat on the metric, so
every path resolved to `null` and the distilled baseline recorded null latency, null throughput and
a zero error rate. Both layouts are now accepted, and the script refuses to write a baseline whose
p95 is null rather than emitting a plausible-looking file full of nulls.

### Security defect: the summary export leaked bearer tokens

k6 embeds the return value of `setup()` in `--summary-export`, and `setup()` returns access tokens.
Every exported summary — a file intended to be archived and compared across runs — therefore
contained **live bearer credentials**. This predates the pool change (it was one token; the pool made
it twenty). `run-baseline.sh` now strips `setup_data` before anything else touches the file, and
`performance/results/` is gitignored.

## 2. Run A — shipped configuration

| Metric | Value |
| --- | --- |
| Requests | 12,317 (58.48 rps) |
| **Failed** | **16.33%** (2,012) |
| Checks passed | 86.06% |
| p50 / p95 | 7.87 ms / 17.81 ms |

Every failure was **HTTP 429**, and the limiter was behaving exactly as designed. The latency
profile confirms it: rejection is cheap, so p95 stayed at 17.81 ms while a sixth of traffic was
turned away. Saturation looks nothing like this.

`RateLimitFilter` keys its token bucket on the **client IP** (`X-Forwarded-For`, else the remote
address). A load generator is one IP, so all 20 authenticated learners shared a single bucket. At
the shipped defaults (`capacity 120`, `refill 60/s`) a 60 rps arrival rate sits exactly on the
limit, and bursts are rejected.

## 3. Run B — capacity, with the IP limit lifted

Re-run with `performance/compose.perf-override.yml`, which raises the bucket so the run measures the
application rather than the limiter. That override is a load-generation aid and **must never be
applied to a real environment**.

| Metric | Value |
| --- | --- |
| Requests | 12,459 (59.16 rps) |
| **Failed** | **0.00%** (0) |
| Checks passed | **100.00%** (9,599 / 9,599) |
| p50 / p95 | 6.70 ms / 26.77 ms |

Per request class, against the MVP-0 budgets:

| Request class | p95 | Budget | Result |
| --- | --- | --- | --- |
| `skill_map_read` | 14.14 ms | 250 ms | pass |
| `mastery_read` | 12.38 ms | 250 ms | pass |
| `recommendation_read` | 9.97 ms | 250 ms | pass |
| `diagnostic` | 17.11 ms | 500 ms | pass |
| `assessment_write` | 36.73 ms | 400 ms | pass |

All k6 thresholds passed (exit 0). The margin is wide, but the arrival rate was capped at 60 rps by
the scenario — **this run establishes correctness under sustained mixed load, not a capacity
ceiling.** No saturation point was found because none was searched for.

## 4. Finding for MVP-1: rate limiting is keyed on IP, not identity

Run A is a load-generation artefact, but the underlying behaviour is a product concern.

Because the bucket is keyed on client IP, **every user behind a shared egress IP shares one 60 rps
allowance** — a school, an office, a mobile carrier NAT, or any deployment behind a reverse proxy
that does not set a per-user forwarded header. A single cohort could throttle itself while each
individual user is well within any reasonable per-user rate.

The conventional shape is to key on the authenticated subject when a request carries a valid token,
falling back to IP only for unauthenticated traffic — preserving IP-based protection against
anonymous floods without penalising legitimate shared-origin users.

**Resolved.** Rate limiting was split into two tiers: a pre-authentication ceiling keyed on client
IP that sheds floods before any JWT is validated, and a post-authentication tier keyed on the
verified token subject that enforces per-learner fair use. Users behind a shared egress IP no longer
throttle each other.

The subject tier deliberately runs *after* token validation. Reading `sub` from an unverified token
in the pre-authentication filter would let a caller drain a chosen victim's allowance by borrowing
their subject, or mint unlimited fresh buckets by varying the claim.

This also means a future load run needs `RAMALS_LOAD_LEARNERS` set high enough that the simulated
learners are not individually over the fair-use limit; the override below is no longer required for
that reason alone.

> **Update, 2026-08-21 — the resolution shipped in code but not in configuration.** The calibrated
> run on `perf-standard-01` reproduced this section's Run A almost exactly (17.33% failures, all
> HTTP 429) on `v0.1.0-rc3`. `SubjectRateLimitFilter` and the two-tier `RateLimitProperties` are
> present and correct, but `application.yml` binds the **pre-authentication IP tier** to
> `capacity 120 / refill 60` — the numbers intended for the per-subject tier, whose own IP-tier
> default is `600 / 300` — and does not configure the subject tier at all. So the shared-egress
> problem described above is only partly resolved in a deployed system, and raising
> `RAMALS_LOAD_LEARNERS` cannot relieve it, because the binding constraint is the shared IP bucket.
> Tracked as **TD-R1-01** on the [release board](../mvp1-release-board.md). Qualified evidence:
> [R1 evidence package](r1-calibrated-baseline.md).

## Reproducing

```bash
# Provisions fixtures, runs the scenario, scrubs tokens, restores the realm — including on failure
RAMALS_FIXTURE_NETWORK=ramals-deploy_edge \
RAMALS_LOAD_PASSWORD='<load-only password>' \
  bash performance/run-baseline.sh mixed-learning
```

For a capacity run, bring the stack up with the override first:

```bash
docker compose -f deploy/compose.deploy.yml -f performance/compose.perf-override.yml up -d
```
