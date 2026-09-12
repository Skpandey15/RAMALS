package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.assessment.hypothesisdiscrimination.CandidateDiscrimination;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.CandidateProbe;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationResult;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationStatus;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.CandidateUncertainty;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContext;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContextAssembler;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyResult;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * M2-ADR-034 Amendment 4: {@link DiagnosticSelectionV6ReplayService} never rediscovers a historical
 * source attempt or exposure state, always recomputes (never trusts) the persisted
 * activated/fallback outcome, and verifies a real selection against
 * {@code core.diagnostic_probe_provenance} rather than the persisted metadata alone.
 */
@ExtendWith(MockitoExtension.class)
class DiagnosticSelectionV6ReplayServiceTests {

  private static final UUID DESTINATION_ATTEMPT_ID = UUID.fromString("01900000-0000-7000-8000-0000000a0001");
  private static final UUID SOURCE_ATTEMPT_ID = UUID.fromString("01900000-0000-7000-8000-0000000a0002");
  private static final UUID LEARNER_ID = UUID.fromString("01900000-0000-7000-8000-0000000a0003");
  private static final UUID ASSESSMENT_VERSION_ID = UUID.fromString("01900000-0000-7000-8000-0000000a0004");
  private static final UUID REPLAY_INPUT_ID = UUID.fromString("01900000-0000-7000-8000-0000000a0005");

  private static final UUID TRIGGER_ITEM = UUID.fromString("01900000-0000-7000-8000-0000000a1001");
  private static final UUID TRIGGER_OBJECTIVE = UUID.fromString("01900000-0000-7000-8000-0000000a2001");
  private static final UUID TARGET_A = UUID.fromString("01900000-0000-7000-8000-0000000a3001");
  private static final UUID TARGET_B = UUID.fromString("01900000-0000-7000-8000-0000000a3002");
  private static final UUID AUTHORIZING_RELATIONSHIP = UUID.fromString("01900000-0000-7000-8000-0000000a4001");
  private static final UUID ITEM_A1 = UUID.fromString("01900000-0000-7000-8000-0000000a5001");
  private static final UUID ITEM_B1 = UUID.fromString("01900000-0000-7000-8000-0000000a5002");

  private static final DiagnosticHypothesis HYPOTHESIS_A = new DiagnosticHypothesis(
      TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_A, AUTHORIZING_RELATIONSHIP);
  private static final DiagnosticHypothesis HYPOTHESIS_B = new DiagnosticHypothesis(
      TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_B, AUTHORIZING_RELATIONSHIP);

  @Mock private AssessmentRepository repository;
  @Mock private DiagnosticSelectionReplayInputRepository replayInputRepository;
  @Mock private ProbeProvenanceRepository probeProvenanceRepository;
  @Mock private ProbeRelationshipService probeRelationshipService;
  @Mock private HypothesisUncertaintyContextAssembler assembler;
  @Mock private HypothesisUncertaintyCalculatorV1 uncertaintyCalculator;
  @Mock private HypothesisDiscriminationCalculatorV1 discriminationCalculator;

  private DiagnosticSelectionV6ReplayService service;

  private final AssessmentAttempt destinationAttempt = new AssessmentAttempt(
      DESTINATION_ATTEMPT_ID, LEARNER_ID, ASSESSMENT_VERSION_ID, "COMPLETED", "idem-key",
      Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

  @BeforeEach
  void setUp() {
    HypothesisDiscriminationDiagnosticSelector selector = new HypothesisDiscriminationDiagnosticSelector(
        repository, probeRelationshipService, assembler, uncertaintyCalculator, discriminationCalculator);
    service = new DiagnosticSelectionV6ReplayService(
        repository, replayInputRepository, probeProvenanceRepository, selector);
  }

  private DiagnosticSelectionReplayInput header(
      UUID sourceAttemptId, boolean activated, V6FallbackReason fallbackReason) {
    return new DiagnosticSelectionReplayInput(
        REPLAY_INPUT_ID, DESTINATION_ATTEMPT_ID, sourceAttemptId,
        DiagnosticSelectionReplayInputRepository.SNAPSHOT_CONTRACT_VERSION,
        2, 2, 2, 2, "APPLICABLE", "SCORABLE", activated, fallbackReason);
  }

  private static AdaptiveEligibleItem eligibleItem(UUID itemVersionId, String skillCode) {
    return new AdaptiveEligibleItem(
        itemVersionId, UUID.randomUUID(), UUID.randomUUID(), skillCode, "MULTIPLE_CHOICE", "MEDIUM");
  }

  private void stubGenuineTieActivation() {
    HypothesisUncertaintyContext context =
        new HypothesisUncertaintyContext(SOURCE_ATTEMPT_ID, "KAFKA", List.of(), List.of());
    when(assembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(context);
    when(uncertaintyCalculator.calculate(context)).thenReturn(new HypothesisUncertaintyResult(
        "HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.APPLICABLE,
        List.of(
            new CandidateUncertainty(HYPOTHESIS_A, DiagnosticConfidenceBand.MODERATE, true, new BigDecimal("0.5000")),
            new CandidateUncertainty(HYPOTHESIS_B, DiagnosticConfidenceBand.MODERATE, true, new BigDecimal("0.5000")))));
    when(discriminationCalculator.calculate(any())).thenReturn(new HypothesisDiscriminationResult(
        "HYPOTHESIS_DISCRIMINATION_V1", HypothesisDiscriminationStatus.SCORABLE,
        List.of(
            new CandidateDiscrimination(ITEM_A1, HYPOTHESIS_A, new BigDecimal("0.3000")),
            new CandidateDiscrimination(ITEM_B1, HYPOTHESIS_B, new BigDecimal("0.7000")))));
    when(repository.findAdaptiveEligibleItems(ASSESSMENT_VERSION_ID)).thenReturn(
        List.of(eligibleItem(ITEM_A1, "SKILL_A"), eligibleItem(ITEM_B1, "SKILL_B")));
    when(replayInputRepository.findCandidateProbes(REPLAY_INPUT_ID)).thenReturn(List.of(
        new CandidateProbe(ITEM_A1, HYPOTHESIS_A, true), new CandidateProbe(ITEM_B1, HYPOTHESIS_B, true)));
    when(replayInputRepository.findActionableHypotheses(REPLAY_INPUT_ID))
        .thenReturn(List.of(HYPOTHESIS_A, HYPOTHESIS_B));
  }

  @Test
  @DisplayName("unknown destination attempt -> AttemptNotFoundException, snapshot never looked up")
  void replay_unknownAttempt_throwsAttemptNotFoundException() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.replay(DESTINATION_ATTEMPT_ID))
        .isInstanceOf(AttemptNotFoundException.class);
    verifyNoInteractions(replayInputRepository);
  }

  @Test
  @DisplayName("no replay-input snapshot -> NOT_AVAILABLE, never inferred from created_at or state")
  void replay_noSnapshot_returnsNotAvailable() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.empty());

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.NOT_AVAILABLE);
    assertThat(result.recomputedDecision()).isNull();
  }

  @Test
  @DisplayName("NO_SOURCE_ATTEMPT reproduced directly -- never searches for a source attempt")
  void replay_noSourceAttempt_verifiesDirectlyWithoutSearching() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(null, false, V6FallbackReason.NO_SOURCE_ATTEMPT)));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.VERIFIED);
    assertThat(result.recomputedDecision().activated()).isFalse();
    assertThat(result.recomputedDecision().fallbackReason()).isEqualTo(V6FallbackReason.NO_SOURCE_ATTEMPT);
    verify(repository, never()).findMostRecentCompletedAttempt(any(), any());
    verify(repository, never()).findAdaptiveEligibleItems(any());
    verify(replayInputRepository, never()).findCandidateProbes(any());
    verify(replayInputRepository, never()).findActionableHypotheses(any());
    verifyNoInteractions(assembler, uncertaintyCalculator, discriminationCalculator);
  }

  @Test
  @DisplayName("recomputed decision activates and matches provenance -> VERIFIED")
  void replay_activatedDecisionMatchingProvenance_returnsVerified() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, true, null)));
    stubGenuineTieActivation();
    when(probeProvenanceRepository.findByAttemptAndItem(DESTINATION_ATTEMPT_ID, ITEM_B1))
        .thenReturn(Optional.of(new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID, ITEM_B1,
            SOURCE_ATTEMPT_ID, TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
            TARGET_B, AUTHORIZING_RELATIONSHIP)));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.VERIFIED);
    assertThat(result.recomputedDecision().activated()).isTrue();
    assertThat(result.recomputedDecision().selection().orElseThrow().chosenItemVersionId()).isEqualTo(ITEM_B1);
    verify(repository, never()).findMostRecentCompletedAttempt(any(), any());
  }

  @Test
  @DisplayName("recomputed selection has no matching diagnostic_probe_provenance row -> INTEGRITY_FAILURE")
  void replay_activatedDecisionProvenanceMismatch_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, true, null)));
    stubGenuineTieActivation();
    when(probeProvenanceRepository.findByAttemptAndItem(DESTINATION_ATTEMPT_ID, ITEM_B1))
        .thenReturn(Optional.empty());

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("diagnostic_probe_provenance");
  }

  @Test
  @DisplayName("recomputed activated/fallbackReason diverges from persisted -> INTEGRITY_FAILURE, "
      + "the persisted outcome is never trusted as the sole source of truth")
  void replay_recomputedOutcomeDivergesFromPersisted_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    // Persisted metadata falsely claims this decision fell back to ALL_SCORES_ZERO; the recomputed
    // decision (stubbed below) genuinely activates.
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.ALL_SCORES_ZERO)));
    stubGenuineTieActivation();

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("diverges from persisted");
    assertThat(result.recomputedDecision().activated()).isTrue();
  }
}
