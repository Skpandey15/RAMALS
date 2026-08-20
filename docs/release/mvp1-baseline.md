# MVP-1 baseline record

The measured state of the platform at MVP-1 exit, and — just as deliberately — what is **not**
measured and why.

This exists because "establish baseline metrics" is the third clause of the Updated Implementation
Master Plan v2.0 §5, and because MVP-2 is meant to be compared against MVP-1. A comparison needs a
reference point that was written down before the thing it will be compared with, and that says
plainly which of its numbers are load-bearing.

Two kinds of number appear below and they must not be read the same way. **Measured** values were
produced by a run and are reproducible from this commit. **Unmeasured** values are named, with the
reason, and are never rendered as though they had passed.

## Identity

| | |
| --- | --- |
| Deterministic control | tag `v0.1.0-rc2` — the MVP-0 reference baseline |
| Schema | Flyway `V001`–`V023` |
| AI contract | `1.0.0`, frozen at `contracts/baseline/ai-internal.openapi.v1.yaml` |
| Measured on | a developer workstation, **not** the authoritative fixed-spec environment |

### Frozen deterministic engines

Seven identifiers define every consequential decision and are pinned by behaviour hash in
`EngineVersionFreezeTests`. Changing a weight, threshold, rounding mode or branch under an existing
identifier fails the build.

```text
DIAGNOSTIC_SCORING_V1     WEIGHTED_MASTERY_V1       EVIDENCE_CONFIDENCE_V1
MASTERY_STATUS_POLICY_V1  RECOMMENDATION_POLICY_V1  PROGRESSION_POLICY_V1
SESSION_POLICY_V1
```

### Agent and prompt versions

M1-ADR-009 identifies an evaluation baseline by `agentVersion`, `promptVersion` and `modelRoute`.
Those values at MVP-1 exit:

| Agent | Agent version | Prompt version | Route | Model | Soft / hard cost (USD) | p95 budget |
| --- | --- | --- | --- | --- | --- | --- |
| Tutor | `TUTOR_AGENT_V1` | `TUTOR_PROMPT_V1` | `tutor-default` | claude-sonnet-5 | 0.020 / 0.050 | 8000 ms |
| Diagnostic | `DIAGNOSTIC_AGENT_V1` | `DIAGNOSTIC_PROMPT_V1` | `diagnostic-default` | claude-sonnet-5 | 0.015 / 0.040 | 6000 ms |
| Assessment | `ASSESSMENT_AGENT_V1` | `ASSESSMENT_PROMPT_V1` | `assessment-default` | claude-sonnet-5 | 0.030 / 0.060 | 10000 ms |
| Adaptation | `ADAPTATION_AGENT_V1` | `ADAPTATION_PROMPT_V1` | `adaptation-default` | claude-sonnet-5 | 0.015 / 0.040 | 6000 ms |
| — | — | `CI_FAKE_PROMPT_V1` | `ci-fake` | deterministic fake | 0 / 0 | 1000 ms |

Cost ceilings are enforced before dispatch, not observed after it: a call whose worst case would
exceed the remaining request budget never reaches a provider.

## Measured

### Automated verification

| Suite | Tests | Skipped | Failed |
| --- | ---: | ---: | ---: |
| Backend — unit (`test`) | 431 | 0 | 0 |
| Backend — integration (`integrationTest`) | 107 | 0 | 0 |
| Backend — governance (`governanceTest`) | 120 | 0 | 0 |
| Backend — architecture (`architectureTest`) | 33 | 0 | 0 |
| **Backend total** | **691** | **0** | **0** |
| Python AI plane | 430 | 0 | 0 |
| Web UI | 39 | 0 | 0 |
| **All** | **1160** | **0** | **0** |

Zero skipped is part of the measurement. A skipped integration suite is the usual way a database
guarantee stops being verified without anyone noticing, so the count is recorded rather than the
pass/fail alone.

### Hard gates (Doc 07 §2, enforced on every pull request)

These are properties of the system rather than of a model, so they hold on `ci-fake` exactly as on a
real provider — enforced by context minimization, output validation, the trust pipeline, the
deterministic gates and the absence of any database credential in the AI plane.

| Gate | Threshold | State |
| --- | --- | --- |
| Schema-valid proposals | 100% | Measured — enforced per request, unusable output becomes an explicitly empty proposal |
| Authority / safety hard cases | 100% | Measured |
| Prompt / tool security corpus | 100% | Measured |
| Cross-learner leakage | 0 incidents | Measured — structural: the minimizer allowlist means the material never enters the process |
| Active answer-key leakage | 0 incidents | Measured — structural, same mechanism |
| Hard-gate regression | 0 tolerated | Measured |

### Database hot paths

All eight critical queries are index-served with no sequential scan; plans archived in
[db-hot-path-plans.md](evidence/db-hot-path-plans.md).

## Not measured, and why

Recording these is the point of the document. Each is a number MVP-2 might otherwise be compared
against, and none of them exists.

| Metric | Why it does not exist | What would produce it |
| --- | --- | --- |
| **Latency and throughput baseline** (`mixed-learning`, `diagnostic` including Adaptive Decision Latency) | **R1.** The harness is repaired and produces clean data, but only from a developer workstation, and Doc 07 §4 makes developer-machine numbers indicative only | An authoritative fixed-spec environment |
| **Primary task functional rubric** (≥ 0.90 normalized) | `ci-fake` returns a deterministic canned string; any score computed from it describes the fake | A release-candidate run against a real route (M1-ADR-009) |
| **Tutor pedagogical rubric** (≥ 0.85 normalized) | Same | Same |
| **Regression vs approved baseline** (no drop > 0.05) | There is no approved baseline yet, because there is no first measurement to approve | The first real-route evaluation, approved by a named person under M1-ADR-009 |
| **Agent latency and cost against Doc 01 / Doc 04** | Route ceilings are enforced, but observed distributions require production-shaped load | R1's environment plus a provider credential |

R1 has been open since MVP-0 and is tracked on the [release board](mvp1-release-board.md). It is not
blocked on engineering: nothing in T00–T17 will close it, and under
[M1-ADR-000](../adr/M1-ADR-000-mvp1-engineering-before-r1.md) it still blocks the MVP-1 release
candidate and any calibrated deterministic-versus-agentic comparison unless a named owner accepts the
risk with explicit scope and expiry.

## How MVP-2 should use this

Compare against the **measured** rows, and treat the unmeasured ones as absent rather than as
baselines of zero. The specific failure this guards against is a later report claiming MVP-2 improved
latency or quality against MVP-1 — a comparison that cannot be made, because MVP-1 never measured
either on an authoritative environment.

When R1 closes, add the measured values here as a new section rather than editing this one. A
baseline that can be edited is not a baseline, which is the same reason
`EngineVersionFreezeTests` refuses an updated hash and M1-ADR-009 makes approved evaluation baselines
append-only.
