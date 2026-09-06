package io.ramals.learningplatform.mcp.resources;

import java.time.Instant;
import java.util.List;

/**
 * MCP-2 wire shape for H7's {@code LongitudinalEvidenceReport} (M2-ADR-030) -- a separate contract
 * from {@code LongitudinalEvidenceResponse} (the REST DTO). Shared by both {@code
 * diagnostics.longitudinal-summary} (potentially many findings, one per misconception with {@code
 * dataStatus == "HAS_BASELINE"} in the requested domain) and {@code
 * diagnostics.misconception-longitudinal-detail} (exactly one finding, present even when {@code
 * dataStatus == "NO_BASELINE"} -- never a 404 for an existing misconception the learner has no
 * baseline for yet).
 *
 * <p>Every {@code LongitudinalEvidenceState} value (M2-ADR-030) is preserved verbatim, by name, never
 * renamed to a stronger semantic claim -- there is no "resolved"/"recovered"/"confirmed"/"persistent"/
 * "root-caused"/"likely"/"probable" anywhere in this shape or its mapper.
 */
public record McpLongitudinalReport(
    String domainCode,
    Instant generatedAt,
    List<Finding> findings) {

  /**
   * @param dataStatus {@code "NO_BASELINE"} or {@code "HAS_BASELINE"} -- never conflated with {@code
   *     state}'s own {@code "NO_LATER_EVIDENCE"}, which is a different, only-meaningful-once-a-
   *     baseline-exists classification
   * @param baseline the fixed evidentiary boundary; {@code null} iff {@code dataStatus ==
   *     "NO_BASELINE"}
   * @param state the {@code LONGITUDINAL_EVIDENCE_V1} classification of post-baseline evidence;
   *     {@code null} iff {@code dataStatus == "NO_BASELINE"}
   * @param laterEvidenceObservationIds every post-baseline evidence-observation id, in the same
   *     deterministic ({@code created_at ASC, id ASC}) presentation order H7 already uses -- never a
   *     causal/generation-order guarantee
   * @param latestConfidence the latest persisted G3 snapshot for this pair, exposed as separate
   *     context; {@code null} iff no G3 snapshot exists at all yet
   * @param confidenceCoverage {@code "CURRENT"} or {@code "STALE_RELATIVE_TO_LATER_EVIDENCE"}; {@code
   *     null} iff {@code latestConfidence == null}
   */
  public record Finding(
      String misconceptionId,
      String name,
      String description,
      String targetType,
      String targetId,
      McpDiagnosticReport.ObjectiveContext objectiveContext,
      McpDiagnosticReport.ConceptContext conceptContext,
      McpDiagnosticReport.SubConceptContext subConceptContext,
      String dataStatus,
      Baseline baseline,
      String state,
      McpDiagnosticReport.EvidenceSummary laterEvidence,
      List<String> laterEvidenceObservationIds,
      LatestConfidence latestConfidence,
      String confidenceCoverage,
      String policyVersion) {
  }

  /** {@code evidenceStrength}/{@code computedAt} read back verbatim -- never recomputed. Worded
   * "baseline evidence strength", never "as first established". */
  public record Baseline(String evidenceStrength, Instant computedAt) {
  }

  /** {@code evidenceStrength}/{@code computedAt} read back verbatim -- never recomputed. Worded
   * "latest persisted overall evidence strength", never "current confidence". */
  public record LatestConfidence(String evidenceStrength, Instant computedAt) {
  }
}
