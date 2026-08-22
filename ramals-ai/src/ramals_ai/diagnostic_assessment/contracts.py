"""The MVP-2 DiagnosticAssessmentProposal, mirroring `diagnostic-proposal.v1.schema.json`.

Hand-written rather than generated, following the grounded-context contract: the MVP-2 contracts
live in `contracts/mvp2/` as JSON Schema, outside the OpenAPI envelope that produces
`contracts.generated`. `test_e01_proposal_conforms_to_the_frozen_contract_schema` validates a
produced proposal against that schema file, so the two cannot drift without a test failing.

The name is deliberately not `DiagnosticProposal`. MVP-1 already owns that word for a different
thing -- a proposal about *what to probe next* -- and its gate exists partly to refuse the verdicts
this contract requires. Two semantics, two names, no shared code.
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

MAX_DIAGNOSES = 64
MAX_EVIDENCE_REFS = 64
MAX_NEXT_SKILLS = 16

Identifier = Annotated[str, Field(min_length=1, max_length=64)]


class Classification(StrEnum):
    """What the agent believes the evidence shows about one skill.

    Advisory in every case. `INSUFFICIENT_EVIDENCE` is not a low score and not a failure -- it is
    the agent declining to classify, which under sparse evidence is the only honest answer.
    Deterministic mastery is computed by Spring from the same evidence and is never derived from
    this field.
    """

    STRONG = "STRONG"
    WEAK = "WEAK"
    INCONSISTENT = "INCONSISTENT"
    INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"


class Diagnosis(BaseModel):
    """One skill-level classification, with the evidence it rests on."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    skillCode: Annotated[str, Field(min_length=1, max_length=128)]  # noqa: N815
    classification: Classification
    reason: Annotated[str, Field(min_length=1, max_length=1000)]
    evidenceIds: Annotated[  # noqa: N815
        list[Identifier], Field(min_length=1, max_length=MAX_EVIDENCE_REFS)
    ]
    """At least one, always.

    A classification with no evidence reference is an opinion, and the schema refuses to express it.
    That the minimum is one rather than zero is the whole point of the contract: it makes an
    ungrounded diagnosis unrepresentable rather than merely discouraged.
    """

    @field_validator("evidenceIds")
    @classmethod
    def reject_duplicate_references(cls, value: list[str]) -> list[str]:
        """`uniqueItems` in the schema. Repeating one reference does not make a claim better
        supported, and a duplicate is usually a model padding a list to look thorough."""
        if len(set(value)) != len(value):
            raise ValueError("DIAGNOSIS_EVIDENCE_IDS_NOT_UNIQUE")
        return value


class DiagnosticAssessmentProposal(BaseModel):
    """A proposal-only reading of learner evidence.

    Nothing here is authoritative. It carries no mastery value, no progression decision and no
    instruction -- only classifications, the evidence behind them, and skills worth considering
    next.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    contractVersion: Literal["1.0"] = "1.0"  # noqa: N815
    proposalId: Identifier  # noqa: N815
    requestId: Identifier  # noqa: N815
    agentRunId: Identifier  # noqa: N815
    diagnoses: Annotated[list[Diagnosis], Field(min_length=1, max_length=MAX_DIAGNOSES)]
    recommendedNextSkills: Annotated[  # noqa: N815
        list[Annotated[str, Field(min_length=1, max_length=128)]],
        Field(max_length=MAX_NEXT_SKILLS),
    ] = []
    """Skills worth considering next. Recommendations, never a transition.

    Nothing consumes this as state: it reaches Spring inside a proposal payload, and the
    deterministic services decide what a learner does next from their own rules.
    """

    confidence: Annotated[float, Field(ge=0.0, le=1.0)]
    """The agent's own confidence, and never the only signal a gate uses.

    M2-ADR-007 is explicit that self-reported confidence is an additional policy check rather than a
    substitute for evidence validation -- a model is free to be confidently wrong.
    """

    @field_validator("recommendedNextSkills")
    @classmethod
    def reject_duplicate_skills(cls, value: list[str]) -> list[str]:
        """`uniqueItems` in the schema."""
        if len(set(value)) != len(value):
            raise ValueError("RECOMMENDED_SKILLS_NOT_UNIQUE")
        return value

    def to_contract(self) -> dict[str, object]:
        """The wire form, exactly as `diagnostic-proposal.v1.schema.json` describes it.

        `mode="json"` so enums and every nested value serialize to their JSON representation rather
        than to Python objects -- a `Classification` member would otherwise validate against the
        schema by accident in-process and fail once it crossed a boundary.
        """
        return self.model_dump(mode="json")
