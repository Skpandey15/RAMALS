"""Golden evaluation datasets and release gates (M1-T15, M1-ADR-009).

Hard gates run in CI at 100%. Quality rubrics cannot be scored on ``ci-fake`` and are reported
unmeasured rather than passing.
"""

from ramals_ai.evaluation.baseline import (
    REGRESSION_LIMIT,
    Approval,
    Baseline,
    BaselineError,
    BaselineIdentity,
    Comparison,
    RegressionAcceptance,
    approved_baseline_for,
    compare,
    load_baselines,
)
from ramals_ai.evaluation.harness import (
    UNMEASURED,
    Case,
    CaseResult,
    Dataset,
    DatasetResult,
    load_all,
    load_dataset,
    run,
)

__all__ = [
    "REGRESSION_LIMIT",
    "UNMEASURED",
    "Approval",
    "Baseline",
    "BaselineError",
    "BaselineIdentity",
    "Comparison",
    "RegressionAcceptance",
    "approved_baseline_for",
    "compare",
    "load_baselines",
    "Case",
    "CaseResult",
    "Dataset",
    "DatasetResult",
    "load_all",
    "load_dataset",
    "run",
]
