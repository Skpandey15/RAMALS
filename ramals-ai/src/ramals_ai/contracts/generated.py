# GENERATED FROM contracts/ai-internal.openapi.yaml -- DO NOT EDIT BY HAND.
# Regenerate with: python scripts/ci/generate-contract-models.py
# M1-ADR-002: Python models are generated; Java records are hand-written and validated against the
# same contract by the golden fixtures in contracts/golden/.

from __future__ import annotations

from datetime import date
from enum import StrEnum
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, RootModel


class ContractVersion(RootModel[str]):
    root: Annotated[
        str,
        Field(
            description='Envelope contract version. A breaking change ships a new major version.',
            examples=['1.0'],
            pattern='^\\d+\\.\\d+$',
        ),
    ]


class TrustLevel(StrEnum):
    NON_AUTHORITATIVE = 'NON_AUTHORITATIVE'
    UNVERIFIED = 'UNVERIFIED'
    VERIFIED_CONTENT = 'VERIFIED_CONTENT'
    FORMATIVE_ONLY = 'FORMATIVE_ONLY'
    REJECTED = 'REJECTED'
    APPROVAL_REQUIRED = 'APPROVAL_REQUIRED'


class AgentType(StrEnum):
    TUTOR = 'TUTOR'
    DIAGNOSTIC = 'DIAGNOSTIC'
    ASSESSMENT = 'ASSESSMENT'
    ADAPTATION = 'ADAPTATION'


class InteractionClass(StrEnum):
    FAST = 'FAST'
    INTERACTIVE_AI = 'INTERACTIVE_AI'
    ASSESSMENT_PROPOSAL = 'ASSESSMENT_PROPOSAL'
    LIMITED_DURABLE = 'LIMITED_DURABLE'


class LearnerRef(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    learnerRef: Annotated[str, Field(max_length=128, min_length=1)]
    locale: Annotated[str | None, Field(examples=['en-IN'], max_length=16)] = None


class Prerequisite(RootModel[str]):
    root: Annotated[str, Field(max_length=96)]


class DomainType(StrEnum):
    TECHNOLOGY = 'TECHNOLOGY'
    ACADEMIC = 'ACADEMIC'
    PROFESSIONAL = 'PROFESSIONAL'


class GoalType(StrEnum):
    LEARNING_DOMAIN = 'LEARNING_DOMAIN'
    ACADEMIC_MASTERY = 'ACADEMIC_MASTERY'
    DEGREE_COMPETENCY = 'DEGREE_COMPETENCY'
    CAREER_ROLE = 'CAREER_ROLE'
    CAREER_TRANSITION = 'CAREER_TRANSITION'


class DecimalString(RootModel[str]):
    root: Annotated[
        str,
        Field(
            description='Fixed-scale decimal carried as a string. The deterministic engines use BigDecimal at a canonical scale; a JSON number would invite a float round-trip and break reproducibility.\n',
            examples=['0.7200'],
            pattern='^\\d\\.\\d{4}$',
        ),
    ]


class AllowedTool(RootModel[str]):
    root: Annotated[str, Field(max_length=64)]


class Constraints(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    interactionClass: InteractionClass
    deadlineMs: Annotated[
        int,
        Field(
            description='Remaining budget when the call was made, not a per-hop timeout. The callee must not exceed it and must not begin work it cannot finish within it (M1-ADR-001).\n',
            ge=1,
            le=15000,
        ),
    ]
    maxOutputTokens: Annotated[int | None, Field(ge=1, le=4000)] = None
    allowedTools: Annotated[
        list[AllowedTool] | None,
        Field(description='Capability allowlist. An empty array means no tools.', max_length=16),
    ] = None
    policyVersion: Annotated[str | None, Field(max_length=64)] = None


class Usage(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    inputTokens: Annotated[int | None, Field(ge=0)] = None
    cachedInputTokens: Annotated[int | None, Field(ge=0)] = None
    outputTokens: Annotated[int | None, Field(ge=0)] = None
    estimatedCostUsd: Annotated[str | None, Field(pattern='^\\d+\\.\\d{1,6}$')] = None
    latencyMs: Annotated[int | None, Field(ge=0)] = None


class Validation(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    schemaValid: bool
    semanticValid: bool | None = None
    repairAttempts: Annotated[int | None, Field(ge=0, le=2)] = None


class ReasonCode(RootModel[str]):
    root: Annotated[str, Field(max_length=64)]


class AIProposalEnvelope(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    contractVersion: ContractVersion
    proposalId: Annotated[
        str,
        Field(
            description='Identity of this proposal. Referenced by DecisionRecord when it influences one.',
            max_length=64,
        ),
    ]
    agentType: AgentType
    agentVersion: Annotated[str, Field(max_length=64)]
    agentRunId: Annotated[
        str | None,
        Field(
            description="The orchestrated agent execution that produced this proposal (Observability HLD §9). Distinct from proposalId, requestId and interactionId: a retried request produces several runs, and one interaction may involve several agents. Carried on the wire so the deterministic core's log lines for a decision can name the run that proposed it -- otherwise the correlation chain ends at the plane boundary, which is where a support pivot most often needs to cross. Optional, so a plane that predates the field still validates.\n",
            max_length=64,
        ),
    ] = None
    promptTemplateId: Annotated[
        str | None,
        Field(
            description="Which prompt produced this proposal (M1-ADR-011). Two of the assessment agent's prompts share a version, so promptVersion alone does not identify one.\n",
            max_length=64,
        ),
    ] = None
    promptVersion: Annotated[str | None, Field(max_length=64)] = None
    modelRoute: Annotated[str, Field(max_length=64)]
    trustLevel: TrustLevel
    confidence: Annotated[
        DecimalString | None,
        Field(
            description="The agent's self-reported confidence. Never a basis for authority: Spring's deterministic policy decides, and a high value here changes nothing.\n"
        ),
    ] = None
    reasonCodes: Annotated[list[ReasonCode] | None, Field(max_length=16)] = None
    proposal: Annotated[
        dict[str, Any],
        Field(
            description="Agent-specific payload. Deliberately open at v1.0 so each agent can define its own shape in its own task without a breaking contract change; each agent's schema is pinned by its own golden fixtures.\n"
        ),
    ]
    validation: Validation | None = None
    usage: Usage | None = None


class Capabilities(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    contractVersion: ContractVersion
    service: Annotated[str, Field(max_length=64)]
    version: Annotated[str, Field(max_length=32)]
    environment: Annotated[str, Field(max_length=32)]
    aiEnabled: bool
    modelRoute: Annotated[str, Field(max_length=64)]
    routeTableVersion: Annotated[
        str | None,
        Field(
            description='The route configuration this process is actually serving. Equal to the shipped table version when nothing is pinned, and extended with the pins when a prompt or model has been rolled back (M1-ADR-008, M1-ADR-011). Reported so a rollback that did not take effect is distinguishable from one that did: without it, the two look identical from outside the process. Optional, so a deployment that predates the field still validates.\n',
            max_length=256,
        ),
    ] = None
    agents: Annotated[list[AgentType], Field(max_length=8)]
    authority: Annotated[
        Literal['NON_AUTHORITATIVE'],
        Field(
            description='Stated on the wire so no caller can mistake this service for a decision-maker.'
        ),
    ]


class Problem(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    type: Annotated[str, Field(max_length=256)]
    title: Annotated[str, Field(max_length=256)]
    status: Annotated[int, Field(ge=100, le=599)]
    code: Annotated[str, Field(max_length=64)]
    detail: Annotated[str | None, Field(max_length=1024)] = None
    interactionId: Annotated[str | None, Field(max_length=64)] = None
    traceId: Annotated[str | None, Field(max_length=64)] = None


class LearningContext(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    skillCode: Annotated[str, Field(max_length=96, min_length=1)]
    masteryScore: DecimalString | None = None
    evidenceConfidence: DecimalString | None = None
    masteryStatus: Annotated[str | None, Field(examples=['NEEDS_PRACTICE'], max_length=32)] = None
    prerequisites: Annotated[list[Prerequisite] | None, Field(max_length=32)] = None


class DomainContext(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    domainCode: Annotated[str, Field(examples=['KAFKA'], max_length=64, min_length=1)]
    domainType: DomainType
    curriculumVersion: Annotated[str | None, Field(examples=['v1'], max_length=64)] = None


class LearningGoalContext(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    goalType: GoalType
    goalCode: Annotated[str, Field(examples=['KAFKA'], max_length=96, min_length=1)]
    targetDate: date | None = None
    goalVersion: Annotated[str | None, Field(max_length=64)] = None


class AIRequestEnvelope(BaseModel):
    model_config = ConfigDict(
        extra='forbid',
    )
    contractVersion: ContractVersion
    interactionId: Annotated[
        str, Field(description='Logical learner action. Stable across safe retries.', max_length=64)
    ]
    requestId: Annotated[
        str, Field(description='One transport attempt. New per retry.', max_length=64)
    ]
    learner: LearnerRef
    learningContext: LearningContext | None = None
    domainContext: DomainContext | None = None
    learningGoalContext: LearningGoalContext | None = None
    constraints: Constraints
    requestedCapability: Annotated[str | None, Field(max_length=64)] = None
