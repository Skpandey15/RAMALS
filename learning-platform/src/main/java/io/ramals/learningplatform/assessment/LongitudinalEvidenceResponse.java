package io.ramals.learningplatform.assessment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M2-ADR-030 (H7): the wire shape of a {@link LongitudinalEvidenceReport}. Two factory methods
 * produce the same shape with one deliberate difference: {@link #fromAdminView} populates each
 * finding's {@code adminProvenance}; {@link #fromLearnerView} always sets it to {@code null} -- exact
 * confidence-snapshot/evidence-observation/attempt ids are never serialized to a learner-facing
 * response, only to an admin one (same rule H6's own {@code DiagnosticReportResponse} already
 * follows). No mastery field appears anywhere in this shape; H7 V1 exposes no mastery.
 */
public record LongitudinalEvidenceResponse(
    String learnerId,
    String domainCode,
    Instant generatedAt,
    List<LongitudinalEvidenceFindingResponse> findings) {

  public record LongitudinalEvidenceFindingResponse(
      String misconceptionId,
      String name,
      String description,
      String targetType,
      String targetId,
      ObjectiveContextResponse objectiveContext,
      ConceptContextResponse conceptContext,
      SubConceptContextResponse subConceptContext,
      String dataStatus,
      BaselineResponse baseline,
      String state,
      EvidenceSummaryResponse laterEvidence,
      LatestConfidenceResponse latestConfidence,
      String confidenceCoverage,
      String policyVersion,
      AdminProvenanceResponse adminProvenance) {
  }

  public record ObjectiveContextResponse(String objectiveId, String objectiveCode, String description) {
  }

  public record ConceptContextResponse(String conceptId, String name) {
  }

  public record SubConceptContextResponse(String subConceptId, String name) {
  }

  public record EvidenceSummaryResponse(int supportingCount, int contradictoryCount, int inconclusiveCount) {
  }

  /** {@code evidenceStrength}/{@code computedAt} read back verbatim from the baseline G3 snapshot --
   * never recomputed. Worded "baseline evidence strength", never "as first established". */
  public record BaselineResponse(DiagnosticConfidenceBand evidenceStrength, Instant computedAt) {
  }

  /** {@code evidenceStrength}/{@code computedAt} read back verbatim from the latest persisted G3
   * snapshot -- never recomputed. Worded "latest persisted overall evidence strength", never "current
   * confidence". */
  public record LatestConfidenceResponse(DiagnosticConfidenceBand evidenceStrength, Instant computedAt) {
  }

  /** Admin-only: the exact baseline/latest confidence snapshot ids, the attempt that computed the
   * latest one, and the complete ordered post-baseline evidence-observation ids. Never present in a
   * learner-facing response. */
  public record AdminProvenanceResponse(
      String baselineConfidenceSnapshotId, String latestConfidenceSnapshotId,
      String latestConfidenceAttemptId, List<String> laterEvidenceObservationIds) {
  }

  public static LongitudinalEvidenceResponse fromLearnerView(LongitudinalEvidenceReport report) {
    return from(report, false);
  }

  public static LongitudinalEvidenceResponse fromAdminView(LongitudinalEvidenceReport report) {
    return from(report, true);
  }

  private static LongitudinalEvidenceResponse from(LongitudinalEvidenceReport report, boolean includeProvenance) {
    return new LongitudinalEvidenceResponse(
        toStringOrNull(report.learnerId()),
        report.domainCode(),
        report.generatedAt(),
        report.findings().stream().map(finding -> toFindingResponse(finding, includeProvenance)).toList());
  }

  private static LongitudinalEvidenceFindingResponse toFindingResponse(
      LongitudinalEvidenceReport.LongitudinalEvidenceFinding finding, boolean includeProvenance) {
    ObjectiveContextResponse objectiveContext = finding.objectiveContext() == null ? null
        : new ObjectiveContextResponse(
            finding.objectiveContext().objectiveId().toString(),
            finding.objectiveContext().objectiveCode(),
            finding.objectiveContext().description());
    ConceptContextResponse conceptContext = finding.conceptContext() == null ? null
        : new ConceptContextResponse(
            finding.conceptContext().conceptId().toString(), finding.conceptContext().name());
    SubConceptContextResponse subConceptContext = finding.subConceptContext() == null ? null
        : new SubConceptContextResponse(
            finding.subConceptContext().subConceptId().toString(), finding.subConceptContext().name());
    BaselineResponse baseline = finding.baseline() == null ? null
        : new BaselineResponse(finding.baseline().evidenceStrength(), finding.baseline().computedAt());
    LatestConfidenceResponse latestConfidence = finding.latestConfidence() == null ? null
        : new LatestConfidenceResponse(
            finding.latestConfidence().evidenceStrength(), finding.latestConfidence().computedAt());
    AdminProvenanceResponse adminProvenance = !includeProvenance ? null
        : new AdminProvenanceResponse(
            toStringOrNull(finding.baseline() == null ? null : finding.baseline().confidenceSnapshotId()),
            toStringOrNull(finding.latestConfidence() == null ? null : finding.latestConfidence().confidenceSnapshotId()),
            toStringOrNull(finding.latestConfidence() == null ? null : finding.latestConfidence().attemptId()),
            finding.laterEvidenceObservationIds().stream().map(UUID::toString).toList());

    return new LongitudinalEvidenceFindingResponse(
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
        new EvidenceSummaryResponse(
            finding.laterEvidence().supportingCount(), finding.laterEvidence().contradictoryCount(),
            finding.laterEvidence().inconclusiveCount()),
        latestConfidence,
        finding.confidenceCoverage() == null ? null : finding.confidenceCoverage().name(),
        finding.policyVersion(),
        adminProvenance);
  }

  private static String toStringOrNull(UUID value) {
    return value == null ? null : value.toString();
  }
}
