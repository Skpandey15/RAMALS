"""Golden evaluation datasets and release gates (M1-T15, M1-ADR-009).

Hard gates run in CI at 100%. Quality rubrics cannot be scored on ``ci-fake`` and are reported
unmeasured rather than passing.
"""

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
    "UNMEASURED",
    "Case",
    "CaseResult",
    "Dataset",
    "DatasetResult",
    "load_all",
    "load_dataset",
    "run",
]
