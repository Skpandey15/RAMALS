"""Strict local model of ``assessment-evaluation-proposal.v1.schema.json``."""

from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

Identifier = Annotated[str, Field(min_length=1, max_length=64)]


class RubricDimensionEvaluation(BaseModel):
    """One proposed rubric score with the facts that support it."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    dimensionId: Identifier  # noqa: N815
    score: Annotated[float, Field(ge=0)]
    maxScore: Annotated[float, Field(gt=0)]  # noqa: N815
    reason: Annotated[str, Field(min_length=1, max_length=1000)]
    evidenceIds: Annotated[list[Identifier], Field(max_length=32)] = Field(  # noqa: N815
        default_factory=list
    )

    @field_validator("evidenceIds")
    @classmethod
    def reject_duplicate_evidence(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("EVALUATION_EVIDENCE_IDS_NOT_UNIQUE")
        return value


class AssessmentEvaluationProposal(BaseModel):
    """A proposal only; Spring's M2-T12 gate owns any authoritative effect."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    contractVersion: Literal["1.0"]  # noqa: N815
    proposalId: Identifier  # noqa: N815
    requestId: Identifier  # noqa: N815
    agentRunId: Identifier  # noqa: N815
    answerVersion: Identifier  # noqa: N815
    rubricVersion: Identifier  # noqa: N815
    dimensions: Annotated[list[RubricDimensionEvaluation], Field(min_length=1, max_length=32)]
    feedback: Annotated[str, Field(min_length=1, max_length=4000)]
    evidenceIds: Annotated[list[Identifier], Field(max_length=64)] = Field(  # noqa: N815
        default_factory=list
    )
    confidence: Annotated[float, Field(ge=0, le=1)]

    @field_validator("evidenceIds")
    @classmethod
    def reject_duplicate_feedback_evidence(cls, value: list[str]) -> list[str]:
        if len(set(value)) != len(value):
            raise ValueError("EVALUATION_EVIDENCE_IDS_NOT_UNIQUE")
        return value

    def to_contract(self) -> dict[str, object]:
        return self.model_dump(mode="json")
