"""AI evaluation release gates (M1-T15, Doc 07 §2 and §5, M1-ADR-009).

These are the gates that block a pull request. They are hard gates in Doc 07's sense — properties of
the system rather than of a model — so they hold on ``ci-fake`` exactly as they would on a real
provider and can run in CI at 100% with no tolerance.

What is deliberately absent is the other half. The 0.90 functional and 0.85 pedagogical rubrics
cannot be scored here: ``ci-fake`` returns a deterministic canned string, so a number computed from
it would describe the fake. M1-ADR-009 makes those release-candidate gates and requires that an
unmeasured dimension is never rendered as a pass, which
``test_quality_rubrics_are_reported_unmeasured_not_passed`` asserts rather than leaves to a comment.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from ramals_ai.evaluation import UNMEASURED, load_all, load_dataset, run

DATASET_ROOT = Path(__file__).resolve().parents[2].parent / "evaluation" / "datasets"

DATASETS = sorted(DATASET_ROOT.glob("*.json"))

# Every agent surface that produces model output must be covered. Listed explicitly rather than
# derived from the files, so deleting a dataset fails instead of shrinking the suite silently.
REQUIRED_AGENTS = {"TUTOR", "DIAGNOSTIC", "ASSESSMENT", "ASSESSMENT_EVALUATE", "ADAPTATION"}


def dataset_ids() -> list[str]:
    return [path.stem for path in DATASETS]


# -- the hard gate ---------------------------------------------------------------------------------


@pytest.mark.parametrize("path", DATASETS, ids=dataset_ids())
def test_hard_gates_pass_at_one_hundred_percent(path: Path) -> None:
    """Doc 07 §2. No tolerance, and the failure message names the case rather than a count."""
    result = run(load_dataset(path))

    failures = [
        f"{failure.case.id}: expected valid={failure.case.expect_valid} "
        f"wanted={list(failure.case.expect_reason_codes)} got={list(failure.reason_codes)}"
        for failure in result.failures
    ]

    assert failures == [], f"{result.dataset.agent} ({result.dataset.version}): " + "; ".join(
        failures
    )
    assert result.hard_gate_passed


@pytest.mark.parametrize("path", DATASETS, ids=dataset_ids())
def test_a_known_bad_case_fails_for_its_stated_reason(path: Path) -> None:
    """A case that fails for the wrong reason has stopped testing what it was written for.

    The subtle version of a dead test: a fixture written to catch an authority violation gets
    reworded, now fails schema validation instead, and still passes the suite while no longer
    exercising the authority rule at all.
    """
    result = run(load_dataset(path))

    for case_result in result.results:
        if case_result.case.expect_valid:
            continue
        for expected in case_result.case.expect_reason_codes:
            assert expected in case_result.reason_codes, (
                f"{case_result.case.id} should fail with {expected}, "
                f"got {list(case_result.reason_codes)}"
            )


@pytest.mark.parametrize("path", DATASETS, ids=dataset_ids())
def test_every_dataset_has_known_good_and_known_bad_cases(path: Path) -> None:
    """Doc 07 §5. A suite of only passes would be satisfied by disabling validation entirely; a
    suite of only failures would be satisfied by rejecting everything."""
    dataset = load_dataset(path)

    assert any(case.expect_valid for case in dataset.cases), "no known-good case"
    assert any(not case.expect_valid for case in dataset.cases), "no known-bad case"


# -- what the harness must not claim ---------------------------------------------------------------


@pytest.mark.parametrize("path", DATASETS, ids=dataset_ids())
def test_quality_rubrics_are_reported_unmeasured_not_passed(path: Path) -> None:
    """Required by M1-ADR-009.

    ``ci-fake`` returns a deterministic canned string, so any rubric score computed here describes
    the fake. The dimensions are reported explicitly as UNMEASURED rather than omitted, because an
    omitted dimension reads as "fine" in a report and an absent number reads as zero in a
    comparison.
    """
    quality = run(load_dataset(path)).quality()

    assert quality, "quality dimensions must be reported, even when unmeasurable"
    for dimension, value in quality.items():
        assert value == UNMEASURED, f"{dimension} must not be scored on ci-fake, got {value}"


# -- dataset integrity -----------------------------------------------------------------------------


def test_every_agent_surface_has_a_dataset() -> None:
    """A missing dataset is an ungated agent, which looks identical to a passing one."""
    covered = {load_dataset(path).agent for path in DATASETS}

    assert covered == REQUIRED_AGENTS


def test_dataset_versions_are_unique_and_named() -> None:
    """M1-ADR-009 identifies an approved baseline partly by dataset version.

    Two datasets sharing a version, or carrying a blank one, makes a recorded result ambiguous about
    what it was scored against — and the comparison it feeds silently meaningless.
    """
    versions = [dataset.version for dataset in load_all(DATASET_ROOT)]

    assert all(version.strip() for version in versions)
    assert len(versions) == len(set(versions))


def test_every_case_has_a_stable_id_and_a_reason_for_existing() -> None:
    """Ids must be unique within a dataset so a result can name a case.

    ``why`` is required because a golden case whose purpose is undocumented gets deleted the first
    time it is inconvenient, and nobody can tell what stopped being covered.
    """
    for dataset in load_all(DATASET_ROOT):
        ids = [case.id for case in dataset.cases]
        assert len(ids) == len(set(ids)), f"{dataset.agent} has duplicate case ids"
        for case in dataset.cases:
            assert case.id.strip(), f"{dataset.agent} has a case without an id"
            assert case.why.strip(), f"{dataset.agent}/{case.id} does not say why it exists"


def test_each_dataset_scores_against_its_own_agent_validator() -> None:
    """The dataset names the validator, and it must be the agent's own.

    A harness that reimplemented the rules would measure the harness. Resolving the reference proves
    the function exists and is importable, so a renamed validator fails here rather than silently
    scoring nothing.
    """
    for dataset in load_all(DATASET_ROOT):
        validator = dataset.validator()

        assert callable(validator)
        assert dataset.validator_ref.startswith("ramals_ai.")


def test_datasets_are_valid_json_with_the_required_shape() -> None:
    """Read as data before being trusted as a suite."""
    for path in DATASETS:
        raw = json.loads(path.read_text(encoding="utf-8"))

        for key in ("agent", "datasetVersion", "validator", "cases"):
            assert key in raw, f"{path.name} is missing {key}"
        assert raw["cases"], f"{path.name} has no cases"
