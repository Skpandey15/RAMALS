from __future__ import annotations

from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from pydantic import ValidationError

from ramals_ai.grounding.contracts import GroundedContext, SourceType

NOW = datetime(2026, 8, 22, 12, tzinfo=timezone.utc)


def context(*items: dict[str, object]) -> GroundedContext:
    return GroundedContext.model_validate(
        {
            "contractVersion": "1.0",
            "contextId": "context-1",
            "learnerRef": "opaque-learner",
            "asOf": NOW.isoformat(),
            "expiresAt": (NOW + timedelta(minutes=5)).isoformat(),
            "retrievalPolicyVersion": "POLICY_V1",
            "items": list(items),
        }
    )


def item(
    source: str = "MASTERY", authority: str = "AUTHORITATIVE_FACT", **changes: object
) -> dict[str, object]:
    value: dict[str, object] = {
        "evidenceId": "evidence-1",
        "sourceType": source,
        "sourceVersion": "v1",
        "authority": authority,
        "factType": "MASTERY_SCORE",
        "value": "0.7200",
        "observedAt": NOW.isoformat(),
    }
    value.update(changes)
    return value


def test_required_authoritative_grounding_is_accepted() -> None:
    grounded = context(item())
    assert grounded.require_grounding({SourceType.MASTERY}, now=NOW) is grounded


def test_missing_summary_only_and_stale_grounding_fail_closed() -> None:
    with pytest.raises(ValueError, match="GROUNDING_REQUIRED_SOURCE_MISSING"):
        context(item(authority="MODEL_GENERATED_SUMMARY")).require_grounding(
            {SourceType.MASTERY}, now=NOW
        )
    with pytest.raises(ValueError, match="GROUNDING_STALE"):
        context(item()).require_grounding(
            {SourceType.MASTERY}, now=NOW + timedelta(minutes=6)
        )


@pytest.mark.parametrize(
    "bad_item,code",
    [
        (item(factType="LEARNER_EMAIL"), "GROUNDING_SENSITIVE_FIELD_REJECTED"),
        (item(value={"unrestricted": "database dump"}), "validation error"),
        (item(value="x" * 2049), "GROUNDING_VALUE_LIMIT_EXCEEDED"),
    ],
)
def test_sensitive_structured_and_oversized_values_are_rejected(
    bad_item: dict[str, object], code: str
) -> None:
    with pytest.raises(ValidationError, match=code):
        context(bad_item)


def test_unknown_fields_and_more_than_64_items_are_rejected() -> None:
    unknown = item()
    unknown["rawLearnerRecord"] = "no"
    with pytest.raises(ValidationError):
        context(unknown)
    with pytest.raises(ValidationError):
        context(*(item(evidenceId=f"e-{index}") for index in range(65)))


def test_shared_golden_contract_validates_in_python() -> None:
    fixture = Path(__file__).parents[3] / "contracts" / "golden" / "grounded-context-v1.json"
    grounded = GroundedContext.model_validate_json(fixture.read_text(encoding="utf-8"))
    grounded.require_grounding(
        {SourceType.MASTERY, SourceType.CURRICULUM_POLICY}, now=NOW
    )
