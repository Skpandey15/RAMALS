package io.ramals.learningplatform.mcp.resources;

import io.ramals.learningplatform.assessment.LongitudinalEvidenceReport;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceReport.LongitudinalEvidenceFinding;
import java.util.UUID;

/**
 * MCP-2: maps the authoritative {@link LongitudinalEvidenceReport} (H7, M2-ADR-030) to the MCP wire
 * shape. Pure mapping -- reads every field verbatim, recomputes no baseline, no classification, and
 * no G3 confidence. Deliberately independent of {@code LongitudinalEvidenceResponse} (the REST
 * mapper).
 */
final class McpLongitudinalMapper {

  private McpLongitudinalMapper() {
  }

  static McpLongitudinalReport toMcp(LongitudinalEvidenceReport report) {
    return new McpLongitudinalReport(
        report.domainCode(),
        report.generatedAt(),
        report.findings().stream().map(McpLongitudinalMapper::toFinding).toList());
  }

  private static McpLongitudinalReport.Finding toFinding(LongitudinalEvidenceFinding finding) {
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
    McpLongitudinalReport.Baseline baseline = finding.baseline() == null ? null
        : new McpLongitudinalReport.Baseline(
            finding.baseline().evidenceStrength().name(), finding.baseline().computedAt());
    McpLongitudinalReport.LatestConfidence latestConfidence = finding.latestConfidence() == null ? null
        : new McpLongitudinalReport.LatestConfidence(
            finding.latestConfidence().evidenceStrength().name(), finding.latestConfidence().computedAt());

    return new McpLongitudinalReport.Finding(
        finding.misconceptionId().toString(),
        finding.name(),
        finding.description(),
        finding.targetType().name(),
        finding.targetId().toString(),
        objectiveContext,
        conceptContext,
        subConceptContext,
        finding.dataStatus().name(),
        baseline,
        finding.state() == null ? null : finding.state().name(),
        new McpDiagnosticReport.EvidenceSummary(
            finding.laterEvidence().supportingCount(), finding.laterEvidence().contradictoryCount(),
            finding.laterEvidence().inconclusiveCount()),
        finding.laterEvidenceObservationIds().stream().map(UUID::toString).toList(),
        latestConfidence,
        finding.confidenceCoverage() == null ? null : finding.confidenceCoverage().name(),
        finding.policyVersion());
  }
}
