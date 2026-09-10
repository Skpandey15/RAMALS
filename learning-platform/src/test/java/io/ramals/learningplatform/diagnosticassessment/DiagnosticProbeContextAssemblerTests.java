package io.ramals.learningplatform.diagnosticassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.ai.AiDelegatedCapabilityPolicy;
import io.ramals.learningplatform.assessment.DiagnosticReport;
import io.ramals.learningplatform.assessment.DiagnosticReport.ConfidenceState;
import io.ramals.learningplatform.assessment.DiagnosticReport.DiagnosticDataStatus;
import io.ramals.learningplatform.assessment.DiagnosticReport.EvidenceSummary;
import io.ramals.learningplatform.assessment.DiagnosticReport.MisconceptionFinding;
import io.ramals.learningplatform.assessment.DiagnosticReport.ObjectiveContext;
import io.ramals.learningplatform.assessment.DiagnosticReport.ReportMode;
import io.ramals.learningplatform.assessment.DiagnosticReportService;
import io.ramals.learningplatform.assessment.LongitudinalDataStatus;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceReport;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceReport.LongitudinalEvidenceFinding;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceService;
import io.ramals.learningplatform.assessment.LongitudinalEvidenceState;
import io.ramals.learningplatform.assessment.MisconceptionTargetType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The authoritative context is assembled from Java's own governed H6/H7 read services only, never
 * from an AI payload (M2-ADR-032 step 3, §4). {@code M_allowed} and {@code E_allowed} are the exact
 * sets the MCP-2 reads expose; {@code allowedCandidateProbeRefs} is empty in step 3; and
 * {@code groundable} is false whenever there is nothing for the model to reason a probe from.
 */
class DiagnosticProbeContextAssemblerTests {

  private static final UUID LEARNER = UUID.fromString("01900000-0000-7000-8000-0000000000c1");
  private static final UUID MC_H6 = UUID.fromString("01900000-0000-7000-8000-0000000000a1");
  private static final UUID MC_H7_ONLY = UUID.fromString("01900000-0000-7000-8000-0000000000a2");
  private static final UUID OBJECTIVE = UUID.fromString("01900000-0000-7000-8000-0000000000b1");
  private static final UUID EV_1 = UUID.fromString("01900000-0000-7000-8000-0000000000e1");
  private static final UUID EV_2 = UUID.fromString("01900000-0000-7000-8000-0000000000e2");
  private static final Instant NOW = Instant.parse("2026-09-09T00:00:00Z");

  private final DiagnosticReportService h6 = mock(DiagnosticReportService.class);
  private final LongitudinalEvidenceService h7 = mock(LongitudinalEvidenceService.class);
  private final DiagnosticProbeContextAssembler assembler =
      new DiagnosticProbeContextAssembler(h6, h7);

  private void givenH6(DiagnosticReport report) {
    when(h6.currentDomainReportForLearner(eq(LEARNER), eq("KAFKA"))).thenReturn(report);
  }

  private void givenH7(LongitudinalEvidenceReport report) {
    when(h7.domainSummaryForLearner(eq(LEARNER), eq("KAFKA"))).thenReturn(report);
  }

  private static DiagnosticReport h6Report(DiagnosticDataStatus status, MisconceptionFinding... f) {
    return new DiagnosticReport(
        ReportMode.CURRENT_DOMAIN, LEARNER, "KAFKA", null, NOW, status, List.of(f), List.of());
  }

  private static MisconceptionFinding h6Finding(UUID misconceptionId) {
    return new MisconceptionFinding(
        misconceptionId,
        "Confuses partition with replica",
        "A learner treats a partition and a replica as the same thing.",
        MisconceptionTargetType.LEARNING_OBJECTIVE,
        OBJECTIVE,
        new ObjectiveContext(OBJECTIVE, "KAFKA_PARTITIONS", "Understand partitions."),
        null,
        null,
        new EvidenceSummary(1, 1, 0),
        ConfidenceState.NOT_ASSESSED,
        null,
        null,
        null,
        List.of());
  }

  private static LongitudinalEvidenceReport h7Report(LongitudinalEvidenceFinding... f) {
    return new LongitudinalEvidenceReport(LEARNER, "KAFKA", NOW, List.of(f));
  }

  private static LongitudinalEvidenceFinding h7Finding(UUID misconceptionId, List<UUID> laterIds) {
    boolean hasBaseline = !laterIds.isEmpty();
    return new LongitudinalEvidenceFinding(
        misconceptionId,
        "Confuses partition with replica",
        "A learner treats a partition and a replica as the same thing.",
        MisconceptionTargetType.LEARNING_OBJECTIVE,
        OBJECTIVE,
        null,
        null,
        null,
        hasBaseline ? LongitudinalDataStatus.HAS_BASELINE : LongitudinalDataStatus.NO_BASELINE,
        null,
        hasBaseline ? LongitudinalEvidenceState.LATER_MIXED_EVIDENCE : null,
        new EvidenceSummary(1, 1, 0),
        laterIds,
        null,
        null,
        hasBaseline ? "LONGITUDINAL_EVIDENCE_V1" : null);
  }

  @Test
  @DisplayName("M_allowed is the union of every misconception H6 or H7 surfaces; E_allowed is H7's "
      + "post-baseline observation ids")
  void assemblesAllowedSetsFromBothSources() {
    givenH6(h6Report(DiagnosticDataStatus.HAS_EVIDENCE, h6Finding(MC_H6)));
    givenH7(h7Report(
        h7Finding(MC_H6, List.of(EV_1, EV_2)), h7Finding(MC_H7_ONLY, List.of(EV_2))));

    DiagnosticProbeContextAssembler.Assembled assembled =
        assembler.assemble(LEARNER, "kafka", "int-1");
    DiagnosticProbeProposalContext context = assembled.context();

    assertThat(context.authoritativeLearnerId()).isEqualTo(LEARNER);
    assertThat(context.interactionId()).isEqualTo("int-1");
    assertThat(context.domain()).isEqualTo("KAFKA");
    assertThat(context.allowedMisconceptionIds()).containsExactlyInAnyOrder(MC_H6, MC_H7_ONLY);
    assertThat(context.allowedEvidenceRefs())
        .containsExactlyInAnyOrder(EV_1.toString(), EV_2.toString());
    assertThat(context.allowedCandidateProbeRefs()).isEmpty();
    assertThat(context.delegatedCapabilities())
        .isEqualTo(AiDelegatedCapabilityPolicy.DIAGNOSTIC_ASSESSMENT_CAPABILITIES);
    assertThat(assembled.groundable()).isTrue();
    assertThat(assembled.h6DataStatus()).isEqualTo(DiagnosticDataStatus.HAS_EVIDENCE);
  }

  @Test
  @DisplayName("candidate probe references are empty in step 3 -- the model may only name an intent")
  void candidateProbeRefsAreAlwaysEmptyInStepThree() {
    givenH6(h6Report(DiagnosticDataStatus.HAS_EVIDENCE, h6Finding(MC_H6)));
    givenH7(h7Report(h7Finding(MC_H6, List.of(EV_1))));

    assertThat(assembler.assemble(LEARNER, "KAFKA", "int-1").context().allowedCandidateProbeRefs())
        .isEmpty();
  }

  @Test
  @DisplayName("H6 NO_EVIDENCE -> not groundable, so the caller returns ABSENT without a model call")
  void h6NoEvidenceIsNotGroundable() {
    givenH6(h6Report(DiagnosticDataStatus.NO_EVIDENCE));
    givenH7(h7Report());

    DiagnosticProbeContextAssembler.Assembled assembled =
        assembler.assemble(LEARNER, "KAFKA", "int-1");

    assertThat(assembled.groundable()).isFalse();
    assertThat(assembled.h6DataStatus()).isEqualTo(DiagnosticDataStatus.NO_EVIDENCE);
  }

  @Test
  @DisplayName("H6 has evidence but H7 exposes no post-baseline observation ids -> E_allowed empty, "
      + "not groundable (documented limitation)")
  void emptyEvidenceRefsIsNotGroundable() {
    givenH6(h6Report(DiagnosticDataStatus.HAS_EVIDENCE, h6Finding(MC_H6)));
    givenH7(h7Report(h7Finding(MC_H6, List.of())));

    DiagnosticProbeContextAssembler.Assembled assembled =
        assembler.assemble(LEARNER, "KAFKA", "int-1");

    assertThat(assembled.context().allowedEvidenceRefs()).isEmpty();
    assertThat(assembled.groundable()).isFalse();
  }

  @Test
  @DisplayName("no misconception findings at all -> M_allowed empty, not groundable")
  void emptyMisconceptionSetIsNotGroundable() {
    givenH6(h6Report(DiagnosticDataStatus.HAS_EVIDENCE));
    givenH7(h7Report());

    assertThat(assembler.assemble(LEARNER, "KAFKA", "int-1").groundable()).isFalse();
  }

  @Test
  @DisplayName("the domain code is normalised to upper case before the H6/H7 reads and on the "
      + "context")
  void domainCodeIsNormalised() {
    givenH6(h6Report(DiagnosticDataStatus.HAS_EVIDENCE, h6Finding(MC_H6)));
    givenH7(h7Report(h7Finding(MC_H6, List.of(EV_1))));

    assertThat(assembler.assemble(LEARNER, "kafka", "int-1").context().domain()).isEqualTo("KAFKA");
  }
}
