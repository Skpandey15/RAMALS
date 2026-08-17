"""Schema and semantic validation of tutor output (Doc 07 §2 and §3).

Schema validity is the easy half: the shape parses or it does not, and it is a 100% hard gate.

The semantic half is the one that matters. "You struggled with this last week" reads as attentive
personalization and is indistinguishable, to the learner, from something the platform actually
knows. Nobody reviewing the output would flag it. Doc 07 §3 names unsupported learner-state claims
as a primary Tutor measure for exactly that reason.
"""

from __future__ import annotations

import json

import pytest

from ramals_ai.tutor.minimizer import MinimizedContext
from ramals_ai.tutor.validation import validate

CONTEXT = MinimizedContext(
    {
        "skillCode": "KAFKA_PARTITIONING",
        "masteryScore": "0.7200",
        "masteryStatus": "NEEDS_PRACTICE",
        "prerequisites": ["KAFKA_TOPIC"],
    }
)


def output(**overrides: object) -> str:
    payload: dict[str, object] = {
        "responseType": "EXPLAIN",
        "explanation": "A partition is an ordered, append-only log.",
        "checksForUnderstanding": ["What happens to ordering across two partitions?"],
    }
    payload.update(overrides)
    return json.dumps(payload)


# -- schema --------------------------------------------------------------------------------------


def test_a_well_formed_response_validates() -> None:
    assert validate(output(), CONTEXT) == []


def test_non_json_is_rejected() -> None:
    assert validate("I'd be happy to explain partitions!", CONTEXT) == ["SCHEMA_NOT_JSON"]


def test_a_json_array_is_rejected() -> None:
    assert validate("[]", CONTEXT) == ["SCHEMA_NOT_OBJECT"]


@pytest.mark.parametrize(
    ("overrides", "expected"),
    [
        ({"responseType": "LECTURE"}, "SCHEMA_BAD_RESPONSE_TYPE"),
        ({"explanation": ""}, "SCHEMA_MISSING_EXPLANATION"),
        ({"explanation": "x" * 4001}, "SCHEMA_EXPLANATION_TOO_LONG"),
        ({"checksForUnderstanding": []}, "SCHEMA_MISSING_CHECKS"),
        ({"checksForUnderstanding": ["a"] * 6}, "SCHEMA_TOO_MANY_CHECKS"),
        ({"checksForUnderstanding": [""]}, "SCHEMA_BAD_CHECK"),
    ],
)
def test_schema_violations_are_named(overrides: dict[str, object], expected: str) -> None:
    assert expected in validate(output(**overrides), CONTEXT)


def test_an_invented_field_is_rejected() -> None:
    """A model that invents fields may be inventing content to put in them.

    An unexpected field is the one place a fabricated verdict could hide from every other check.
    """
    assert "SCHEMA_UNEXPECTED_FIELD" in validate(output(masteryVerdict="MASTERED"), CONTEXT)


def test_semantic_checks_do_not_run_on_a_malformed_object() -> None:
    """Reporting semantics for fields that may not be what they appear is noise, not signal."""
    errors = validate(output(explanation=""), CONTEXT)
    assert errors == ["SCHEMA_MISSING_EXPLANATION"]


# -- unsupported learner-state claims ------------------------------------------------------------


@pytest.mark.parametrize(
    "fabrication",
    [
        "Last time you got this wrong, so let's go slower.",
        "Previously you attempted this and struggled.",
        "Since your last session you have improved.",
        "You usually confuse partitions with replicas.",
        "Your progress shows steady improvement here.",
        "Earlier this week you completed the prerequisite.",
        "When you first tried this, ordering was the sticking point.",
    ],
)
def test_claims_about_learner_history_are_rejected(fabrication: str) -> None:
    """The context carries a current status and nothing temporal; a past reference is invented."""
    assert "UNSUPPORTED_LEARNER_HISTORY_CLAIM" in validate(output(explanation=fabrication), CONTEXT)


@pytest.mark.parametrize(
    "fabrication",
    [
        "Ask your teacher about this topic.",
        "Your class covered this last term.",
        "Other students who struggled here found analogies useful.",
    ],
)
def test_claims_about_learner_identity_are_rejected(fabrication: str) -> None:
    assert "UNSUPPORTED_LEARNER_IDENTITY_CLAIM" in validate(
        output(explanation=fabrication), CONTEXT
    )


def test_announcing_a_mastery_verdict_is_rejected() -> None:
    """Progression is decided by a deterministic engine from recorded evidence.

    A learner told by a tutor that they have mastered something has been told something the platform
    has not decided.
    """
    assert "UNSUPPORTED_MASTERY_CLAIM" in validate(
        output(explanation="Great work - you have now mastered partitioning."), CONTEXT
    )


def test_referring_to_the_supplied_status_is_allowed() -> None:
    """The tutor may use what it was given; the rule is about announcing a *new* outcome."""
    assert (
        validate(
            output(explanation="Your current status is needs-practice, so let's target ordering."),
            CONTEXT,
        )
        == []
    )


def test_a_check_for_understanding_is_scanned_too() -> None:
    """Fabrication in a question is still fabrication, and questions are shown to the learner."""
    assert "UNSUPPORTED_LEARNER_HISTORY_CLAIM" in validate(
        output(checksForUnderstanding=["Remember what you answered last time?"]), CONTEXT
    )


def test_ordinary_pedagogical_prose_is_not_flagged() -> None:
    """A detector that fires on normal teaching would make the gate useless by making it noisy."""
    prose = (
        "A partition is an ordered, append-only log. Ordering is guaranteed within a partition "
        "but not across partitions, which is the usual source of confusion. Consider what happens "
        "when two messages with the same key are produced."
    )
    assert validate(output(explanation=prose), CONTEXT) == []
