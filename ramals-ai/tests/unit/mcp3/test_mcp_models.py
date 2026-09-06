"""MCP-3 review requirements #22-25: the five Pydantic result contracts deserialize representative
Java MCP payloads, and H6/H7 enum semantics -- plus mastery's own distinctness from diagnostic
confidence -- survive unchanged."""

from __future__ import annotations

from decimal import Decimal
from typing import Any

from ramals_ai.mcp.models import (
    McpDiagnosticReport,
    McpLongitudinalReport,
    McpMasteryReport,
)

# Representative payloads shaped exactly like McpDiagnosticMapper/McpLongitudinalMapper/
# McpMasteryToolsConfig's own Jackson serialization (M2-ADR-031, MCP-2) -- camelCase keys, ISO-8601
# timestamps.

_CURRENT_DOMAIN_REPORT_PAYLOAD: dict[str, Any] = {
    "reportMode": "CURRENT_DOMAIN",
    "diagnosticDataStatus": "HAS_EVIDENCE",
    "domainCode": "KAFKA",
    "attemptId": None,
    "generatedAt": "2026-09-06T00:00:00Z",
    "misconceptionFindings": [
        {
            "misconceptionId": "0199-fake-misconception-id",
            "name": "Confuses partition with replica",
            "description": "A learner confuses a partition with a replica.",
            "targetType": "LEARNING_OBJECTIVE",
            "targetId": "0199-fake-objective-id",
            "objectiveContext": {
                "objectiveId": "0199-fake-objective-id",
                "objectiveCode": "KAFKA_PARTITIONS",
                "description": "Understand Kafka partitions.",
            },
            "conceptContext": None,
            "subConceptContext": None,
            "evidenceSummary": {
                "supportingCount": 2,
                "contradictoryCount": 0,
                "inconclusiveCount": 1,
            },
            "confidenceState": "ASSESSED",
            "confidence": {
                "band": "HIGH",
                "policyVersion": "DIAGNOSTIC_CONFIDENCE_V1",
                "computedAt": "2026-09-05T12:00:00Z",
            },
        },
        {
            "misconceptionId": "0199-fake-misconception-id-2",
            "name": "Confuses offset with timestamp",
            "description": "...",
            "targetType": "CONCEPT",
            "targetId": "0199-fake-concept-id",
            "objectiveContext": {
                "objectiveId": "0199-fake-objective-id",
                "objectiveCode": "KAFKA_PARTITIONS",
                "description": "Understand Kafka partitions.",
            },
            "conceptContext": {"conceptId": "0199-fake-concept-id", "name": "Offsets"},
            "subConceptContext": None,
            "evidenceSummary": {
                "supportingCount": 1,
                "contradictoryCount": 0,
                "inconclusiveCount": 0,
            },
            "confidenceState": "NOT_ASSESSED",
            "confidence": None,
        },
    ],
    "mastery": [
        {
            "skillCode": "KAFKA_PARTITIONS",
            "masteryScore": "0.750000",
            "evidenceConfidence": "0.600000",
            "masteryStatus": "PROFICIENT",
            "aggregateVersion": 3,
        }
    ],
}

_ATTEMPT_REPORT_PAYLOAD: dict[str, Any] = {
    "reportMode": "ATTEMPT",
    "diagnosticDataStatus": "NO_EVIDENCE",
    "domainCode": None,
    "attemptId": "0199-fake-attempt-id",
    "generatedAt": "2026-09-06T00:00:00Z",
    "misconceptionFindings": [],
    "mastery": [],
}

_LONGITUDINAL_SUMMARY_PAYLOAD: dict[str, Any] = {
    "domainCode": "KAFKA",
    "generatedAt": "2026-09-06T00:00:00Z",
    "findings": [
        {
            "misconceptionId": "0199-fake-misconception-id",
            "name": "Confuses partition with replica",
            "description": "...",
            "targetType": "LEARNING_OBJECTIVE",
            "targetId": "0199-fake-objective-id",
            "objectiveContext": None,
            "conceptContext": None,
            "subConceptContext": None,
            "dataStatus": "HAS_BASELINE",
            "baseline": {"evidenceStrength": "LOW", "computedAt": "2026-09-01T00:00:00Z"},
            "state": "LATER_MIXED_EVIDENCE",
            "laterEvidence": {
                "supportingCount": 1,
                "contradictoryCount": 1,
                "inconclusiveCount": 0,
            },
            "laterEvidenceObservationIds": ["0199-fake-evidence-id-1", "0199-fake-evidence-id-2"],
            "latestConfidence": {
                "evidenceStrength": "MODERATE",
                "computedAt": "2026-09-05T00:00:00Z",
            },
            "confidenceCoverage": "CURRENT",
            "policyVersion": "LONGITUDINAL_EVIDENCE_V1",
        }
    ],
}

_MISCONCEPTION_DETAIL_NO_BASELINE_PAYLOAD: dict[str, Any] = {
    "domainCode": None,
    "generatedAt": "2026-09-06T00:00:00Z",
    "findings": [
        {
            "misconceptionId": "0199-fake-misconception-id",
            "name": "Confuses partition with replica",
            "description": "...",
            "targetType": "LEARNING_OBJECTIVE",
            "targetId": "0199-fake-objective-id",
            "objectiveContext": None,
            "conceptContext": None,
            "subConceptContext": None,
            "dataStatus": "NO_BASELINE",
            "baseline": None,
            "state": None,
            "laterEvidence": {
                "supportingCount": 0,
                "contradictoryCount": 0,
                "inconclusiveCount": 0,
            },
            "laterEvidenceObservationIds": [],
            "latestConfidence": None,
            "confidenceCoverage": None,
            "policyVersion": None,
        }
    ],
}

_MASTERY_CURRENT_PAYLOAD: dict[str, Any] = {
    "domainCode": "KAFKA",
    "versionCode": "v1",
    "skills": [
        {
            "skillCode": "KAFKA_PARTITIONS",
            "masteryScore": "0.750000",
            "evidenceConfidence": "0.600000",
            "masteryStatus": "PROFICIENT",
            "aggregateVersion": 3,
        }
    ],
}


def test_current_domain_report_deserializes() -> None:
    """Test #22."""
    report = McpDiagnosticReport.model_validate(_CURRENT_DOMAIN_REPORT_PAYLOAD)
    assert report.report_mode == "CURRENT_DOMAIN"
    assert report.mastery[0].mastery_score == Decimal("0.750000")


def test_attempt_report_deserializes_and_carries_no_mastery() -> None:
    """Test #22, plus the attempt-report-never-mastery semantic (M2-ADR-029)."""
    report = McpDiagnosticReport.model_validate(_ATTEMPT_REPORT_PAYLOAD)
    assert report.report_mode == "ATTEMPT"
    assert report.mastery == []


def test_longitudinal_summary_deserializes() -> None:
    """Test #22."""
    report = McpLongitudinalReport.model_validate(_LONGITUDINAL_SUMMARY_PAYLOAD)
    assert report.findings[0].data_status == "HAS_BASELINE"


def test_misconception_detail_no_baseline_deserializes() -> None:
    """Test #22."""
    report = McpLongitudinalReport.model_validate(_MISCONCEPTION_DETAIL_NO_BASELINE_PAYLOAD)
    assert report.findings[0].data_status == "NO_BASELINE"
    assert report.findings[0].state is None
    assert report.findings[0].confidence_coverage is None


def test_mastery_current_deserializes() -> None:
    """Test #22."""
    report = McpMasteryReport.model_validate(_MASTERY_CURRENT_PAYLOAD)
    assert report.skills[0].skill_code == "KAFKA_PARTITIONS"


# -- test #23: every H6 enum value/semantic preserved exactly --------------------------------------


def test_h6_not_assessed_and_assessed_are_preserved_distinctly() -> None:
    report = McpDiagnosticReport.model_validate(_CURRENT_DOMAIN_REPORT_PAYLOAD)
    states = {finding.confidence_state for finding in report.misconception_findings}
    assert states == {"ASSESSED", "NOT_ASSESSED"}


def test_h6_insufficient_evidence_band_is_preserved_verbatim_never_renamed() -> None:
    first_finding: dict[str, Any] = _CURRENT_DOMAIN_REPORT_PAYLOAD["misconceptionFindings"][0]
    payload: dict[str, Any] = {
        **_CURRENT_DOMAIN_REPORT_PAYLOAD,
        "misconceptionFindings": [
            {
                **first_finding,
                "confidence": {
                    "band": "INSUFFICIENT_EVIDENCE",
                    "policyVersion": "DIAGNOSTIC_CONFIDENCE_V1",
                    "computedAt": "2026-09-05T12:00:00Z",
                },
            }
        ],
    }
    report = McpDiagnosticReport.model_validate(payload)
    finding = report.misconception_findings[0]
    # ASSESSED with band INSUFFICIENT_EVIDENCE, never coerced to NOT_ASSESSED and never renamed to
    # anything stronger (e.g. "diagnosis", "confirmed").
    assert finding.confidence_state == "ASSESSED"
    assert finding.confidence is not None
    assert finding.confidence.band == "INSUFFICIENT_EVIDENCE"


# -- test #24: every H7 enum value/semantic preserved exactly


def test_h7_no_baseline_and_has_baseline_are_preserved_distinctly() -> None:
    no_baseline = McpLongitudinalReport.model_validate(_MISCONCEPTION_DETAIL_NO_BASELINE_PAYLOAD)
    has_baseline = McpLongitudinalReport.model_validate(_LONGITUDINAL_SUMMARY_PAYLOAD)
    assert no_baseline.findings[0].data_status == "NO_BASELINE"
    assert has_baseline.findings[0].data_status == "HAS_BASELINE"


def test_h7_later_mixed_evidence_state_is_preserved_verbatim_never_renamed() -> None:
    report = McpLongitudinalReport.model_validate(_LONGITUDINAL_SUMMARY_PAYLOAD)
    # Never "recurrence" or "regression" -- the classifier draws no sequential inference.
    assert report.findings[0].state == "LATER_MIXED_EVIDENCE"


def test_h7_confidence_coverage_values_are_preserved_verbatim() -> None:
    report = McpLongitudinalReport.model_validate(_LONGITUDINAL_SUMMARY_PAYLOAD)
    assert report.findings[0].confidence_coverage == "CURRENT"

    first_finding: dict[str, Any] = _LONGITUDINAL_SUMMARY_PAYLOAD["findings"][0]
    stale_payload: dict[str, Any] = {
        **_LONGITUDINAL_SUMMARY_PAYLOAD,
        "findings": [{**first_finding, "confidenceCoverage": "STALE_RELATIVE_TO_LATER_EVIDENCE"}],
    }
    stale_report = McpLongitudinalReport.model_validate(stale_payload)
    assert stale_report.findings[0].confidence_coverage == "STALE_RELATIVE_TO_LATER_EVIDENCE"


# -- test #25: mastery is read-only and distinct from diagnostic confidence


def test_mastery_skill_has_no_diagnostic_confidence_field() -> None:
    """Mastery's own model carries masteryScore/evidenceConfidence/masteryStatus only -- no
    confidenceState, no band, no field that could be mistaken for H6/H7 diagnostic confidence."""
    fields = set(
        type(
            McpMasteryReport.model_validate(_MASTERY_CURRENT_PAYLOAD).skills[0]
        ).model_fields.keys()
    )
    assert fields == {
        "skill_code",
        "mastery_score",
        "evidence_confidence",
        "mastery_status",
        "aggregate_version",
    }
    assert "confidence_state" not in fields
    assert "band" not in fields


def test_all_result_models_are_frozen_and_reject_extra_fields() -> None:
    """No model here can be mutated after construction (read-only, structurally), and none silently
    accepts an unexpected field -- an unexpected key from Java would surface as a validation error,
    not a quietly-dropped extra."""
    report = McpMasteryReport.model_validate(_MASTERY_CURRENT_PAYLOAD)
    import pydantic

    try:
        report.domain_code = "CBSE"
        raised = False
    except pydantic.ValidationError:
        raised = True
    assert raised
