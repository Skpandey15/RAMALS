package io.ramals.learningplatform.assessment;

import io.ramals.learningplatform.mastery.MasteryMapResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M2-ADR-029 (H6): the wire shape of a {@link DiagnosticReport}. Two factory methods produce the
 * same shape with one deliberate difference: {@link #fromAdminView} populates each finding's {@code
 * adminProvenance}; {@link #fromLearnerView} always sets it to {@code null} -- exact confidence/
 * evidence provenance ids are never serialized to a learner-facing response (M2-ADR-029 §I), only to
 * an admin one. No H5 field appears anywhere in this shape; H6 V1 composes G2 evidence, G3
 * confidence, ontology context, and mastery context only.
 */
public record DiagnosticReportResponse(
    String reportMode,
    String diagnosticDataStatus,
    String domainCode,
    String attemptId,
    Instant generatedAt,
    List<MisconceptionFindingResponse> misconceptionFindings,
    List<MasteryMapResponse.Skill> mastery) {

  public record MisconceptionFindingResponse(
      String misconceptionId,
      String name,
      String description,
      String targetType,
      String targetId,
      ObjectiveContextResponse objectiveContext,
      ConceptContextResponse conceptContext,
      SubConceptContextResponse subConceptContext,
      EvidenceSummaryResponse evidenceSummary,
      String confidenceState,
      ConfidenceResponse confidence,
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

  /** {@code band}/{@code policyVersion} read back verbatim from the persisted G3 snapshot -- never
   * computed here. Evidence strength under a governed policy, never a probability, never mastery or
   * progression authority. */
  public record ConfidenceResponse(String band, String policyVersion, Instant computedAt) {
  }

  /** Admin-only: the exact confidence snapshot id, the attempt that computed it, and its complete
   * cited evidence-observation ids (M2-ADR-028 §7). Never present in a learner-facing response. */
  public record AdminProvenanceResponse(
      String confidenceSnapshotId, String confidenceAttemptId, List<String> evidenceObservationIds) {
  }

  public static DiagnosticReportResponse fromLearnerView(DiagnosticReport report) {
    return from(report, false);
  }

  public static DiagnosticReportResponse fromAdminView(DiagnosticReport report) {
    return from(report, true);
  }

  private static DiagnosticReportResponse from(DiagnosticReport report, boolean includeProvenance) {
    return new DiagnosticReportResponse(
        report.mode().name(),
        report.diagnosticDataStatus().name(),
        report.domainCode(),
        toStringOrNull(report.attemptId()),
        report.generatedAt(),
        report.misconceptionFindings().stream()
            .map(finding -> toFindingResponse(finding, includeProvenance))
            .toList(),
        report.mastery().stream()
            .map(entry -> new MasteryMapResponse.Skill(
                entry.skillCode(), entry.masteryScore(), entry.evidenceConfidence(),
                entry.masteryStatus(), entry.aggregateVersion()))
            .toList());
  }

  private static MisconceptionFindingResponse toFindingResponse(
      DiagnosticReport.MisconceptionFinding finding, boolean includeProvenance) {
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
    ConfidenceResponse confidence = finding.confidence() == null ? null
        : new ConfidenceResponse(
            finding.confidence().band().name(), finding.confidence().policyVersion(),
            finding.confidence().computedAt());
    AdminProvenanceResponse adminProvenance = !includeProvenance || finding.confidenceSnapshotId() == null
        ? null
        : new AdminProvenanceResponse(
            finding.confidenceSnapshotId().toString(),
            toStringOrNull(finding.confidenceAttemptId()),
            finding.evidenceObservationIds().stream().map(UUID::toString).toList());

    return new MisconceptionFindingResponse(
        finding.misconceptionId().toString(),
        finding.name(),
        finding.description(),
        finding.targetType().name(),
        finding.targetId().toString(),
        objectiveContext,
        conceptContext,
        subConceptContext,
        new EvidenceSummaryResponse(
            finding.evidenceSummary().supportingCount(), finding.evidenceSummary().contradictoryCount(),
            finding.evidenceSummary().inconclusiveCount()),
        finding.confidenceState().name(),
        confidence,
        adminProvenance);
  }

  private static String toStringOrNull(UUID value) {
    return value == null ? null : value.toString();
  }
}
