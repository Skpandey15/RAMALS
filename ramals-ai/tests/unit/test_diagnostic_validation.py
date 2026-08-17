"""Diagnostic proposal validation (M1-T09).

These mirror the deterministic gate's rules on the agent side. The gate remains the authority — a
proposal is refused there whatever happens here — so what these tests protect is a different thing:
that a recognisably wrong proposal is caught before it costs a learner a wait and the platform a
model call, and that the repair loop is given something specific to fix.

The pairs matter more than the individual cases. For each rule there is a case that must be refused
and a neighbouring case that must be allowed, because a validator that rejects everything is as
useless as one that rejects nothing, and only the allowed case proves the difference.
"""

from __future__ import annotations

import json
from typing import Any

import pytest

from ramals_ai.diagnostic.validation import validate

CONTEXT: dict[str, Any] = {
    "coverage": {
        "KAFKA_TOPIC": "INSUFFICIENT_EVIDENCE",
        "KAFKA_PARTITIONING": "NEEDS_PRACTICE",
    },
    "objectives": {
        "KAFKA_TOPIC": ["TOPIC_DEFINE", "TOPIC_PARTITION_COUNT"],
        "KAFKA_PARTITIONING": ["PARTITION_ORDERING"],
    },
}


def proposal(**overrides: Any) -> str:
    payload: dict[str, Any] = {
        "skillCode": "KAFKA_TOPIC",
        "objectiveCode": "TOPIC_DEFINE",
        "difficulty": "FOUNDATIONAL",
        "rationale": "No items have probed the definition of a topic yet.",
        "inferredStatus": None,
    }
    payload.update(overrides)
    return json.dumps(payload)


# -- schema --------------------------------------------------------------------------------------


def test_a_well_formed_proposal_validates() -> None:
    assert validate(proposal(), CONTEXT) == []


def test_non_json_is_rejected() -> None:
    assert validate("I suggest probing topics next.", CONTEXT) == ["SCHEMA_NOT_JSON"]


@pytest.mark.parametrize(
    ("overrides", "expected"),
    [
        ({"skillCode": ""}, "SCHEMA_MISSING_SKILL_CODE"),
        ({"objectiveCode": ""}, "SCHEMA_MISSING_OBJECTIVE_CODE"),
        ({"difficulty": "TRICKY"}, "SCHEMA_BAD_DIFFICULTY"),
        ({"rationale": ""}, "SCHEMA_MISSING_RATIONALE"),
        ({"rationale": "x" * 601}, "SCHEMA_RATIONALE_TOO_LONG"),
    ],
)
def test_schema_violations_are_named(overrides: dict[str, Any], expected: str) -> None:
    assert expected in validate(proposal(**overrides), CONTEXT)


def test_a_proposal_without_a_rationale_is_rejected() -> None:
    """An unreviewable proposal is one nobody can disagree with.

    Which is the opposite of what a proposing agent is for: the rationale is the part that makes
    disagreement possible, and disagreement is the mechanism the whole design depends on.
    """
    assert "SCHEMA_MISSING_RATIONALE" in validate(proposal(rationale="   "), CONTEXT)


def test_an_invented_field_is_rejected() -> None:
    assert "SCHEMA_UNEXPECTED_FIELD" in validate(proposal(masteryVerdict="MASTERED"), CONTEXT)


# -- the objective must belong to the skill ------------------------------------------------------


def test_an_objective_from_another_skill_is_rejected() -> None:
    """Real objective, wrong skill. Nothing looks invented, which is why it needs checking."""
    assert "OBJECTIVE_NOT_IN_SKILL" in validate(
        proposal(skillCode="KAFKA_TOPIC", objectiveCode="PARTITION_ORDERING"), CONTEXT
    )


def test_an_invented_objective_is_rejected() -> None:
    assert "OBJECTIVE_NOT_IN_SKILL" in validate(proposal(objectiveCode="TOPIC_VIBES"), CONTEXT)


def test_an_objective_the_context_offered_is_allowed() -> None:
    assert validate(proposal(objectiveCode="TOPIC_PARTITION_COUNT"), CONTEXT) == []


def test_validation_defers_when_the_context_listed_no_objectives() -> None:
    """The agent was not told what exists, so it cannot be faulted for the choice.

    Refusing here would punish the agent for the context's silence and duplicate a decision the gate
    can make properly, with the curriculum in front of it.
    """
    assert validate(proposal(objectiveCode="ANYTHING"), {"coverage": CONTEXT["coverage"]}) == []


def test_validation_defers_when_the_skill_was_not_offered() -> None:
    """An unknown skill is the gate's UNKNOWN_SKILL decision, made on complete information."""
    assert (
        validate(proposal(skillCode="KAFKA_STREAMS", objectiveCode="STREAMS_JOIN"), CONTEXT) == []
    )


# -- sparse evidence must not become a verdict ---------------------------------------------------


@pytest.mark.parametrize("inferred", ["MASTERED", "NEEDS_PRACTICE", "NOT_STARTED"])
def test_a_verdict_under_sparse_evidence_is_rejected(inferred: str) -> None:
    """KAFKA_TOPIC has INSUFFICIENT_EVIDENCE, so any status but that one is invented."""
    assert "INFERRED_VERDICT_FROM_INSUFFICIENT_EVIDENCE" in validate(
        proposal(skillCode="KAFKA_TOPIC", inferredStatus=inferred), CONTEXT
    )


def test_restating_sparseness_is_allowed() -> None:
    """The only honest inference under sparseness, and the agent needs it to justify a probe."""
    assert (
        validate(proposal(skillCode="KAFKA_TOPIC", inferredStatus="INSUFFICIENT_EVIDENCE"), CONTEXT)
        == []
    )


def test_a_recorded_status_may_be_restated() -> None:
    """With real evidence behind it, repeating the platform's finding is not inventing one."""
    assert (
        validate(
            proposal(
                skillCode="KAFKA_PARTITIONING",
                objectiveCode="PARTITION_ORDERING",
                inferredStatus="NEEDS_PRACTICE",
            ),
            CONTEXT,
        )
        == []
    )


@pytest.mark.parametrize(
    "rationale",
    [
        "The learner has not mastered topic definitions.",
        "They are failing on this skill.",
        "The learner clearly does not understand partitions.",
        "There is no understanding of topics here.",
    ],
)
def test_a_verdict_in_the_rationale_is_rejected(rationale: str) -> None:
    """A rationale can assert what the structured field carefully avoids.

    And the rationale is the half a human reviewer reads, so policing only the tidy field would
    police the half that was never going to be the problem.
    """
    assert "INFERRED_VERDICT_FROM_INSUFFICIENT_EVIDENCE" in validate(
        proposal(skillCode="KAFKA_TOPIC", rationale=rationale), CONTEXT
    )


def test_ordinary_coverage_reasoning_is_not_flagged() -> None:
    """A detector that fires on normal reasoning would make the rule useless by making it noisy."""
    rationale = (
        "Only one item has probed this skill, and it did not cover the definition itself, "
        "so a foundational probe should add the most information."
    )
    assert validate(proposal(skillCode="KAFKA_TOPIC", rationale=rationale), CONTEXT) == []


def test_a_verdict_about_a_well_measured_skill_is_not_flagged_here() -> None:
    """The rule is about inference from *sparse* evidence, not about strong language generally."""
    assert (
        validate(
            proposal(
                skillCode="KAFKA_PARTITIONING",
                objectiveCode="PARTITION_ORDERING",
                rationale="The learner has not mastered ordering across partitions.",
            ),
            CONTEXT,
        )
        == []
    )
