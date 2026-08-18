"""Item validation, and the fact that it mirrors Spring rather than replacing it.

The rules here exist to make the M1-T06 repair loop useful: a candidate refused by Spring has
already cost a model call, and the agent can rewrite inside the same request if it is told what was
wrong early. Spring's ``ContentValidationPipeline`` remains the authority.

Every test below is about a failure that would otherwise reach a reviewer looking well-formed. That
is the class of defect worth catching: an item that is obviously broken wastes nobody's time.
"""

from __future__ import annotations

import json
from typing import Any

from ramals_ai.assessment.validation import validate_item

CONTEXT: dict[str, Any] = {
    "skillCode": "KAFKA_TOPIC",
    "masteryStatus": "NEEDS_PRACTICE",
    "availableObjectives": ["TOPIC_DEFINE", "TOPIC_PARTITION"],
}


def item(**overrides: Any) -> str:
    payload: dict[str, Any] = {
        "skillCode": "KAFKA_TOPIC",
        "objectiveCode": "TOPIC_DEFINE",
        "difficulty": "FOUNDATIONAL",
        "stem": "A Kafka topic is best described as:",
        "options": [
            "A durable, named, append-only stream of records",
            "A single mutable database row",
            "A transient in-memory cache",
        ],
        "answerKey": ["A durable, named, append-only stream of records"],
        "rationale": "A topic is an append-only log; the other options describe mutable stores.",
    }
    payload.update(overrides)
    return json.dumps(payload)


# -- the shape of the answer ----------------------------------------------------------------------


def test_a_well_formed_item_is_not_rejected() -> None:
    """The converse of everything else. A validator that refused every item would satisfy each
    negative test below and generate nothing."""
    assert validate_item(item(), CONTEXT) == []


def test_output_that_is_not_json_is_refused() -> None:
    assert validate_item("Here is a nice question about topics.", CONTEXT) == ["SCHEMA_NOT_JSON"]


def test_a_json_array_is_not_an_item() -> None:
    assert validate_item("[]", CONTEXT) == ["SCHEMA_NOT_OBJECT"]


def test_an_unexpected_field_is_refused() -> None:
    """``extra`` fields are how a "helpful" addition becomes a silent contract change."""
    assert "SCHEMA_UNEXPECTED_FIELD" in validate_item(item(itemCode="KAFKA_01"), CONTEXT)


# -- the failures that look fine -------------------------------------------------------------------


def test_an_answer_key_naming_an_absent_option_is_refused() -> None:
    """The defect this whole slice exists for.

    Nothing throws. The item renders, reads well, and scores every learner wrong -- and the wrong
    scores become evidence the mastery engine treats as real.
    """
    errors = validate_item(item(answerKey=["A partition"]), CONTEXT)

    assert "STRUCTURAL_ANSWER_KEY_NOT_IN_OPTIONS" in errors


def test_an_item_where_every_option_is_correct_is_refused() -> None:
    options = ["A log", "An append-only log", "A durable log"]
    errors = validate_item(item(options=options, answerKey=options), CONTEXT)

    assert "STRUCTURAL_EVERY_OPTION_CORRECT" in errors


def test_an_item_with_no_correct_option_is_refused() -> None:
    assert "STRUCTURAL_NO_CORRECT_OPTION" in validate_item(item(answerKey=[]), CONTEXT)


def test_a_single_option_item_is_refused() -> None:
    errors = validate_item(item(options=["A log"], answerKey=["A log"]), CONTEXT)

    assert "STRUCTURAL_TOO_FEW_OPTIONS" in errors


def test_duplicate_options_are_refused() -> None:
    """Ambiguous even when the key is right, and the resulting evidence is noise attributed to a
    skill."""
    options = ["A log", "A log", "A cache"]
    errors = validate_item(item(options=options, answerKey=["A cache"]), CONTEXT)

    assert "QUALITY_DUPLICATE_OPTIONS" in errors


def test_an_empty_option_is_refused() -> None:
    options = ["A log", "   ", "A cache"]
    errors = validate_item(item(options=options, answerKey=["A log"]), CONTEXT)

    assert "STRUCTURAL_EMPTY_OPTION" in errors


def test_aggregate_options_are_refused() -> None:
    options = ["A log", "A cache", "All of the above"]
    errors = validate_item(item(options=options, answerKey=["A log"]), CONTEXT)

    assert "QUALITY_AGGREGATE_OPTION" in errors


def test_generator_narration_in_the_stem_is_refused() -> None:
    """Harmless to a reader, corrosive to trust, and a reliable signal the generation went
    sideways."""
    errors = validate_item(item(stem="As an AI, here is a question about topics."), CONTEXT)

    assert "QUALITY_GENERATOR_NARRATION" in errors


def test_narration_variants_are_refused() -> None:
    for stem in (
        "As a language model, I have written the following question.",
        "Here is a question about Kafka topics.",
        "I have generated the following item for you.",
    ):
        assert "QUALITY_GENERATOR_NARRATION" in validate_item(item(stem=stem), CONTEXT), stem


# -- curriculum policy -----------------------------------------------------------------------------


def test_an_item_about_a_different_skill_is_refused() -> None:
    """Evidence attributed to a skill the item does not measure is worse than no evidence: the
    mastery engine cannot tell the difference."""
    errors = validate_item(item(skillCode="KAFKA_STREAMS"), CONTEXT)

    assert "POLICY_SKILL_NOT_REQUESTED" in errors


def test_an_objective_that_was_not_offered_is_refused() -> None:
    errors = validate_item(item(objectiveCode="PARTITION_ORDERING"), CONTEXT)

    assert "POLICY_OBJECTIVE_NOT_OFFERED" in errors


def test_policy_defers_when_the_context_offered_no_objectives() -> None:
    """Spring holds the curriculum graph. Guessing here on partial information would refuse items
    the authority would have accepted, which is the failure direction nobody notices."""
    context = {"skillCode": "KAFKA_TOPIC"}

    assert validate_item(item(objectiveCode="ANYTHING_AT_ALL"), context) == []


def test_an_unsupported_difficulty_is_refused() -> None:
    assert "SCHEMA_BAD_DIFFICULTY" in validate_item(item(difficulty="EXPERT"), CONTEXT)


def test_a_missing_rationale_is_refused() -> None:
    """Review is the only thing between a generated item and a learner; an item with no stated
    reason cannot be reviewed quickly."""
    assert "SCHEMA_MISSING_RATIONALE" in validate_item(item(rationale="  "), CONTEXT)


def test_an_over_long_stem_is_refused() -> None:
    assert "SCHEMA_STEM_TOO_LONG" in validate_item(item(stem="x" * 1201), CONTEXT)


# -- the mirror is one-directional -----------------------------------------------------------------


def test_nothing_here_promotes() -> None:
    """``validate_item`` returns error codes, never a trust state.

    Structural rather than behavioural: a future change that made this function return "verified"
    would have to change its signature, which is visible in review -- unlike a boolean quietly
    changing meaning.
    """
    result = validate_item(item(), CONTEXT)

    assert isinstance(result, list)
    assert all(isinstance(code, str) for code in result)
