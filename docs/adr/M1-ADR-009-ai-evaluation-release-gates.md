# M1-ADR-009: AI evaluation release gates and regression governance

- **Status:** Accepted
- **Date:** 2026-08-20
- **Relates to:** MVP-1 Canonical Package v1.3 Doc 07, Doc 01 §5, Doc 04, M1-T15, M1-ADR-000, M1-ADR-008

## Context

Doc 07 already owns the numbers. It fixes the hard gates, the quality thresholds, the regression
limit and the unit convention, and M1-T15's objective is to make prompt, model and agent changes
regression-testable *"using Doc 07 numbers"*.

So this decision is not about what the thresholds are. It is about the four questions Doc 07 does not
answer, each of which will otherwise be answered implicitly by whoever writes the first evaluation
run:

1. Which gates block a pull request, and which block a release candidate? They cannot all block a
   pull request, because the quality thresholds are not measurable in CI.
2. What makes a baseline "approved", given Doc 07 says to compare against one?
3. Who may accept a regression, and on what terms?
4. How are dataset changes kept from laundering a model regression, given Doc 07 requires them to be
   reviewed independently?

The second, third and fourth matter most, and for the same reason: a threshold with no named owner
for its exceptions is a threshold that gets waived by whoever is under deadline pressure, and the
waiver leaves no trace.

## Decision

### The thresholds are Doc 07's, transcribed here once

| Dimension | Threshold | Gate type |
| --- | --- | --- |
| Schema-valid proposals | 100% on the release golden suite | Hard |
| Authority/safety hard cases | 100% pass | Hard |
| Prompt/tool security hard corpus | 100% pass | Hard |
| Cross-learner leakage | 0 incidents | Hard |
| Active answer-key leakage | 0 incidents | Hard |
| Primary task functional rubric | mean ≥ 0.90 normalized, no critical-case failure | Quality |
| Tutor pedagogical rubric | mean ≥ 0.85 normalized | Quality |
| Regression vs approved baseline | no absolute drop > 0.05 on the normalized 0.00–1.00 scale in any primary quality dimension | Regression |
| Hard-gate regression | 0 tolerated | Hard |

All normalized scores use the 0.00–1.00 scale, so the 0.05 regression limit is five percentage
points on that scale. Reports may render percentages; the stored and computed gate uses normalized
units.

**Doc 07 remains the authority.** This table is a transcription, and it is transcribed rather than
referenced only because the canonical package is not in this repository — a reader of a fresh clone
would otherwise have no way to see the numbers their build is gated on. That is a known asymmetry
recorded in [the ADR register](README.md) and on the release board. If the package is ever brought
into the repository, this table should become a reference to it, because a number kept in two places
eventually disagrees with itself.

### Hard gates block CI; quality gates block a release candidate

Hard gates are properties of the system, not of a model: schema validity, authority, leakage and the
security corpus hold on `ci-fake` exactly as they would on a real provider, because they are enforced
by minimization, validation, the trust pipeline and the absence of a database credential. They run on
every pull request and a failure blocks the merge.

Quality thresholds cannot run there. `ci-fake` returns a deterministic canned string, so any rubric
score computed from it describes the fake. They are therefore **release-candidate gates**, evaluated
against a real route before an RC is cut, and a build that has not measured them reports them as
*unmeasured* rather than as passing. A green pull request is not a claim about tutor quality.

This is already the situation M1-T07 documented and it is not a concession introduced here; naming it
is what stops "the evaluation suite is green" being read as more than it says.

### An approved baseline is a named, immutable, human-approved record

A baseline is a stored evaluation result identified by its `agentVersion`, `promptVersion` and
`modelRoute`, together with the dataset version it was scored on. It becomes *approved* when a named
person records that approval; approval is not conferred by a run being recent, by being the best
score so far, or by being the only one available.

Baselines are append-only. Re-approving a new baseline supersedes the old one and does not overwrite
it, for the same reason MVP-0 keeps superseded engine versions: a comparison whose reference point
can be edited is not a comparison.

### A regression may be accepted only by a named owner, in writing, with scope and expiry

A drop greater than 0.05 in any primary quality dimension blocks the release candidate. It may be
accepted, but only as M1-ADR-000 accepts R1: a **named owner**, an explicit **scope**, and an
**expiry**. An acceptance without all three is not an acceptance.

A hard-gate regression cannot be accepted at all. Zero tolerated means zero: there is no owner senior
enough to approve a cross-learner leak.

### Dataset changes and model changes are reviewed separately, and cannot land together

Doc 07 requires independent review; the mechanism is that a change to a golden dataset and a change
to a prompt, model route or agent must not appear in the same pull request.

The failure this prevents is specific and hard to see afterwards. A prompt change that regresses
quality, landed alongside a dataset edit, produces a green run and an unexplained baseline shift —
and the shift looks like a dataset improvement. Separating them means every quality movement has
exactly one candidate cause.

Datasets are versioned, and a run records the dataset version it scored against. A result compared
against a baseline scored on a different dataset version is not a regression check, and is reported
as incomparable rather than as a pass.

## Alternatives rejected

- **Run the quality rubrics in CI against `ci-fake`.** Produces a number that describes the fake and
  would be indistinguishable, in a report, from a number describing the model. Worse than having no
  number, because it would be trusted.
- **Block pull requests on quality thresholds using a real provider.** Puts a paid, non-deterministic
  dependency in the path of every merge, and makes the merge gate flaky for reasons unrelated to the
  change under review.
- **Let the most recent passing run become the baseline automatically.** Removes the human from the
  one place the process needs one, and lets quality drift downward one acceptable step at a time
  while every individual comparison passes.
- **Allow hard-gate exceptions with sufficient seniority.** The hard gates are the properties that
  make agent output safe to show a learner. An exception process for them is an outage waiting for a
  deadline.

## Consequences

- M1-T15 implements the harness against these rules rather than choosing them, and its
  "regression threshold test" has a definition to test against.
- A pull request can be green while tutor quality is unmeasured, and reports must say so. Any
  release-evidence document that reports quality must state the route it was measured on.
- R1 still gates the calibrated deterministic-versus-agentic comparison and the MVP-1 release
  candidate under M1-ADR-000; this decision does not relax that and cannot, because the comparison it
  would license is the one R1 exists to make possible.
- The transcription above is a maintenance liability while Doc 07 lives outside the repository. That
  is the cost of the numbers being visible at all, and it is the smaller cost.

## Verification

- M1-T15 required tests: known-good fixture passes; known-bad authority/security fixture fails; the
  regression threshold is enforced; prompt and model rollback produce an evaluation smoke result;
  dataset versioning integrity holds.
- The hard gates already have standing tests — schema validity, the trust pipeline, leakage and
  injection corpora, and the authority boundary — which run on every pull request today.
- A build that has not measured quality against a real route reports those dimensions as unmeasured;
  a test asserts that an unmeasured dimension is never rendered as a pass.
