package io.ramals.learningplatform.mcp.resources;

import io.ramals.learningplatform.assessment.DiagnosticReport;
import io.ramals.learningplatform.assessment.DiagnosticReport.MisconceptionFinding;
import java.util.UUID;

/**
 * MCP-2: maps the authoritative {@link DiagnosticReport} (H6, M2-ADR-029) to the MCP wire shape.
 * Pure mapping -- reads every field verbatim from the domain record, computes nothing, and never
 * reaches {@code DiagnosticConfidenceCalculatorV1} or any repository. Deliberately independent of
 * {@code DiagnosticReportResponse} (the REST mapper): a separate contract, even though the shape is
 * similar, per MCP-2's own DTO-design rule.
 */
final class McpDiagnosticMapper {

  private McpDiagnosticMapper() {
  }

  static McpDiagnosticReport toMcp(DiagnosticReport report) {
    return new McpDiagnosticReport(
        report.mode().name(),
        report.diagnosticDataStatus().name(),
        report.domainCode(),
        toStringOrNull(report.attemptId()),
        report.generatedAt(),
        report.misconceptionFindings().stream().map(McpDiagnosticMapper::toFinding).toList(),
        report.mastery().stream()
            .map(entry -> new McpMasterySkill(
                entry.skillCode(), entry.masteryScore(), entry.evidenceConfidence(),
                entry.masteryStatus(), entry.aggregateVersion()))
            .toList());
  }

  private static McpDiagnosticReport.Finding toFinding(MisconceptionFinding finding) {
    McpDiagnosticReport.ObjectiveContext objectiveContext = finding.objectiveContext() == null ? null
        : new McpDiagnosticReport.ObjectiveContext(
            finding.objectiveContext().objectiveId().toString(),
            finding.objectiveContext().objectiveCode(),
            finding.objectiveContext().description());
    McpDiagnosticReport.ConceptContext conceptContext = finding.conceptContext() == null ? null
        : new McpDiagnosticReport.ConceptContext(
            finding.conceptContext().conceptId().toString(), finding.conceptContext().name());
    McpDiagnosticReport.SubConceptContext subConceptContext = finding.subConceptContext() == null ? null
        : new McpDiagnosticReport.SubConceptContext(
            finding.subConceptContext().subConceptId().toString(), finding.subConceptContext().name());
    McpDiagnosticReport.Confidence confidence = finding.confidence() == null ? null
        : new McpDiagnosticReport.Confidence(
            finding.confidence().band().name(), finding.confidence().policyVersion(),
            finding.confidence().computedAt());

    return new McpDiagnosticReport.Finding(
        finding.misconceptionId().toString(),
        finding.name(),
        finding.description(),
        finding.targetType().name(),
        finding.targetId().toString(),
        objectiveContext,
        conceptContext,
        subConceptContext,
        new McpDiagnosticReport.EvidenceSummary(
            finding.evidenceSummary().supportingCount(), finding.evidenceSummary().contradictoryCount(),
            finding.evidenceSummary().inconclusiveCount()),
        finding.confidenceState().name(),
        confidence);
  }

  private static String toStringOrNull(UUID value) {
    return value == null ? null : value.toString();
  }
}
