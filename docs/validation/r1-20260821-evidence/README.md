# R1 evidence package — 2026-08-21

Raw evidence for two runs against `perf-standard-01`. **Both belong to the R1 record. Neither
replaces the other, and neither may be modified, deleted, overwritten or relabelled.**

The analysis is in
[`r1-20260821-run-a-and-run-b-report.md`](../r1-20260821-run-a-and-run-b-report.md).

| Directory | Identity | Disposition |
| --- | --- | --- |
| `run-a/` | **R1 Run A — VALID / FAIL — production rate-limit policy active** | The canonical result. No rate-limit override. 9,599 iterations, 12,417 requests, 60 rps sustained, 2,153 × HTTP 429, `http_req_failed` 17.33%. Latency thresholds passed comfortably; error-rate threshold failed. |
| `run-b/` | **R1 Run B — capacity characterization / perf rate-limit override** | Not a production-policy result. `compose.perf-override.yml` active, verified before load. 9,599 iterations, 12,519 requests, 0 failures, all thresholds passed. |

## Contents

- `*.summary.json` — k6 summary export, `setup_data` scrubbed by the harness (it carries bearer
  tokens)
- `*.baseline.json` — distilled baseline. **Present for Run B only.** Run A produced none: k6 exits
  non-zero on a breached threshold and `run-baseline.sh` ran under `set -e`, so distillation never
  executed. That defect is fixed separately; the tooling was deliberately left unchanged between
  the two runs so both measured the same benchmark implementation.
- `*.attestation.json` — the environment attestation carried from the SUT, `conforms: true`
- `*-k6-console.log` — full console output including per-class thresholds
- `*-backend.log.gz` — complete backend request log, the primary evidence for the status
  distribution
- `*-status-distribution.txt` — status codes counted from that log
- `*-effective-rate-limit.txt` — the rate-limit configuration read off the running container
- `*-restarts-oom.txt` — restart counts and OOM-kill flags for all four services
- `runB-telemetry/` — `docker stats` and `pg_stat_database` samples taken across Run B
- `runB-override-metadata.json` — records that the override was active, and everything that was not
  changed relative to Run A

## A note on completeness

The uncompressed backend logs (11 MB and 26 MB) are stored gzipped here. Nothing was truncated or
filtered; `gunzip` reproduces them byte for byte.

Run A's evidence was captured by hand after the run, because the tooling discarded it. That is the
reason this directory exists rather than a `results/` artifact set.

## The one alteration made to Run A's evidence, and why

`run-a/…summary.json` had its `setup_data` field removed, and carries a `$scrub_note` saying so.

That field held **20 live learner access tokens**. k6 writes `setup()`'s return value into the
summary export, and `run-baseline.sh` strips it during baseline distillation — which never ran for
Run A, because a breached threshold aborts the script first. gitleaks caught it when this evidence
was first committed.

This is the scrub the harness was supposed to apply, applied late. **No measurement was touched**:
`metrics` and `root_group` are byte-identical to what k6 wrote, and `setup_data` contains no
measurements — only credentials. The tokens were already invalid when found, since the Keycloak
instance had been destroyed and the load learners deleted at teardown, and they never reached a
published branch.

Nothing else in either bundle has been modified.
