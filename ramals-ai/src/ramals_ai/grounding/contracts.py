"""Fail-closed consumer for Spring-built grounded context packages (M2-ADR-006)."""

from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

MAX_ITEMS = 64
MAX_SERIALIZED_BYTES = 65_536
SENSITIVE_FACT_TOKENS = frozenset(
    {
        "EMAIL",
        "FULL_NAME",
        "DISPLAY_NAME",
        "PHONE",
        "POSTAL_ADDRESS",
        "AUTH_TOKEN",
        "SECRET",
        "PASSWORD",
        "RAW_PROMPT",
    }
)


class SourceType(StrEnum):
    LEARNER_EVIDENCE = "LEARNER_EVIDENCE"
    MASTERY = "MASTERY"
    SKILL_GRAPH = "SKILL_GRAPH"
    ASSESSMENT = "ASSESSMENT"
    APPROVED_CONTENT = "APPROVED_CONTENT"
    CURRICULUM_POLICY = "CURRICULUM_POLICY"
    DOMAIN_POLICY = "DOMAIN_POLICY"


class ContextAuthority(StrEnum):
    AUTHORITATIVE_FACT = "AUTHORITATIVE_FACT"
    MODEL_GENERATED_SUMMARY = "MODEL_GENERATED_SUMMARY"


ScalarValue = str | int | float | bool


class GroundedContextItem(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    evidenceId: Annotated[str, Field(min_length=1, max_length=64)]  # noqa: N815
    sourceType: SourceType  # noqa: N815
    sourceVersion: Annotated[str, Field(min_length=1, max_length=64)]  # noqa: N815
    authority: ContextAuthority
    factType: Annotated[str, Field(min_length=1, max_length=64)]  # noqa: N815
    value: ScalarValue
    observedAt: datetime  # noqa: N815
    expiresAt: datetime | None = None  # noqa: N815

    @model_validator(mode="after")
    def reject_sensitive_or_unbounded_fact(self) -> GroundedContextItem:
        normalized = self.factType.upper()
        if any(token in normalized for token in SENSITIVE_FACT_TOKENS):
            raise ValueError("GROUNDING_SENSITIVE_FIELD_REJECTED")
        if isinstance(self.value, str) and len(self.value) > 2_048:
            raise ValueError("GROUNDING_VALUE_LIMIT_EXCEEDED")
        return self


class GroundedContext(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    contractVersion: Literal["1.0"]  # noqa: N815
    contextId: Annotated[str, Field(min_length=1, max_length=64)]  # noqa: N815
    learnerRef: Annotated[str, Field(min_length=1, max_length=64)]  # noqa: N815
    asOf: datetime  # noqa: N815
    expiresAt: datetime  # noqa: N815
    retrievalPolicyVersion: Annotated[str, Field(min_length=1, max_length=64)]  # noqa: N815
    items: Annotated[list[GroundedContextItem], Field(max_length=MAX_ITEMS)]

    @model_validator(mode="after")
    def validate_bounds_and_freshness(self) -> GroundedContext:
        if self.expiresAt <= self.asOf:
            raise ValueError("GROUNDING_FRESHNESS_INVALID")
        if len(self.model_dump_json().encode("utf-8")) > MAX_SERIALIZED_BYTES:
            raise ValueError("GROUNDING_SIZE_LIMIT_EXCEEDED")
        return self

    def require_grounding(
        self, required_sources: set[SourceType], *, now: datetime | None = None
    ) -> GroundedContext:
        current = now or datetime.now(UTC)
        if self.expiresAt <= current:
            raise ValueError("GROUNDING_STALE")
        authoritative = {
            item.sourceType
            for item in self.items
            if item.authority is ContextAuthority.AUTHORITATIVE_FACT
            and (item.expiresAt is None or item.expiresAt > current)
        }
        if not required_sources <= authoritative:
            raise ValueError("GROUNDING_REQUIRED_SOURCE_MISSING")
        return self
