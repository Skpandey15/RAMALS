"""The Python half of the cross-language contract guarantee.

The Java suite runs these same fixtures through hand-written records; this one runs them through
generated Pydantic models. Both must accept the identical bytes and emit the identical shape.

Either side alone proves nothing about the other. Together they are what makes M1-ADR-002 safe:
generating one side and hand-writing the other is only defensible while these fixtures agree.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from pydantic import BaseModel

from ramals_ai.contracts.generated import (
    AIProposalEnvelope,
    AIRequestEnvelope,
    Capabilities,
    Problem,
)
from ramals_ai.grounding.contracts import GroundedContext

GOLDEN = Path(__file__).parents[3] / "contracts" / "golden"

ROUND_TRIP_CASES: list[tuple[str, type[BaseModel]]] = [
    ("request-tutor-minimal.json", AIRequestEnvelope),
    ("request-tutor-full.json", AIRequestEnvelope),
    # BTECH_DBMS is synthetic: no seed data, no curriculum, no content. It exists so the boundary is
    # proven domain-neutral before a second domain is built, and its identifiers sit in the 65-96
    # band that the contract used to reject while core.skill.stable_code accepted it.
    ("request-tutor-cross-domain.json", AIRequestEnvelope),
    ("proposal-tutor.json", AIProposalEnvelope),
    ("proposal-assessment-evaluate.json", AIProposalEnvelope),
    ("capabilities.json", Capabilities),
    ("problem-deadline-exceeded.json", Problem),
    ("grounded-context-v1.json", GroundedContext),
]


def load(fixture: str) -> dict[str, Any]:
    payload: dict[str, Any] = json.loads((GOLDEN / fixture).read_text(encoding="utf-8"))
    return payload


@pytest.mark.parametrize(("fixture", "model"), ROUND_TRIP_CASES)
def test_fixture_round_trips(fixture: str, model: type[BaseModel]) -> None:
    original = load(fixture)
    parsed = model.model_validate(original)
    # exclude_none so an absent optional stays absent rather than becoming an explicit null, which
    # is what the Java side emits and what the fixture records.
    assert parsed.model_dump(mode="json", exclude_none=True, by_alias=True) == original


def test_every_golden_fixture_is_exercised() -> None:
    """A fixture the Python side never loads could pin a shape only Java sees."""
    present = sorted(p.name for p in GOLDEN.glob("*.json"))
    covered = sorted(fixture for fixture, _ in ROUND_TRIP_CASES)
    assert present == covered


def test_decimal_precision_is_carried_as_a_string() -> None:
    """0.7200 must not become 0.72, and must never be parsed as a float.

    Mastery reproducibility is the property the entire MVP-0 control rests on. A float round-trip
    anywhere on this boundary would break it silently and only at some scales.
    """
    parsed = AIRequestEnvelope.model_validate(load("request-tutor-full.json"))
    assert parsed.learningContext is not None
    assert parsed.learningContext.masteryScore is not None
    assert parsed.learningContext.masteryScore.root == "0.7200"
    assert isinstance(parsed.learningContext.masteryScore.root, str)


def test_evaluation_proposal_is_formative_only() -> None:
    """M1-ADR-010 asserted on the wire shape, not only in prose."""
    parsed = AIProposalEnvelope.model_validate(load("proposal-assessment-evaluate.json"))
    assert parsed.trustLevel.value == "FORMATIVE_ONLY"


def test_unknown_field_is_rejected_on_a_closed_envelope() -> None:
    """`additionalProperties: false` must survive generation.

    Without it the contract stops constraining anything: a peer could send any extra field and both
    sides would silently accept it.
    """
    payload = load("request-tutor-minimal.json")
    payload["unexpectedField"] = "should not be accepted"
    with pytest.raises(ValueError, match="unexpectedField|Extra inputs"):
        AIRequestEnvelope.model_validate(payload)


def test_open_proposal_payload_is_preserved_verbatim() -> None:
    """The agent payload is deliberately open at v1.0; it must pass through untouched."""
    parsed = AIProposalEnvelope.model_validate(load("proposal-tutor.json"))
    assert parsed.proposal["responseType"] == "EXPLAIN_WITH_ANALOGY"
    assert isinstance(parsed.proposal["checksForUnderstanding"], list)


def test_deadline_ceiling_is_enforced_by_the_contract() -> None:
    """15000 ms is the widest hard deadline in M1-ADR-001; nothing may ask for more."""
    payload = load("request-tutor-minimal.json")
    payload["constraints"]["deadlineMs"] = 60000
    with pytest.raises(ValueError, match="deadlineMs|less than or equal"):
        AIRequestEnvelope.model_validate(payload)
