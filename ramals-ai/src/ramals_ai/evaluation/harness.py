"""The golden evaluation harness (M1-T15, Doc 07 §2 and §5, M1-ADR-009).

Loads versioned datasets from ``evaluation/datasets`` and scores each case through the agent's own
validator — the same function the running agent uses, not a copy of its rules. A harness with its
own reimplementation of validation measures the harness.

Two properties this module exists to keep apart, because Doc 07 separates them and conflating them
is how an evaluation suite starts reporting more than it knows:

**Hard gates** are properties of the system rather than of a model. Schema validity, authority,
leakage and the security corpus hold on ``ci-fake`` exactly as they would on a real provider,
because they are enforced by minimization, validation and the trust pipeline. They run in CI at
100% with no tolerance.

**Quality rubrics** cannot run here at all. ``ci-fake`` returns a deterministic canned string, so a
rubric score computed from it describes the fake. They are release-candidate gates, and this module
reports them as :data:`UNMEASURED` rather than as passing — an absent measurement rendered as a pass
is the failure M1-ADR-009 names explicitly.
"""

from __future__ import annotations

import importlib
import json
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

UNMEASURED = "UNMEASURED"
"""What a dimension reports when it has no measurement. Never a pass, never a zero."""

DATASET_ROOT = Path(__file__).resolve().parents[3].parent / "evaluation" / "datasets"


@dataclass(frozen=True)
class Case:
    """One golden case: an output, the context it was produced under, and what must happen to it."""

    id: str
    gate: str
    context: dict[str, Any]
    output: str
    expect_valid: bool
    expect_reason_codes: tuple[str, ...]
    why: str


@dataclass(frozen=True)
class Dataset:
    """A versioned golden suite for one agent surface."""

    agent: str
    version: str
    validator_ref: str
    cases: tuple[Case, ...]

    def validator(self) -> Callable[[str, dict[str, Any]], list[str]]:
        """Resolves ``module:function`` to the agent's own validator.

        Late-bound on purpose. The dataset names the function it scores against, so a dataset cannot
        drift onto a different validator without the change being visible in the dataset file,
        which is also the file whose version a recorded result carries.
        """
        module_name, _, function_name = self.validator_ref.partition(":")
        module = importlib.import_module(module_name)
        resolved: Callable[[str, dict[str, Any]], list[str]] = getattr(module, function_name)
        return resolved


@dataclass(frozen=True)
class CaseResult:
    """What happened to one case, in enough detail to say why it failed."""

    case: Case
    reason_codes: tuple[str, ...]

    @property
    def valid(self) -> bool:
        return not self.reason_codes

    @property
    def passed(self) -> bool:
        """Whether the case behaved as the dataset says it must.

        A known-bad case must fail *for its stated reasons*. Accepting any failure would let a case
        keep passing while silently testing something else — a malformed fixture rejected for being
        malformed rather than for the authority violation it was written to catch.
        """
        if self.valid is not self.case.expect_valid:
            return False
        if self.case.expect_valid:
            return True
        return all(code in self.reason_codes for code in self.case.expect_reason_codes)


@dataclass(frozen=True)
class DatasetResult:
    """The outcome of one dataset, and the quality dimensions it could not measure."""

    dataset: Dataset
    results: tuple[CaseResult, ...]

    @property
    def hard_gate_passed(self) -> bool:
        """Doc 07 §2: 100%, no tolerance."""
        return all(result.passed for result in self.hard_gate_results)

    @property
    def hard_gate_results(self) -> tuple[CaseResult, ...]:
        return tuple(result for result in self.results if result.case.gate == "HARD")

    @property
    def failures(self) -> tuple[CaseResult, ...]:
        return tuple(result for result in self.results if not result.passed)

    def quality(self) -> dict[str, str]:
        """Quality rubrics, which this harness cannot score.

        Returned as an explicit mapping to UNMEASURED rather than omitted, so a caller rendering a
        report has to say something about them. An omitted dimension is easy to read as "fine".
        """
        return {
            "primaryTaskFunctionalRubric": UNMEASURED,
            "tutorPedagogicalRubric": UNMEASURED,
        }


def load_dataset(path: Path) -> Dataset:
    """Reads one dataset file."""
    raw = json.loads(path.read_text(encoding="utf-8"))
    cases = tuple(
        Case(
            id=case["id"],
            gate=case.get("gate", "HARD"),
            context=case.get("context", {}),
            output=case["output"],
            expect_valid=bool(case["expectValid"]),
            expect_reason_codes=tuple(case.get("expectReasonCodes", ())),
            why=case.get("why", ""),
        )
        for case in raw["cases"]
    )
    return Dataset(
        agent=raw["agent"],
        version=raw["datasetVersion"],
        validator_ref=raw["validator"],
        cases=cases,
    )


def load_all(root: Path | None = None) -> Iterator[Dataset]:
    """Every dataset, in a stable order so a report is diffable run to run."""
    directory = root or DATASET_ROOT
    for path in sorted(directory.glob("*.json")):
        yield load_dataset(path)


def run(dataset: Dataset) -> DatasetResult:
    """Scores every case through the agent's own validator."""
    validator = dataset.validator()
    results = tuple(
        CaseResult(case=case, reason_codes=tuple(validator(case.output, dict(case.context))))
        for case in dataset.cases
    )
    return DatasetResult(dataset=dataset, results=results)
