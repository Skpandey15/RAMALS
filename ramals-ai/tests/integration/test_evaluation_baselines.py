"""Approved baselines and the regression rule (M1-T15 slice 2, M1-ADR-009).

The regression rule is the one gate that can be satisfied by doing nothing, so it needs the most
care. A comparison that returns "no regression" when it could not measure anything is worse than no
comparison at all: it produces a green run and a false claim in the same breath.

So these tests are mostly about the ways a comparison can be wrong while looking right — an
unmeasured dimension read as a catastrophic drop or as a pass, a baseline approved by nobody, a
comparison across mismatched dataset versions, an expired acceptance still being honoured, and a
hard-gate regression being waived by someone senior enough.
"""

from __future__ import annotations

import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from ramals_ai.evaluation.baseline import (
    REGRESSION_LIMIT,
    Approval,
    Baseline,
    BaselineError,
    BaselineIdentity,
    RegressionAcceptance,
    approved_baseline_for,
    compare,
    load_baselines,
)
from ramals_ai.evaluation.harness import UNMEASURED, load_all, run

EVALUATION_ROOT = Path(__file__).resolve().parents[2].parent / "evaluation"
REGISTER = EVALUATION_ROOT / "baselines.json"
DATASET_ROOT = EVALUATION_ROOT / "datasets"

TODAY = date(2026, 8, 20)


def identity(dataset_version: str = "tutor-golden-v1", agent: str = "TUTOR") -> BaselineIdentity:
    return BaselineIdentity(
        agent=agent,
        agent_version="TUTOR_AGENT_V1",
        prompt_version="TUTOR_PROMPT_V1",
        model_route="ci-fake",
        dataset_version=dataset_version,
    )


def baseline(quality: dict[str, str], hard_gate_passed: bool = True) -> Baseline:
    return Baseline(
        identity=identity(),
        approval=Approval(approved_by="a-named-person", approved_on=TODAY),
        hard_gate_passed=hard_gate_passed,
        quality=quality,
    )


# -- the hard gate cannot be waived ------------------------------------------------------------


def test_a_hard_gate_regression_blocks_even_with_an_acceptance() -> None:
    """Zero tolerated means zero: no owner is senior enough to approve a cross-learner leak."""
    result = compare(
        baseline({"primaryTaskFunctionalRubric": "0.95"}),
        identity(),
        hard_gate_passed=False,
        quality={"primaryTaskFunctionalRubric": "0.95"},
        acceptance=RegressionAcceptance(
            owner="a-very-senior-person", scope="everything", expires_on=date(2099, 1, 1)
        ),
        today=TODAY,
    )

    assert result.hard_gate_regressed
    assert result.blocks_release()
    assert "hard-gate regression, which cannot be accepted" in result.reasons()


def test_a_failing_hard_gate_blocks_regardless_of_the_baseline() -> None:
    result = compare(
        baseline({}, hard_gate_passed=False),
        identity(),
        hard_gate_passed=False,
        quality={},
        today=TODAY,
    )

    assert result.blocks_release()
    assert "hard gate failed" in result.reasons()


# -- the quality regression rule ------------------------------------------------------------------


def test_a_drop_beyond_the_limit_blocks() -> None:
    """Doc 07 §2: no absolute drop greater than 0.05 on the normalized scale."""
    result = compare(
        baseline({"primaryTaskFunctionalRubric": "0.92"}),
        identity(),
        hard_gate_passed=True,
        quality={"primaryTaskFunctionalRubric": "0.85"},
        today=TODAY,
    )

    assert result.blocks_release()
    assert result.quality_regressions[0].drop == Decimal("0.07")


def test_a_drop_exactly_at_the_limit_does_not_block() -> None:
    """The rule is "greater than 0.05". A boundary written the other way would move the threshold
    to 0.049 without anyone deciding to."""
    result = compare(
        baseline({"primaryTaskFunctionalRubric": "0.90"}),
        identity(),
        hard_gate_passed=True,
        quality={"primaryTaskFunctionalRubric": "0.85"},
        today=TODAY,
    )

    assert result.quality_regressions == ()
    assert not result.blocks_release()
    assert Decimal("0.05") == REGRESSION_LIMIT


def test_an_improvement_does_not_block() -> None:
    result = compare(
        baseline({"primaryTaskFunctionalRubric": "0.85"}),
        identity(),
        hard_gate_passed=True,
        quality={"primaryTaskFunctionalRubric": "0.95"},
        today=TODAY,
    )

    assert not result.blocks_release()


# -- unmeasured is neither a pass nor a zero -------------------------------------------------------


def test_an_unmeasured_dimension_is_not_reported_as_a_regression() -> None:
    """The trap this class of code falls into: UNMEASURED coerced to 0.00 reads as a 0.95 drop,
    which would block every release for a dimension nobody measured."""
    result = compare(
        baseline({"primaryTaskFunctionalRubric": "0.95"}),
        identity(),
        hard_gate_passed=True,
        quality={"primaryTaskFunctionalRubric": UNMEASURED},
        today=TODAY,
    )

    assert result.quality_regressions == ()
    assert result.unmeasured == ("primaryTaskFunctionalRubric",)
    assert not result.blocks_release()


def test_a_measured_candidate_against_an_unmeasured_baseline_is_not_a_comparison() -> None:
    """The asymmetric case, and the one a coercion bug hides.

    If the baseline side coerced UNMEASURED to 0.00, this dimension would look comparable: the drop
    would be a negative number, no regression would be reported, and the dimension would vanish from
    `unmeasured` -- so a first-ever measurement would read as "compared, fine" rather than as having
    no reference point. Found by perturbation: coercing the baseline to zero passed every other test
    in this file.
    """
    result = compare(
        baseline({"primaryTaskFunctionalRubric": UNMEASURED}),
        identity(),
        hard_gate_passed=True,
        quality={"primaryTaskFunctionalRubric": "0.95"},
        today=TODAY,
    )

    assert result.unmeasured == ("primaryTaskFunctionalRubric",)
    assert result.quality_regressions == ()
    assert result.dimensions[0].drop is None


def test_an_unmeasured_dimension_is_still_reported() -> None:
    """The other half. Not blocking must not mean not mentioning: a dimension that quietly vanishes
    from a report is indistinguishable from one that passed."""
    result = compare(
        baseline({"primaryTaskFunctionalRubric": UNMEASURED}),
        identity(),
        hard_gate_passed=True,
        quality={"primaryTaskFunctionalRubric": UNMEASURED},
        today=TODAY,
    )

    assert "primaryTaskFunctionalRubric" in result.unmeasured


# -- acceptance needs an owner, a scope and an expiry ----------------------------------------------


def test_a_valid_acceptance_unblocks_a_quality_regression() -> None:
    result = compare(
        baseline({"primaryTaskFunctionalRubric": "0.92"}),
        identity(),
        hard_gate_passed=True,
        quality={"primaryTaskFunctionalRubric": "0.80"},
        acceptance=RegressionAcceptance(
            owner="a-named-owner", scope="tutor prompt v2 rollout", expires_on=date(2026, 9, 30)
        ),
        today=TODAY,
    )

    assert result.quality_regressions
    assert not result.blocks_release()


def test_an_expired_acceptance_stops_working() -> None:
    """An acceptance with no expiry is how a temporary exception becomes permanent, so expiry is
    required — and it has to actually lapse."""
    result = compare(
        baseline({"primaryTaskFunctionalRubric": "0.92"}),
        identity(),
        hard_gate_passed=True,
        quality={"primaryTaskFunctionalRubric": "0.80"},
        acceptance=RegressionAcceptance(
            owner="a-named-owner", scope="tutor prompt v2 rollout", expires_on=date(2026, 8, 19)
        ),
        today=TODAY,
    )

    assert result.blocks_release()
    assert any("expired" in reason for reason in result.reasons())


@pytest.mark.parametrize(
    "raw",
    [
        {"scope": "s", "expiresOn": "2026-09-30"},
        {"owner": "o", "expiresOn": "2026-09-30"},
        {"owner": "o", "scope": "s"},
        {"owner": "   ", "scope": "s", "expiresOn": "2026-09-30"},
    ],
    ids=["no-owner", "no-scope", "no-expiry", "blank-owner"],
)
def test_an_incomplete_acceptance_is_not_an_acceptance(raw: dict[str, str]) -> None:
    """M1-ADR-000's shape, applied here: all three or none."""
    with pytest.raises(BaselineError):
        RegressionAcceptance.parse(raw)


# -- a baseline must be approved, and comparable ---------------------------------------------------


def test_a_baseline_without_a_named_approver_is_refused() -> None:
    """Approval is not conferred by a run being recent, best, or the only one available."""
    with pytest.raises(BaselineError):
        Approval.parse({"approvedBy": "  ", "approvedOn": "2026-08-20"})


def test_comparing_across_dataset_versions_is_refused() -> None:
    """Not reported as a pass. A comparison across dataset versions has no meaning, and returning
    "no regression" would be a confident wrong answer."""
    with pytest.raises(BaselineError):
        compare(
            baseline({"primaryTaskFunctionalRubric": "0.95"}),
            identity(dataset_version="tutor-golden-v2"),
            hard_gate_passed=True,
            quality={"primaryTaskFunctionalRubric": "0.95"},
            today=TODAY,
        )


def test_a_superseded_baseline_is_not_the_current_one() -> None:
    """Re-approval supersedes; it does not overwrite. The old entry stays readable."""
    old = Baseline(
        identity=identity(),
        approval=Approval(approved_by="someone", approved_on=date(2026, 1, 1)),
        hard_gate_passed=True,
        quality={},
        superseded_by="2026-08-20",
    )
    current = baseline({})

    assert approved_baseline_for((old, current), identity()) is current


# -- the register that ships -----------------------------------------------------------------------


def test_the_register_is_readable_and_every_entry_is_approved() -> None:
    baselines = load_baselines(REGISTER)

    assert baselines
    for entry in baselines:
        assert entry.approval.approved_by.strip()


def test_every_dataset_has_an_approved_baseline() -> None:
    """A dataset with no baseline cannot be regression-checked, which looks identical to passing."""
    baselines = load_baselines(REGISTER)

    for dataset in load_all(DATASET_ROOT):
        found = approved_baseline_for(
            baselines,
            BaselineIdentity(
                agent=dataset.agent,
                agent_version="",
                prompt_version="",
                model_route="",
                dataset_version=dataset.version,
            ),
        )
        assert found is not None, f"{dataset.agent}/{dataset.version} has no approved baseline"


def test_the_shipped_register_claims_no_quality_measurement() -> None:
    """Every recorded quality dimension is UNMEASURED, because ci-fake cannot score one.

    This is the assertion that would fail the day somebody records a rubric number without a real
    provider run behind it — which is exactly how an unmeasured dimension becomes a baseline.
    """
    raw = json.loads(REGISTER.read_text(encoding="utf-8"))

    for entry in raw["baselines"]:
        for dimension, value in entry.get("quality", {}).items():
            assert value == UNMEASURED, (
                f"{entry['agent']}/{dimension} records {value}, but the route is "
                f"{entry['modelRoute']} — a score from ci-fake describes the fake"
            )


# -- the rollback smoke test -----------------------------------------------------------------------


def test_a_prompt_rollback_still_evaluates_and_still_gates() -> None:
    """M1-T15 required test: prompt/model rollback eval smoke test.

    M1-ADR-008 makes rollback a pointer move — the route's prompt version changes and recorded
    proposal metadata is never rewritten. What must survive that is the gate: a rolled-back prompt
    is still scored, and still compared against the baseline for the dataset it ran on.
    """
    rolled_back = BaselineIdentity(
        agent="TUTOR",
        agent_version="TUTOR_AGENT_V1",
        prompt_version="TUTOR_PROMPT_V0",
        model_route="tutor-default",
        dataset_version="tutor-golden-v1",
    )

    # The dataset still runs, and the hard gate still holds, under a different prompt pointer.
    dataset = next(d for d in load_all(DATASET_ROOT) if d.agent == "TUTOR")
    result = run(dataset)
    assert result.hard_gate_passed

    comparison = compare(
        baseline({"primaryTaskFunctionalRubric": UNMEASURED}),
        rolled_back,
        hard_gate_passed=result.hard_gate_passed,
        quality={"primaryTaskFunctionalRubric": UNMEASURED},
        today=TODAY,
    )

    # Comparable because the dataset version is unchanged; a rollback moves the prompt pointer, not
    # the thing the prompt is being measured on.
    assert not comparison.blocks_release()
    assert comparison.identity.prompt_version == "TUTOR_PROMPT_V0"
