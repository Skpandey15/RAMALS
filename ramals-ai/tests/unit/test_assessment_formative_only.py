"""FORMATIVE_ONLY (M1-ADR-010), tested where a mistake could plausibly originate.

The rule is enforced in four places and this module tests the weakest one, so it is worth being
precise about what is being claimed. An authoritative AI score is *impossible* because the AI plane
holds no database credential (M1-T03) and ``ramals_ai_runtime`` has no privilege on ``ledger``
(V015) — proven elsewhere, in ``test_no_database_access`` and ``AiRuntimeBoundaryIntegrationTests``.

What is tested here is narrower and still worth having: that output *shaped* like a score never
leaves this service. A payload carrying ``{"score": 0.82}`` cannot become a mark today. It is
exactly the field a future well-meaning change would notice, plumb through, and render beside a real
one — and at that point the four-layer defence has one layer left.
"""

from __future__ import annotations

import json
from typing import Any

import pytest

from ramals_ai.assessment.evaluation import validate_evaluation

CONTEXT: dict[str, Any] = {"skillCode": "KAFKA_TOPIC", "masteryStatus": "NEEDS_PRACTICE"}


def evaluation(**overrides: Any) -> str:
    payload: dict[str, Any] = {
        "skillCode": "KAFKA_TOPIC",
        "indicators": {
            "strong": "Explains why ordering is per-partition and not per-topic.",
            "partial": "Describes a topic as a stream but treats partitions as replicas.",
            "weak": "Treats a topic as a queue that is consumed and emptied.",
        },
        "misconceptions": ["A topic is a queue that drains as consumers read it."],
        "suggestedProbe": "Ask what happens to a record after every consumer has read it.",
    }
    payload.update(overrides)
    return json.dumps(payload)


# -- nothing shaped like a score -------------------------------------------------------------------


def test_well_formed_formative_material_is_not_rejected() -> None:
    """The converse. A validator that refused everything would satisfy every test below and make
    the endpoint useless rather than safe."""
    assert validate_evaluation(evaluation(), CONTEXT) == []


def test_a_score_field_is_refused() -> None:
    """Caught twice over, and both codes are reported.

    The schema allowlist refuses it as an unexpected field; the scoring scan names it for what it
    is. The second code is the one an operator can count, so it must survive the presence of the
    first rather than being short-circuited by it.
    """
    errors = validate_evaluation(evaluation(score=0.82), CONTEXT)

    assert "FORMATIVE_SCORING_FIELD_PRESENT" in errors
    assert "SCHEMA_UNEXPECTED_FIELD" in errors


def test_a_nested_score_is_refused() -> None:
    """The top-level field check would not see this one.

    Two independent checks rather than one, so neither has to be the thing that always works: the
    schema allowlist catches it at the surface, and the recursive name scan catches it at depth.
    """
    indicators = {
        "strong": "Explains per-partition ordering.",
        "partial": {"text": "Partly there.", "score": 0.5},
        "weak": "Treats a topic as a queue.",
    }
    errors = validate_evaluation(evaluation(indicators=indicators), CONTEXT)

    assert "FORMATIVE_SCORING_FIELD_PRESENT" in errors


def test_scoring_field_names_are_refused_whatever_the_value_is() -> None:
    """Matched on the name alone. A field called ``score`` holding "not applicable" is still the
    field a later change reads as a score."""
    for name in ("grade", "mark", "percentage", "verdict", "passed", "masteryLevel", "rating"):
        payload = json.loads(evaluation())
        payload["indicators"]["extra"] = {name: "not applicable"}
        errors = validate_evaluation(json.dumps(payload), CONTEXT)
        assert "FORMATIVE_SCORING_FIELD_PRESENT" in errors, name


def test_a_scoring_field_name_is_caught_case_insensitively() -> None:
    payload = json.loads(evaluation())
    payload["indicators"]["extra"] = {"  MasteryScore  ": "high"}

    assert "FORMATIVE_SCORING_FIELD_PRESENT" in validate_evaluation(json.dumps(payload), CONTEXT)


# -- nothing shaped like a verdict, in prose (M1-T16) -------------------------------------------


@pytest.mark.parametrize(
    "probe",
    [
        "Assign a grade of B+ for this competency.",
        "Overall attainment here is 82%.",
        "This competency rates 7 out of 10.",
        "This would be a pass at the current standard.",
        "Place this at band 3 of 5.",
        "Mastery level: intermediate.",
    ],
    ids=["grade", "percentage", "out-of-ten", "pass-fail", "band", "mastery-level"],
)
def test_a_verdict_in_prose_is_refused(probe: str) -> None:
    """Found by the M1-T16 adversarial pass, where all six of these validated cleanly.

    M1-ADR-010 and the prompt both say no verdict "in any field or in prose", but only the field
    half was enforced. The scoring-field scan catches a key called ``score``; the learner-claim scan
    catches a sentence about a learner. Neither catches a grade with no subject and no field.

    It creates no evidence and the database boundary is untouched, so nothing becomes authoritative
    -- but it reaches a human reviewer as a verdict, from an endpoint whose whole contract is that
    it does not produce one.
    """
    assert "FORMATIVE_VERDICT_IN_PROSE" in validate_evaluation(
        evaluation(suggestedProbe=probe), CONTEXT
    )


def test_a_verdict_anywhere_in_the_payload_is_refused() -> None:
    """Not only the probe field. The scan reads every string at any depth."""
    indicators = {
        "strong": "Grade A work.",
        "partial": "Describes a topic as a stream.",
        "weak": "Treats a topic as a queue.",
    }

    assert "FORMATIVE_VERDICT_IN_PROSE" in validate_evaluation(
        evaluation(indicators=indicators), CONTEXT
    )


def test_ordinary_formative_language_is_not_a_verdict() -> None:
    """The control, and the reason the scan is narrow.

    Formative material legitimately discusses strength and weakness. A scan broad enough to flag
    "strong answers" would be switched off within a week, and then it would catch nothing at all.
    """
    indicators = {
        "strong": "Strong answers explain per-partition ordering without prompting.",
        "partial": "Describes a topic as a stream but treats partitions as replicas.",
        "weak": "Treats a topic as a queue that is consumed and emptied.",
    }

    assert (
        validate_evaluation(
            evaluation(
                indicators=indicators,
                suggestedProbe="Ask what happens to a record after every consumer has read it.",
            ),
            CONTEXT,
        )
        == []
    )


# -- nothing claimed about a learner ---------------------------------------------------------------


def test_a_claim_about_the_learner_is_refused() -> None:
    """The prompt is never given a learner's answers, so any such sentence is invented -- and an
    invented observation is indistinguishable, to a reader, from a real one."""
    errors = validate_evaluation(
        evaluation(suggestedProbe="The learner answered this incorrectly, so ask again."), CONTEXT
    )

    assert "FORMATIVE_CLAIMS_ABOUT_A_LEARNER" in errors


def test_a_mastery_verdict_in_prose_is_refused() -> None:
    """A verdict avoided in the structured fields and asserted in the prose is still a verdict, and
    the prose is the part a reviewer reads."""
    indicators = {
        "strong": "Explains per-partition ordering.",
        "partial": "Has not mastered partition semantics.",
        "weak": "Treats a topic as a queue.",
    }
    errors = validate_evaluation(evaluation(indicators=indicators), CONTEXT)

    assert "FORMATIVE_CLAIMS_ABOUT_A_LEARNER" in errors


def test_a_claim_that_something_was_recorded_is_refused() -> None:
    """Nothing here is stored as evidence. Saying so would tell a reviewer the platform holds a
    record it does not hold."""
    errors = validate_evaluation(
        evaluation(suggestedProbe="This assessment has been recorded against the skill."), CONTEXT
    )

    assert "FORMATIVE_CLAIMS_PERSISTENCE" in errors


# -- shape and policy ------------------------------------------------------------------------------


def test_material_about_a_different_skill_is_refused() -> None:
    errors = validate_evaluation(evaluation(skillCode="KAFKA_STREAMS"), CONTEXT)

    assert "POLICY_SKILL_NOT_REQUESTED" in errors


def test_a_missing_indicator_band_is_refused() -> None:
    indicators = {"strong": "Explains ordering.", "weak": "Treats a topic as a queue."}

    assert "SCHEMA_MISSING_INDICATOR" in validate_evaluation(
        evaluation(indicators=indicators), CONTEXT
    )


def test_output_that_is_not_json_is_refused() -> None:
    assert validate_evaluation("Strong learners understand partitions.", CONTEXT) == [
        "SCHEMA_NOT_JSON"
    ]


def test_too_many_misconceptions_are_refused() -> None:
    errors = validate_evaluation(evaluation(misconceptions=["m"] * 9), CONTEXT)

    assert "SCHEMA_TOO_MANY_MISCONCEPTIONS" in errors
