# AI evaluation datasets and gates

Versioned golden datasets for M1-T15, governed by
[M1-ADR-009](../docs/adr/M1-ADR-009-ai-evaluation-release-gates.md) and Doc 07.

## Why the data lives here and not in a test

The tutor dataset began as a Python literal inside `test_tutor_agent.py`. That works until you need
the two things Doc 07 §5 asks for and M1-ADR-009 depends on: a dataset **version** that a recorded
result can name, and dataset changes reviewed **independently** of model changes.

Neither is possible when the data is a literal in a test file. A change to a case and a change to a
prompt look the same in review, and a stored result cannot say which dataset produced it — so a
later comparison cannot tell a genuine quality movement from a dataset edit.

## Layout

```text
evaluation/datasets/<agent>.json
```

Each file carries a `datasetVersion`, the validator it scores against, and its cases. A case is
`expectValid: true` (known-good) or `false` (known-bad), and a known-bad case names the reason codes
it must produce — not merely that it fails, because a case that fails for the wrong reason is a case
that stops testing what it was written for.

## Gate types

Doc 07 §2 separates these and so does the harness.

**Hard gates** are properties of the system rather than of a model — schema validity, authority,
leakage, the security corpus. They hold on `ci-fake` exactly as on a real provider, so they run on
every pull request at 100% with no tolerance.

**Quality rubrics** cannot run in CI. `ci-fake` returns a deterministic canned string, so a score
computed from it describes the fake. They are release-candidate gates, and an unmeasured dimension is
reported as unmeasured, never as a pass.

## Changing a dataset

Bump `datasetVersion`, and do not land the change alongside a prompt, model-route or agent change.
M1-ADR-009 requires the separation; the reason is that a prompt regression landed beside a dataset
edit produces a green run and a baseline shift that reads as a dataset improvement.
