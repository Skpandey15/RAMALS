"""Deterministic quality evaluation for the MVP-2 diagnostic assessment agent.

The scorer deliberately ignores free-form reasons and provider metadata.  Those values may change
without changing the diagnosis.  Release decisions are based on contract validity, authoritative
evidence references, stable semantic classifications, and the expected useful recommendation.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class DiagnosticGoldenCase:
    id: str
    evidence_ids: frozenset[str]
    expected_classifications: dict[str, str]
    expected_next_skills: frozenset[str]


@dataclass(frozen=True)
class DiagnosticObservation:
    case: DiagnosticGoldenCase
    proposals: tuple[dict[str, Any], ...]
    schema_valid: tuple[bool, ...]

    def __post_init__(self) -> None:
        if not self.proposals or len(self.proposals) != len(self.schema_valid):
            raise ValueError("an observation needs one validity result for every proposal")


@dataclass(frozen=True)
class DiagnosticThresholds:
    schema_validity: Decimal = Decimal("1.00")
    evidence_support: Decimal = Decimal("1.00")
    stability: Decimal = Decimal("1.00")
    business_usefulness: Decimal = Decimal("0.90")


@dataclass(frozen=True)
class DiagnosticEvaluationResult:
    schema_validity: Decimal
    evidence_support: Decimal
    stability: Decimal
    business_usefulness: Decimal

    def passes(self, thresholds: DiagnosticThresholds | None = None) -> bool:
        required = thresholds or DiagnosticThresholds()
        return (
            self.schema_validity >= required.schema_validity
            and self.evidence_support >= required.evidence_support
            and self.stability >= required.stability
            and self.business_usefulness >= required.business_usefulness
        )


def load_diagnostic_cases(path: Path) -> tuple[DiagnosticGoldenCase, ...]:
    """Load the reviewed, version-controlled diagnostic scenarios."""
    raw = json.loads(path.read_text(encoding="utf-8"))
    return tuple(
        DiagnosticGoldenCase(
            id=case["id"],
            evidence_ids=frozenset(case["evidenceIds"]),
            expected_classifications=dict(case["expectedClassifications"]),
            expected_next_skills=frozenset(case["expectedNextSkills"]),
        )
        for case in raw["cases"]
    )


def evaluate_diagnostics(
    observations: tuple[DiagnosticObservation, ...],
) -> DiagnosticEvaluationResult:
    """Score golden runs; refuse to turn an absent measurement into a passing score."""
    if not observations:
        raise ValueError("at least one diagnostic observation is required")

    runs = [
        (observation, proposal, valid)
        for observation in observations
        for proposal, valid in zip(observation.proposals, observation.schema_valid, strict=True)
    ]
    schema = _rate(valid for _, _, valid in runs)
    evidence = _rate(
        _evidence_supported(proposal, observation.case.evidence_ids)
        for observation, proposal, _ in runs
    )
    stable = _rate(_stable(observation.proposals) for observation in observations)
    useful = _rate(
        _useful(observation.proposals[0], observation.case) for observation in observations
    )
    return DiagnosticEvaluationResult(schema, evidence, stable, useful)


def semantic_signature(
    proposal: dict[str, Any],
) -> tuple[tuple[tuple[str, str], ...], tuple[str, ...]]:
    """The provider-independent meaning of a proposal; explanatory prose is intentionally absent."""
    classifications = tuple(
        sorted(
            (str(item.get("skillCode", "")), str(item.get("classification", "")))
            for item in proposal.get("diagnoses", [])
        )
    )
    recommendations = tuple(
        sorted(str(value) for value in proposal.get("recommendedNextSkills", []))
    )
    return classifications, recommendations


def _evidence_supported(proposal: dict[str, Any], allowed: frozenset[str]) -> bool:
    diagnoses = proposal.get("diagnoses")
    return (
        isinstance(diagnoses, list)
        and bool(diagnoses)
        and all(
            bool(item.get("evidenceIds")) and set(item["evidenceIds"]).issubset(allowed)
            for item in diagnoses
        )
    )


def _stable(proposals: tuple[dict[str, Any], ...]) -> bool:
    expected = semantic_signature(proposals[0])
    return all(semantic_signature(proposal) == expected for proposal in proposals[1:])


def _useful(proposal: dict[str, Any], case: DiagnosticGoldenCase) -> bool:
    classifications, recommendations = semantic_signature(proposal)
    return (
        dict(classifications) == case.expected_classifications
        and set(recommendations) == case.expected_next_skills
    )


def _rate(values: Any) -> Decimal:
    measured = tuple(bool(value) for value in values)
    if not measured:
        raise ValueError("a diagnostic metric cannot be unmeasured")
    return Decimal(sum(measured)) / Decimal(len(measured))
