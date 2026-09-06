package io.ramals.learningplatform.mcp.resources;

import java.time.Instant;
import java.util.List;

/**
 * MCP-2 wire shape for H6's {@code DiagnosticReport} (M2-ADR-029) -- a separate contract from {@code
 * DiagnosticReportResponse} (the REST DTO), even though the two look similar, and mapped directly
 * from the authoritative domain record by {@link McpDiagnosticMapper}, never from the REST DTO. Always
 * the learner view: no admin provenance field exists on this type at all (mirrors {@code
 * DiagnosticReportResponse.fromLearnerView}, which zeroes it; this shape has nowhere to put it in the
 * first place).
 *
 * <p>{@code mastery} is populated only for {@code reportMode == "CURRENT_DOMAIN"}; always empty for
 * {@code "ATTEMPT"} -- an Attempt Diagnostic Report is exact-attempt diagnostic findings only, never
 * combined with today's mastery state (M2-ADR-029).
 */
public record McpDiagnosticReport(
    String reportMode,
    String diagnosticDataStatus,
    String domainCode,
    String attemptId,
    Instant generatedAt,
    List<Finding> misconceptionFindings,
    List<McpMasterySkill> mastery) {

  /** One misconception's finding -- no confidence-snapshot id, attempt id, or evidence-observation
   * id anywhere: that provenance is admin-only in the REST contract and has no equivalent here at
   * all, since every MCP-2 capability is learner-scoped. */
  public record Finding(
      String misconceptionId,
      String name,
      String description,
      String targetType,
      String targetId,
      ObjectiveContext objectiveContext,
      ConceptContext conceptContext,
      SubConceptContext subConceptContext,
      EvidenceSummary evidenceSummary,
      String confidenceState,
      Confidence confidence) {
  }

  public record ObjectiveContext(String objectiveId, String objectiveCode, String description) {
  }

  public record ConceptContext(String conceptId, String name) {
  }

  public record SubConceptContext(String subConceptId, String name) {
  }

  public record EvidenceSummary(int supportingCount, int contradictoryCount, int inconclusiveCount) {
  }

  /** {@code band}/{@code policyVersion}/{@code computedAt} read back verbatim from the persisted G3
   * snapshot -- never computed here (M2-ADR-029 §E: H6 never invokes {@code
   * DiagnosticConfidenceCalculatorV1}, and neither does this mapper). */
  public record Confidence(String band, String policyVersion, Instant computedAt) {
  }
}
