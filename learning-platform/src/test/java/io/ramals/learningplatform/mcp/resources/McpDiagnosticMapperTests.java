package io.ramals.learningplatform.mcp.resources;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessment.DiagnosticConfidenceBand;
import io.ramals.learningplatform.assessment.DiagnosticReport;
import io.ramals.learningplatform.assessment.DiagnosticReport.ConfidenceState;
import io.ramals.learningplatform.assessment.DiagnosticReport.ConfidenceView;
import io.ramals.learningplatform.assessment.DiagnosticReport.DiagnosticDataStatus;
import io.ramals.learningplatform.assessment.DiagnosticReport.EvidenceSummary;
import io.ramals.learningplatform.assessment.DiagnosticReport.MisconceptionFinding;
import io.ramals.learningplatform.assessment.DiagnosticReport.ReportMode;
import io.ramals.learningplatform.assessment.MisconceptionTargetType;
import io.ramals.learningplatform.mastery.MasteryMapEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MCP-2 semantic-regression suite: {@link McpDiagnosticMapper} must preserve every H6 (M2-ADR-029)
 * semantic distinction unchanged -- {@code NO_EVIDENCE} vs {@code HAS_EVIDENCE}, {@code NOT_ASSESSED}
 * vs {@code ASSESSED} (including {@code INSUFFICIENT_EVIDENCE} remaining {@code ASSESSED}), and an
 * attempt report never carrying mastery. Never invokes any calculator; only maps already-computed
 * domain values.
 */
class McpDiagnosticMapperTests {

  private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

  @Test
  void noEvidenceStatusIsPreserved() {
    DiagnosticReport report = new DiagnosticReport(
        ReportMode.CURRENT_DOMAIN, UUID.randomUUID(), "KAFKA", null, NOW,
        DiagnosticDataStatus.NO_EVIDENCE, List.of(), List.of());

    McpDiagnosticReport mapped = McpDiagnosticMapper.toMcp(report);

    assertThat(mapped.diagnosticDataStatus()).isEqualTo("NO_EVIDENCE");
    assertThat(mapped.misconceptionFindings()).isEmpty();
  }

  @Test
  void notAssessedFindingHasNullConfidenceAndRawEvidenceCounts() {
    MisconceptionFinding finding = finding(ConfidenceState.NOT_ASSESSED, null, new EvidenceSummary(2, 0, 1));
    DiagnosticReport report = new DiagnosticReport(
        ReportMode.CURRENT_DOMAIN, UUID.randomUUID(), "KAFKA", null, NOW,
        DiagnosticDataStatus.HAS_EVIDENCE, List.of(finding), List.of());

    McpDiagnosticReport.Finding mapped = McpDiagnosticMapper.toMcp(report).misconceptionFindings().get(0);

    assertThat(mapped.confidenceState()).isEqualTo("NOT_ASSESSED");
    assertThat(mapped.confidence()).isNull();
    assertThat(mapped.evidenceSummary().supportingCount()).isEqualTo(2);
    assertThat(mapped.evidenceSummary().inconclusiveCount()).isEqualTo(1);
  }

  /** M2-ADR-029: INSUFFICIENT_EVIDENCE is a real, persisted, ASSESSED result -- never conflated
   * with NOT_ASSESSED, which means "no snapshot exists at all yet". */
  @Test
  void insufficientEvidenceBandRemainsAssessedNeverNotAssessed() {
    ConfidenceView confidence =
        new ConfidenceView(DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE, "V1", NOW);
    MisconceptionFinding finding = finding(ConfidenceState.ASSESSED, confidence, new EvidenceSummary(0, 0, 3));
    DiagnosticReport report = new DiagnosticReport(
        ReportMode.CURRENT_DOMAIN, UUID.randomUUID(), "KAFKA", null, NOW,
        DiagnosticDataStatus.HAS_EVIDENCE, List.of(finding), List.of());

    McpDiagnosticReport.Finding mapped = McpDiagnosticMapper.toMcp(report).misconceptionFindings().get(0);

    assertThat(mapped.confidenceState()).isEqualTo("ASSESSED");
    assertThat(mapped.confidence()).isNotNull();
    assertThat(mapped.confidence().band()).isEqualTo("INSUFFICIENT_EVIDENCE");
  }

  /** M2-ADR-029: an Attempt Diagnostic Report never carries mastery, even if the domain object
   * somehow did -- this mapper does not special-case it away, because the authoritative service
   * itself guarantees an empty list for ATTEMPT mode; this test guards that invariant at the MCP
   * boundary too. */
  @Test
  void attemptReportNeverCarriesMastery() {
    DiagnosticReport report = new DiagnosticReport(
        ReportMode.ATTEMPT, UUID.randomUUID(), null, UUID.randomUUID(), NOW,
        DiagnosticDataStatus.HAS_EVIDENCE, List.of(), List.of());

    McpDiagnosticReport mapped = McpDiagnosticMapper.toMcp(report);

    assertThat(mapped.reportMode()).isEqualTo("ATTEMPT");
    assertThat(mapped.mastery()).isEmpty();
  }

  @Test
  void currentDomainReportCarriesMasteryVerbatim() {
    DiagnosticReport report = new DiagnosticReport(
        ReportMode.CURRENT_DOMAIN, UUID.randomUUID(), "KAFKA", null, NOW,
        DiagnosticDataStatus.NO_EVIDENCE, List.of(),
        List.of(new MasteryMapEntry("SKILL_A", new BigDecimal("0.5"), new BigDecimal("0.4"), "DEVELOPING", 1)));

    McpDiagnosticReport mapped = McpDiagnosticMapper.toMcp(report);

    assertThat(mapped.mastery()).containsExactly(
        new McpMasterySkill("SKILL_A", new BigDecimal("0.5"), new BigDecimal("0.4"), "DEVELOPING", 1));
  }

  @Test
  void neverExposesAdminOnlyProvenanceFields() {
    // McpDiagnosticReport.Finding structurally has no confidenceSnapshotId/confidenceAttemptId/
    // evidenceObservationIds field at all -- proven by inspecting its record components, mirroring
    // the same structural-proof style DelegatedLearnerContextTests already uses.
    var componentNames = java.util.Arrays.stream(McpDiagnosticReport.Finding.class.getRecordComponents())
        .map(java.lang.reflect.RecordComponent::getName)
        .toList();

    assertThat(componentNames).doesNotContain(
        "confidenceSnapshotId", "confidenceAttemptId", "evidenceObservationIds");
  }

  private static MisconceptionFinding finding(
      ConfidenceState state, ConfidenceView confidence, EvidenceSummary evidence) {
    return new MisconceptionFinding(
        UUID.randomUUID(), "Some misconception", "description",
        MisconceptionTargetType.LEARNING_OBJECTIVE, UUID.randomUUID(), null, null, null,
        evidence, state, confidence,
        state == ConfidenceState.ASSESSED ? UUID.randomUUID() : null,
        state == ConfidenceState.ASSESSED ? UUID.randomUUID() : null,
        List.of());
  }
}
