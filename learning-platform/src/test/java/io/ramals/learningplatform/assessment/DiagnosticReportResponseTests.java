package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessment.DiagnosticReport.ConfidenceState;
import io.ramals.learningplatform.assessment.DiagnosticReport.ConfidenceView;
import io.ramals.learningplatform.assessment.DiagnosticReport.DiagnosticDataStatus;
import io.ramals.learningplatform.assessment.DiagnosticReport.EvidenceSummary;
import io.ramals.learningplatform.assessment.DiagnosticReport.MisconceptionFinding;
import io.ramals.learningplatform.assessment.DiagnosticReport.ObjectiveContext;
import io.ramals.learningplatform.assessment.DiagnosticReport.ReportMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * M2-ADR-029 (H6): the exact learner-vs-admin visibility split {@link DiagnosticReportResponse}'s
 * two factory methods produce, and structural proof that no H5 or forbidden term ever appears in the
 * serialized shape -- a plain unit test, no database needed, since this is entirely a mapping/
 * serialization property of the DTO itself.
 */
class DiagnosticReportResponseTests {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void learnerViewNeverIncludesExactProvenanceIds() {
    DiagnosticReport report = sampleReport();

    DiagnosticReportResponse learnerView = DiagnosticReportResponse.fromLearnerView(report);

    assertThat(learnerView.misconceptionFindings()).hasSize(1);
    assertThat(learnerView.misconceptionFindings().get(0).adminProvenance()).isNull();
    String json = MAPPER.writeValueAsString(learnerView);
    // The field is present (Jackson serializes null by default) but its value must never be --
    // the exact snapshot/evidence ids themselves must never appear anywhere in learner JSON.
    assertThat(json)
        .contains("\"adminProvenance\":null")
        .doesNotContain(report.misconceptionFindings().get(0).confidenceSnapshotId().toString())
        .doesNotContain(report.misconceptionFindings().get(0).confidenceAttemptId().toString());
  }

  @Test
  void adminViewIncludesExactProvenanceIds() {
    DiagnosticReport report = sampleReport();
    MisconceptionFinding finding = report.misconceptionFindings().get(0);

    DiagnosticReportResponse adminView = DiagnosticReportResponse.fromAdminView(report);

    DiagnosticReportResponse.AdminProvenanceResponse provenance =
        adminView.misconceptionFindings().get(0).adminProvenance();
    assertThat(provenance).isNotNull();
    assertThat(provenance.confidenceSnapshotId()).isEqualTo(finding.confidenceSnapshotId().toString());
    assertThat(provenance.confidenceAttemptId()).isEqualTo(finding.confidenceAttemptId().toString());
    assertThat(provenance.evidenceObservationIds())
        .containsExactlyElementsOf(finding.evidenceObservationIds().stream().map(UUID::toString).toList());
  }

  @Test
  void aNotAssessedFindingNeverCarriesProvenanceEvenInTheAdminView() {
    DiagnosticReport report = sampleNotAssessedReport();

    DiagnosticReportResponse adminView = DiagnosticReportResponse.fromAdminView(report);

    DiagnosticReportResponse.MisconceptionFindingResponse finding =
        adminView.misconceptionFindings().get(0);
    assertThat(finding.confidenceState()).isEqualTo("NOT_ASSESSED");
    assertThat(finding.confidence()).isNull();
    assertThat(finding.adminProvenance()).isNull();
  }

  @Test
  void serializedResponseContainsNoH5OrForbiddenTerminology() {
    DiagnosticReport report = sampleReport();

    String learnerJson = MAPPER.writeValueAsString(DiagnosticReportResponse.fromLearnerView(report));
    String adminJson = MAPPER.writeValueAsString(DiagnosticReportResponse.fromAdminView(report));

    for (String json : List.of(learnerJson, adminJson)) {
      assertThat(json)
          .doesNotContain("hypothesisFindings")
          .doesNotContain("sourceObjectiveId")
          .doesNotContain("targetObjectiveId")
          .doesNotContain("relationshipType")
          .doesNotContainIgnoringCase("rootCause")
          .doesNotContainIgnoringCase("probability")
          .doesNotContainIgnoringCase("likelihood")
          .doesNotContainIgnoringCase("confirmed")
          .doesNotContainIgnoringCase("diagnosisStatus");
    }
  }

  @Test
  void insufficientEvidenceIsAssessedAndDistinctFromNotAssessed() {
    DiagnosticReport insufficientEvidence = sampleReport();
    DiagnosticReport notAssessed = sampleNotAssessedReport();

    DiagnosticReportResponse.MisconceptionFindingResponse assessedFinding =
        DiagnosticReportResponse.fromLearnerView(insufficientEvidence).misconceptionFindings().get(0);
    DiagnosticReportResponse.MisconceptionFindingResponse notAssessedFinding =
        DiagnosticReportResponse.fromLearnerView(notAssessed).misconceptionFindings().get(0);

    assertThat(assessedFinding.confidenceState()).isEqualTo("ASSESSED");
    assertThat(assessedFinding.confidence().band()).isEqualTo("INSUFFICIENT_EVIDENCE");
    assertThat(notAssessedFinding.confidenceState()).isEqualTo("NOT_ASSESSED");
    assertThat(notAssessedFinding.confidence()).isNull();
  }

  private static DiagnosticReport sampleReport() {
    UUID misconceptionId = UUID.randomUUID();
    UUID snapshotId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    UUID evidenceOne = UUID.randomUUID();
    UUID evidenceTwo = UUID.randomUUID();
    MisconceptionFinding finding = new MisconceptionFinding(
        misconceptionId, "acks=all guarantees durability", "description",
        MisconceptionTargetType.LEARNING_OBJECTIVE, misconceptionId,
        new ObjectiveContext(UUID.randomUUID(), "ACKS_DURABILITY_TRADEOFFS", "objective description"),
        null, null,
        new EvidenceSummary(0, 0, 2),
        ConfidenceState.ASSESSED,
        new ConfidenceView(DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE,
            DiagnosticConfidenceCalculatorV1.POLICY_VERSION, Instant.now()),
        snapshotId, attemptId, List.of(evidenceOne, evidenceTwo));
    return new DiagnosticReport(
        ReportMode.CURRENT_DOMAIN, UUID.randomUUID(), "KAFKA", null, Instant.now(),
        DiagnosticDataStatus.HAS_EVIDENCE, List.of(finding), List.of());
  }

  private static DiagnosticReport sampleNotAssessedReport() {
    UUID misconceptionId = UUID.randomUUID();
    MisconceptionFinding finding = new MisconceptionFinding(
        misconceptionId, "acks=all guarantees durability", "description",
        MisconceptionTargetType.LEARNING_OBJECTIVE, misconceptionId,
        new ObjectiveContext(UUID.randomUUID(), "ACKS_DURABILITY_TRADEOFFS", "objective description"),
        null, null,
        new EvidenceSummary(1, 0, 0),
        ConfidenceState.NOT_ASSESSED, null, null, null, List.of());
    return new DiagnosticReport(
        ReportMode.CURRENT_DOMAIN, UUID.randomUUID(), "KAFKA", null, Instant.now(),
        DiagnosticDataStatus.HAS_EVIDENCE, List.of(finding), List.of());
  }
}
