"""Fail-closed validation for MVP-2 assessment evaluation proposals."""

from __future__ import annotations

import json
import re
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from pydantic import ValidationError

from ramals_ai.assessment_evaluation.contracts import AssessmentEvaluationProposal
from ramals_ai.contracts.generated import (
    AssessmentEvaluationContext,
    AssessmentEvaluationRequest,
    InteractionClass,
)
from ramals_ai.grounding.contracts import ContextAuthority, GroundedContext, SourceType

MAX_REPORTED_ERRORS = 16
_AUTHORITY_CLAIM = re.compile(
    r"\b(final score|official score|mastery (?:was|is)|progression (?:was|is)|"
    r"has been (?:recorded|committed|saved)|(?:pass|fail)(?:ed)? the assessment)\b",
    re.IGNORECASE,
)


def require_evaluation_request(
    request: AssessmentEvaluationRequest, context: GroundedContext
) -> None:
    """Refuse a wrong execution class and bind the request to its grounded facts."""
    if request.constraints.interactionClass is not InteractionClass.ASSESSMENT_PROPOSAL:
        raise ValueError("EVALUATION_INTERACTION_CLASS_INVALID")
    require_evaluation_grounding(request.evaluationContext, context)


def require_evaluation_grounding(
    evaluation: AssessmentEvaluationContext, context: GroundedContext
) -> None:
    """Bind the typed answer/rubric package to authoritative assessment facts."""
    now = datetime.now(UTC)
    context.require_grounding({SourceType.ASSESSMENT}, now=now)
    assessment_items = [
        item
        for item in context.items
        if item.sourceType is SourceType.ASSESSMENT
        and item.authority is ContextAuthority.AUTHORITATIVE_FACT
        and (item.expiresAt is None or item.expiresAt > now)
    ]
    assessment_ids = {item.evidenceId for item in assessment_items}
    required = {
        evaluation.answerEvidenceId,
        *(dimension.evidenceId for dimension in evaluation.rubricDimensions),
    }
    if not required <= assessment_ids:
        raise ValueError("EVALUATION_CONTEXT_EVIDENCE_NOT_GROUNDED")
    if not any(
        item.evidenceId == evaluation.answerEvidenceId
        and item.sourceVersion == evaluation.answerVersion
        and item.factType == "ANSWER_VERSION"
        and item.value == evaluation.answerVersion
        for item in assessment_items
    ):
        raise ValueError("EVALUATION_ANSWER_VERSION_NOT_GROUNDED")
    for dimension in evaluation.rubricDimensions:
        if not any(
            item.evidenceId == dimension.evidenceId
            and item.sourceVersion == evaluation.rubricVersion
            and item.factType == "RUBRIC_DIMENSION"
            and item.value == dimension.dimensionId
            for item in assessment_items
        ):
            raise ValueError("EVALUATION_RUBRIC_VERSION_NOT_GROUNDED")
    dimension_ids = [dimension.dimensionId for dimension in evaluation.rubricDimensions]
    if len(set(dimension_ids)) != len(dimension_ids):
        raise ValueError("EVALUATION_CONTEXT_DIMENSIONS_NOT_UNIQUE")


def validate(
    raw: str,
    evaluation: AssessmentEvaluationContext,
    context: GroundedContext,
) -> list[str]:
    """Validate shape, rubric bounds, evidence support, and proposal-only semantics."""
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError, TypeError:
        return ["SCHEMA_NOT_JSON"]
    if not isinstance(parsed, dict):
        return ["SCHEMA_NOT_OBJECT"]

    try:
        proposal = AssessmentEvaluationProposal.model_validate(
            _with_runtime_values(parsed, evaluation)
        )
    except ValidationError as invalid:
        return _schema_codes(invalid)

    errors: list[str] = []
    configured = {dimension.dimensionId: dimension for dimension in evaluation.rubricDimensions}
    proposed_identifiers = [dimension.dimensionId for dimension in proposal.dimensions]
    if len(set(proposed_identifiers)) != len(proposed_identifiers):
        errors.append("EVALUATION_DIMENSION_IDS_NOT_UNIQUE")
    proposed = {dimension.dimensionId: dimension for dimension in proposal.dimensions}
    if set(proposed) != set(configured):
        errors.append("EVALUATION_RUBRIC_DIMENSIONS_MISMATCH")

    permitted = _authoritative_evidence_ids(context)
    for identifier, dimension in proposed.items():
        expected = configured.get(identifier)
        if expected is None:
            continue
        if Decimal(str(dimension.maxScore)) != Decimal(str(expected.maxScore)):
            _append(errors, "EVALUATION_MAX_SCORE_MISMATCH")
        if Decimal(str(dimension.score)) > Decimal(str(expected.maxScore)):
            _append(errors, "EVALUATION_SCORE_OUT_OF_RANGE")
        cited = set(dimension.evidenceIds)
        if not cited <= permitted:
            _append(errors, "EVALUATION_EVIDENCE_NOT_IN_CONTEXT")
        if not {evaluation.answerEvidenceId, expected.evidenceId} <= cited:
            _append(errors, "EVALUATION_DIMENSION_EVIDENCE_INCOMPLETE")
        if _AUTHORITY_CLAIM.search(dimension.reason):
            _append(errors, "EVALUATION_AUTHORITY_CLAIM")

    feedback_evidence = set(proposal.evidenceIds)
    if not feedback_evidence <= permitted:
        _append(errors, "EVALUATION_EVIDENCE_NOT_IN_CONTEXT")
    if evaluation.answerEvidenceId not in feedback_evidence:
        _append(errors, "EVALUATION_FEEDBACK_EVIDENCE_INCOMPLETE")
    if _AUTHORITY_CLAIM.search(proposal.feedback):
        _append(errors, "EVALUATION_AUTHORITY_CLAIM")
    return errors[:MAX_REPORTED_ERRORS]


def _with_runtime_values(
    parsed: dict[str, Any], evaluation: AssessmentEvaluationContext
) -> dict[str, Any]:
    return {
        "contractVersion": "1.0",
        "proposalId": "pending",
        "requestId": "pending",
        "agentRunId": "pending",
        "answerVersion": evaluation.answerVersion,
        "rubricVersion": evaluation.rubricVersion,
        **{key: value for key, value in parsed.items() if key not in _RUNTIME_OWNED},
    }


_RUNTIME_OWNED = frozenset(
    {
        "contractVersion",
        "proposalId",
        "requestId",
        "agentRunId",
        "answerVersion",
        "rubricVersion",
    }
)


def _authoritative_evidence_ids(context: GroundedContext) -> frozenset[str]:
    now = datetime.now(UTC)
    return frozenset(
        item.evidenceId
        for item in context.items
        if item.authority is ContextAuthority.AUTHORITATIVE_FACT
        and (item.expiresAt is None or item.expiresAt > now)
    )


def _schema_codes(invalid: ValidationError) -> list[str]:
    codes: list[str] = []
    for error in invalid.errors():
        location = [part for part in error["loc"] if isinstance(part, str)]
        field = location[0] if location else "PROPOSAL"
        _append(codes, f"SCHEMA_INVALID_{_screaming(field)}")
    return codes[:MAX_REPORTED_ERRORS]


def _append(codes: list[str], code: str) -> None:
    if code not in codes:
        codes.append(code)


def _screaming(field: str) -> str:
    return "".join(character for character in field if character.isalnum()).upper()[:40]
