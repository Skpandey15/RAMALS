"""Typed result contracts for the five Java MCP-2 read-only capabilities (M2-ADR-031).

Every model here is a direct, explicit mirror of the Java MCP wire DTOs
(``McpDiagnosticReport``/``McpLongitudinalReport``/``McpMasteryReport`` and their nested records) --
never an alias, never a stronger semantic claim. Field names are snake_case (Python convention)
with a ``Field(alias=...)`` carrying the exact Java/JSON name, so the wire identity is preserved
precisely while the Python attribute stays idiomatic; every enum-shaped string field keeps the Java
value
verbatim (``NOT_ASSESSED``, ``ASSESSED``, ``NO_BASELINE``, ``HAS_BASELINE``,
``LATER_CONTRADICTION_ONLY``, ...), never renamed to a probabilistic or causal claim.

These are the only shapes MCP-3 result data may take once it leaves ``client.py``. No untyped dict
carries a Java MCP result further into the graph.
"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field

_MODEL_CONFIG = ConfigDict(populate_by_name=True, frozen=True, extra="forbid")


class McpObjectiveContext(BaseModel):
    model_config = _MODEL_CONFIG

    objective_id: str = Field(alias="objectiveId")
    objective_code: str = Field(alias="objectiveCode")
    description: str


class McpConceptContext(BaseModel):
    model_config = _MODEL_CONFIG

    concept_id: str = Field(alias="conceptId")
    name: str


class McpSubConceptContext(BaseModel):
    model_config = _MODEL_CONFIG

    sub_concept_id: str = Field(alias="subConceptId")
    name: str


class McpEvidenceSummary(BaseModel):
    model_config = _MODEL_CONFIG

    supporting_count: int = Field(alias="supportingCount")
    contradictory_count: int = Field(alias="contradictoryCount")
    inconclusive_count: int = Field(alias="inconclusiveCount")


class McpMasterySkill(BaseModel):
    """One skill's latest mastery -- read-only, distinct from diagnostic confidence: this is
    ``masteryScore``/``evidenceConfidence``/``masteryStatus``, never a H6 ``confidenceState`` and
    never derived from one."""

    model_config = _MODEL_CONFIG

    skill_code: str = Field(alias="skillCode")
    mastery_score: Decimal = Field(alias="masteryScore")
    evidence_confidence: Decimal = Field(alias="evidenceConfidence")
    mastery_status: str = Field(alias="masteryStatus")
    aggregate_version: int = Field(alias="aggregateVersion")


# -- H6 (diagnostics.current-domain-report / diagnostics.attempt-report) ---------------------------


class McpDiagnosticConfidence(BaseModel):
    """``band``/``policyVersion``/``computedAt`` read back verbatim from Java's persisted G3
    snapshot -- never computed here, never treated as a probability or a diagnosis."""

    model_config = _MODEL_CONFIG

    band: str = Field(alias="band")
    """One of Java's DiagnosticConfidenceBand values (e.g. INSUFFICIENT_EVIDENCE, LOW, MODERATE,
    HIGH). HIGH means strong evidentiary corroboration under a governed policy -- never "likely root
    cause", "probability", "diagnosis", or "confirmed misconception"."""
    policy_version: str = Field(alias="policyVersion")
    computed_at: datetime = Field(alias="computedAt")


class McpDiagnosticFinding(BaseModel):
    model_config = _MODEL_CONFIG

    misconception_id: str = Field(alias="misconceptionId")
    name: str
    description: str
    target_type: str = Field(alias="targetType")
    target_id: str = Field(alias="targetId")
    objective_context: McpObjectiveContext | None = Field(default=None, alias="objectiveContext")
    concept_context: McpConceptContext | None = Field(default=None, alias="conceptContext")
    sub_concept_context: McpSubConceptContext | None = Field(
        default=None, alias="subConceptContext"
    )
    evidence_summary: McpEvidenceSummary = Field(alias="evidenceSummary")
    confidence_state: str = Field(alias="confidenceState")
    """``NOT_ASSESSED`` (no persisted G3 snapshot yet -- confidence below is null) or ``ASSESSED``
    (a persisted snapshot exists, including band INSUFFICIENT_EVIDENCE, which remains ASSESSED,
    never NOT_ASSESSED)."""
    confidence: McpDiagnosticConfidence | None = None


class McpDiagnosticReport(BaseModel):
    """H6 (M2-ADR-029): ``diagnostics.current-domain-report`` or ``diagnostics.attempt-report``."""

    model_config = _MODEL_CONFIG

    report_mode: str = Field(alias="reportMode")
    """``CURRENT_DOMAIN`` or ``ATTEMPT`` -- an ATTEMPT report's own ``mastery`` is always empty; it
    is exact-attempt diagnostic findings only, never "learner state as of this attempt"."""
    diagnostic_data_status: str = Field(alias="diagnosticDataStatus")
    """``NO_EVIDENCE`` or ``HAS_EVIDENCE`` -- whether any MISCONCEPTION_EVIDENCE_V1 evidence exists
    at all in this report's own scope. Distinct from each finding's own confidence_state."""
    domain_code: str | None = Field(default=None, alias="domainCode")
    attempt_id: str | None = Field(default=None, alias="attemptId")
    generated_at: datetime = Field(alias="generatedAt")
    misconception_findings: list[McpDiagnosticFinding] = Field(
        default_factory=list, alias="misconceptionFindings"
    )
    mastery: list[McpMasterySkill] = Field(default_factory=list)


# -- H7 (diagnostics.longitudinal-summary / diagnostics.misconception-longitudinal-detail) ---------


class McpLongitudinalBaseline(BaseModel):
    """The fixed evidentiary boundary -- worded "baseline evidence strength", never "as first
    established"."""

    model_config = _MODEL_CONFIG

    evidence_strength: str = Field(alias="evidenceStrength")
    computed_at: datetime = Field(alias="computedAt")


class McpLongitudinalLatestConfidence(BaseModel):
    """The latest persisted G3 snapshot -- worded "latest persisted overall evidence strength",
    never "current confidence"."""

    model_config = _MODEL_CONFIG

    evidence_strength: str = Field(alias="evidenceStrength")
    computed_at: datetime = Field(alias="computedAt")


class McpLongitudinalFinding(BaseModel):
    model_config = _MODEL_CONFIG

    misconception_id: str = Field(alias="misconceptionId")
    name: str
    description: str
    target_type: str = Field(alias="targetType")
    target_id: str = Field(alias="targetId")
    objective_context: McpObjectiveContext | None = Field(default=None, alias="objectiveContext")
    concept_context: McpConceptContext | None = Field(default=None, alias="conceptContext")
    sub_concept_context: McpSubConceptContext | None = Field(
        default=None, alias="subConceptContext"
    )
    data_status: str = Field(alias="dataStatus")
    """``NO_BASELINE`` or ``HAS_BASELINE`` -- never conflated with ``state``'s own
    ``NO_LATER_EVIDENCE``, which is a different, only-meaningful-once-a-baseline-exists
    classification."""
    baseline: McpLongitudinalBaseline | None = None
    state: str | None = None
    """One of NO_LATER_EVIDENCE, LATER_INCONCLUSIVE_ONLY, LATER_SUPPORT_ONLY,
    LATER_CONTRADICTION_ONLY, LATER_MIXED_EVIDENCE -- null iff data_status is NO_BASELINE. Never
    reinterpreted as "resolved", "corrected", or "recovered": this classifier draws no sequential or
    causal inference."""
    later_evidence: McpEvidenceSummary = Field(alias="laterEvidence")
    later_evidence_observation_ids: list[str] = Field(
        default_factory=list, alias="laterEvidenceObservationIds"
    )
    latest_confidence: McpLongitudinalLatestConfidence | None = Field(
        default=None, alias="latestConfidence"
    )
    confidence_coverage: str | None = Field(default=None, alias="confidenceCoverage")
    """``CURRENT`` or ``STALE_RELATIVE_TO_LATER_EVIDENCE`` -- null iff latest_confidence is null."""
    policy_version: str | None = Field(default=None, alias="policyVersion")


class McpLongitudinalReport(BaseModel):
    """H7 (M2-ADR-030): ``diagnostics.longitudinal-summary`` or
    ``diagnostics.misconception-longitudinal-detail``."""

    model_config = _MODEL_CONFIG

    domain_code: str | None = Field(default=None, alias="domainCode")
    generated_at: datetime = Field(alias="generatedAt")
    findings: list[McpLongitudinalFinding] = Field(default_factory=list)


# -- mastery.current ---------------------------------------------------------------------------


class McpMasteryReport(BaseModel):
    """``mastery.current`` -- the authoritative current mastery map, read back verbatim. No
    progression-eligibility field exists here; MCP-2 does not expose one, and this model does not
    invent one."""

    model_config = _MODEL_CONFIG

    domain_code: str = Field(alias="domainCode")
    version_code: str = Field(alias="versionCode")
    skills: list[McpMasterySkill] = Field(default_factory=list)
