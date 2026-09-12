package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    // The overwhelming majority of tests in this class replay a genuine DIAGNOSTIC_SELECTION_V6
    // attempt; the handful that deliberately exercise a non-V6 destination attempt (M2-ADR-034
    // Amendment 4 correction round, blocker 3) override this with their own stub. lenient() avoids
    // Mockito's strict-stubbing failure on the tests that never reach this call at all (e.g. an
    // unknown attempt, which throws first).
    lenient().when(repository.findSelectionPolicy(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(HypothesisDiscriminationDiagnosticSelector.SELECTION_POLICY_VERSION));
  }

  /** A header shaped exactly like {@link #stubGenuineTieActivation()}'s own working set (two
   * actionable hypotheses, two candidates, both Step-1 participants, Step-2 SCORABLE) -- valid for
   * either an activated outcome or a deliberately-mismatched-activation test built on top of it. */
  private DiagnosticSelectionReplayInput header(
      UUID sourceAttemptId, boolean activated, V6FallbackReason fallbackReason) {
    return header(sourceAttemptId, activated, fallbackReason, 2, 2, 2, 2, "APPLICABLE", "SCORABLE");
  }

  private DiagnosticSelectionReplayInput header(
      UUID sourceAttemptId, boolean activated, V6FallbackReason fallbackReason,
      int relationshipAuthorizedCount, int actionableHypothesisCount, int candidateProbeCount,
      int participatingHypothesisCount, String step1Status, String step2Status) {
    return new DiagnosticSelectionReplayInput(
        REPLAY_INPUT_ID, DESTINATION_ATTEMPT_ID, sourceAttemptId,
        DiagnosticSelectionReplayInputRepository.SNAPSHOT_CONTRACT_VERSION,
        relationshipAuthorizedCount, actionableHypothesisCount, candidateProbeCount,
        participatingHypothesisCount, step1Status, step2Status, activated, fallbackReason);
  }

  /** The correctly-shaped header for a genuine NO_SOURCE_ATTEMPT outcome -- every count zero, no
   * Step-1/Step-2 status, exactly what {@link HypothesisDiscriminationDiagnosticSelector#noSourceAttemptDecision()}
   * itself produces. */
  private DiagnosticSelectionReplayInput noSourceAttemptHeader() {
    return header(null, false, V6FallbackReason.NO_SOURCE_ATTEMPT, 0, 0, 0, 0, null, null);
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
    lenient().when(repository.findAdaptiveEligibleItemsForItemVersions(any())).thenReturn(
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
        .thenReturn(Optional.of(noSourceAttemptHeader()));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.VERIFIED);
    assertThat(result.recomputedDecision().activated()).isFalse();
    assertThat(result.recomputedDecision().fallbackReason()).isEqualTo(V6FallbackReason.NO_SOURCE_ATTEMPT);
    assertThat(result.verifiedProbeProvenance()).isNull();
    verify(repository, never()).findMostRecentCompletedAttempt(any(), any());
    verify(repository, never()).findAdaptiveEligibleItems(any());
    verify(repository, never()).findAdaptiveEligibleItemsForItemVersions(any());
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
    ProbeProvenance provenance = new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID, ITEM_B1,
        SOURCE_ATTEMPT_ID, TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        TARGET_B, AUTHORIZING_RELATIONSHIP);
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of(provenance));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.VERIFIED);
    assertThat(result.recomputedDecision().activated()).isTrue();
    assertThat(result.recomputedDecision().selection().orElseThrow().chosenItemVersionId()).isEqualTo(ITEM_B1);
    assertThat(result.verifiedProbeProvenance()).isEqualTo(provenance);
    verify(repository, never()).findMostRecentCompletedAttempt(any(), any());
    verify(repository, never()).findAdaptiveEligibleItems(any());
  }

  @Test
  @DisplayName("recomputed selection has no matching diagnostic_probe_provenance row -> INTEGRITY_FAILURE")
  void replay_activatedDecisionProvenanceMismatch_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, true, null)));
    stubGenuineTieActivation();
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of());

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("diagnostic_probe_provenance");
  }

  @Test
  @DisplayName("more than one diagnostic_probe_provenance row for one attempt -> INTEGRITY_FAILURE "
      + "(packet quota violation, MAX_HYPOTHESIS_PROBES_PER_PACKET = 1)")
  void replay_multipleProvenanceRowsForOneAttempt_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, true, null)));
    stubGenuineTieActivation();
    ProbeProvenance first = new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID, ITEM_B1,
        SOURCE_ATTEMPT_ID, TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        TARGET_B, AUTHORIZING_RELATIONSHIP);
    ProbeProvenance second = new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID, ITEM_A1,
        SOURCE_ATTEMPT_ID, TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        TARGET_A, AUTHORIZING_RELATIONSHIP);
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of(first, second));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("MAX_HYPOTHESIS_PROBES_PER_PACKET");
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

  @Test
  @DisplayName("fallback_reason alone diverges from persisted (both non-activated) -> INTEGRITY_FAILURE")
  void replay_fallbackReasonMismatch_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.STEP1_NOT_APPLICABLE)));
    stubAllScoresZeroFallback();

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("fallbackReason");
  }

  // -- M2-ADR-034 Amendment 4 correction round: blocker 1 -- snapshot_contract_version -----------

  @Test
  @DisplayName("unsupported snapshot_contract_version -> UNSUPPORTED_SNAPSHOT_VERSION, fails closed "
      + "before touching Step 1/Step 2 or any source-attempt/exposure discovery")
  void replay_unsupportedSnapshotContractVersion_failsClosedWithoutTouchingEngines() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(
        new DiagnosticSelectionReplayInput(REPLAY_INPUT_ID, DESTINATION_ATTEMPT_ID, SOURCE_ATTEMPT_ID,
            "DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V2", 2, 2, 2, 2, "APPLICABLE", "SCORABLE", true, null)));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.UNSUPPORTED_SNAPSHOT_VERSION);
    assertThat(result.integrityFailureDetail())
        .contains("DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V1")
        .contains("DIAGNOSTIC_SELECTION_V6_REPLAY_INPUT_V2");
    assertThat(result.recomputedDecision()).isNull();
    verify(repository, never()).findMostRecentCompletedAttempt(any(), any());
    verify(repository, never()).findAdaptiveEligibleItems(any());
    verify(repository, never()).findAdaptiveEligibleItemsForItemVersions(any());
    verify(replayInputRepository, never()).findCandidateProbes(any());
    verify(replayInputRepository, never()).findActionableHypotheses(any());
    verifyNoInteractions(assembler, uncertaintyCalculator, discriminationCalculator, probeRelationshipService,
        probeProvenanceRepository);
  }

  // -- M2-ADR-034 Amendment 4 correction round: blocker 3 -- destination attempt must be V6 -------

  @Test
  @DisplayName("non-V6 destination attempt, no snapshot -> NOT_AVAILABLE")
  void replay_nonV6AttemptWithNoSnapshot_returnsNotAvailable() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(repository.findSelectionPolicy(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of("DIAGNOSTIC_SELECTION_V5"));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.NOT_AVAILABLE);
    assertThat(result.recomputedDecision()).isNull();
  }

  @Test
  @DisplayName("non-V6 destination attempt with a V6 replay snapshot -> INTEGRITY_FAILURE "
      + "(a contradictory persisted state, never silently accepted as replayable)")
  void replay_nonV6AttemptWithV6Snapshot_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(repository.findSelectionPolicy(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of("DIAGNOSTIC_SELECTION_V5"));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, true, null)));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("selection_policy");
    assertThat(result.recomputedDecision()).isNull();
    verifyNoInteractions(assembler, uncertaintyCalculator, discriminationCalculator);
  }

  // -- M2-ADR-034 Amendment 4 correction round: blocker 2 -- fallback final-probe verification ----

  private void stubAllScoresZeroFallback() {
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
            new CandidateDiscrimination(ITEM_A1, HYPOTHESIS_A, BigDecimal.ZERO),
            new CandidateDiscrimination(ITEM_B1, HYPOTHESIS_B, BigDecimal.ZERO))));
    when(replayInputRepository.findCandidateProbes(REPLAY_INPUT_ID)).thenReturn(List.of(
        new CandidateProbe(ITEM_A1, HYPOTHESIS_A, true), new CandidateProbe(ITEM_B1, HYPOTHESIS_B, true)));
    when(replayInputRepository.findActionableHypotheses(REPLAY_INPUT_ID))
        .thenReturn(List.of(HYPOTHESIS_A, HYPOTHESIS_B));
    lenient().when(repository.findAdaptiveEligibleItemsForItemVersions(any())).thenReturn(
        List.of(eligibleItem(ITEM_A1, "SKILL_A"), eligibleItem(ITEM_B1, "SKILL_B")));
  }

  @Test
  @DisplayName("V6 fallback (ALL_SCORES_ZERO) with V5's own final selected probe already recorded -> "
      + "VERIFIED, without re-running V5's own discovery (M2-ADR-034 Amendment 4 sec S / golden "
      + "scenario A4-5)")
  void replay_fallbackWithFinalProbeAlreadyRecorded_returnsVerified() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.ALL_SCORES_ZERO)));
    stubAllScoresZeroFallback();
    ProbeProvenance v5Selection = new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID, ITEM_A1,
        SOURCE_ATTEMPT_ID, TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        TARGET_A, AUTHORIZING_RELATIONSHIP);
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of(v5Selection));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.VERIFIED);
    assertThat(result.recomputedDecision().activated()).isFalse();
    assertThat(result.recomputedDecision().fallbackReason()).isEqualTo(V6FallbackReason.ALL_SCORES_ZERO);
    assertThat(result.verifiedProbeProvenance()).isEqualTo(v5Selection);
    verify(probeRelationshipService, never()).resolve(any(), any(), any());
  }

  @Test
  @DisplayName("fallback decision that could not possibly have produced a probe (no source attempt), "
      + "but provenance records one anyway -> INTEGRITY_FAILURE, never re-derived from V5's own "
      + "discovery to decide whether it 'should' be there")
  void replay_fallbackWithImpossibleProbeRecorded_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(noSourceAttemptHeader()));
    ProbeProvenance impossible = new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID, ITEM_A1,
        SOURCE_ATTEMPT_ID, TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        TARGET_A, AUTHORIZING_RELATIONSHIP);
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of(impossible));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("could not have produced a hypothesis-driven probe");
  }

  @Test
  @DisplayName("fallback decision with a real source attempt but zero actionable hypotheses, and "
      + "provenance records a probe anyway -> INTEGRITY_FAILURE")
  void replay_fallbackWithNoActionableHypothesesButProvenanceRecorded_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(
        header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.NO_ACTIONABLE_HYPOTHESES, 0, 0, 0, 0, null, null)));
    when(replayInputRepository.findCandidateProbes(REPLAY_INPUT_ID)).thenReturn(List.of());
    when(replayInputRepository.findActionableHypotheses(REPLAY_INPUT_ID)).thenReturn(List.of());
    ProbeProvenance impossible = new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID, ITEM_A1,
        SOURCE_ATTEMPT_ID, TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        TARGET_A, AUTHORIZING_RELATIONSHIP);
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of(impossible));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("could not have produced a hypothesis-driven probe");
  }

  /**
   * Deliberately NOT the review-suggested "missing fallback provenance is always INTEGRITY_FAILURE"
   * rule. Reading the live V5 path ({@code DiagnosticService.resolveHypothesisProbeSelection} +
   * {@code HypothesisDrivenProbeDiagnosticSelector.adjustForHypothesisProbe}) shows a real, code-level
   * reason a fallback with actionable candidates can legitimately leave no
   * {@code core.diagnostic_probe_provenance} row: {@code adjustForHypothesisProbe}'s own doc records
   * that V3's mastery-band cap can exclude the chosen candidate from the packet entirely ("that skill
   * simply contributes nothing this round"), and {@code DiagnosticService} only writes provenance
   * when the chosen item actually lands in the assembled packet. Whether that band-cap exclusion
   * applied at decision time is a fact about historical mastery/evidence state the Amendment 4
   * snapshot deliberately never persists, so it is not reconstructable at replay time -- treating
   * absence here as corruption would be a false positive on legitimate historical data, not a real
   * integrity check.
   */
  @Test
  @DisplayName("V6 fallback with actionable candidates but no recorded final probe -> still VERIFIED "
      + "(a legitimate V3 band-cap exclusion, not reconstructable from the persisted snapshot)")
  void replay_fallbackWithNoRecordedFinalProbe_isStillVerified() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.ALL_SCORES_ZERO)));
    stubAllScoresZeroFallback();
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of());

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.VERIFIED);
    assertThat(result.verifiedProbeProvenance()).isNull();
  }

  @Test
  @DisplayName("fallback provenance source_attempt_id diverges from the persisted snapshot's "
      + "sourceAttemptId -> INTEGRITY_FAILURE")
  void replay_fallbackProvenanceSourceAttemptMismatch_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.ALL_SCORES_ZERO)));
    stubAllScoresZeroFallback();
    UUID unrelatedSourceAttempt = UUID.fromString("01900000-0000-7000-8000-0000000a9999");
    ProbeProvenance wrongSource = new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID, ITEM_A1,
        unrelatedSourceAttempt, TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        TARGET_A, AUTHORIZING_RELATIONSHIP);
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of(wrongSource));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("source_attempt_id");
  }

  @Test
  @DisplayName("fallback provenance item is unrelated to every persisted candidate, and V6's own "
      + "working-set walk completed without hitting MAX_AUTHORIZED_HYPOTHESES_V6 -> INTEGRITY_FAILURE")
  void replay_fallbackProvenanceItemNotInSnapshot_capNotHit_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.ALL_SCORES_ZERO)));
    stubAllScoresZeroFallback();
    UUID unrelatedItem = UUID.fromString("01900000-0000-7000-8000-0000000a5099");
    UUID unrelatedTargetObjective = UUID.fromString("01900000-0000-7000-8000-0000000a3099");
    ProbeProvenance unrelated = new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID, unrelatedItem,
        SOURCE_ATTEMPT_ID, TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        unrelatedTargetObjective, AUTHORIZING_RELATIONSHIP);
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of(unrelated));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail())
        .contains("does not match any persisted candidate probe")
        .contains("MAX_AUTHORIZED_HYPOTHESES_V6");
  }

  @Test
  @DisplayName("fallback provenance hypothesis identity (relationshipType) diverges from the "
      + "matching item's own persisted candidate -> INTEGRITY_FAILURE")
  void replay_fallbackProvenanceHypothesisIdentityMismatch_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.ALL_SCORES_ZERO)));
    stubAllScoresZeroFallback();
    // Same item (ITEM_A1) and source attempt as the real persisted CandidateProbe(ITEM_A1,
    // HYPOTHESIS_A, ...), but a different relationshipType -- the one field that alone makes this
    // not the same hypothesis identity.
    ProbeProvenance wrongRelationshipType = new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID,
        ITEM_A1, SOURCE_ATTEMPT_ID, TRIGGER_ITEM, TRIGGER_OBJECTIVE,
        ProbeRelationshipType.CONTRADICTION_CHECK, TARGET_A, AUTHORIZING_RELATIONSHIP);
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of(wrongRelationshipType));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("does not match any persisted candidate probe");
  }

  @Test
  @DisplayName("fallback provenance item not in the persisted snapshot IS accepted when V6's own "
      + "working-set walk hit MAX_AUTHORIZED_HYPOTHESES_V6 -- V5's own uncapped walk can legitimately "
      + "reach a candidate V6's own snapshot never recorded")
  void replay_fallbackProvenanceItemNotInSnapshot_capHit_isStillVerified() {
    UUID targetC = UUID.fromString("01900000-0000-7000-8000-0000000a3003");
    UUID targetD = UUID.fromString("01900000-0000-7000-8000-0000000a3004");
    UUID itemC1 = UUID.fromString("01900000-0000-7000-8000-0000000a5003");
    UUID itemD1 = UUID.fromString("01900000-0000-7000-8000-0000000a5004");
    DiagnosticHypothesis hypothesisC = new DiagnosticHypothesis(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE, targetC, AUTHORIZING_RELATIONSHIP);
    DiagnosticHypothesis hypothesisD = new DiagnosticHypothesis(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE, targetD, AUTHORIZING_RELATIONSHIP);

    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    // actionableHypothesisCount == MAX_AUTHORIZED_HYPOTHESES_V6 (4): V6's own walk stopped early.
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(
        header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.ALL_SCORES_ZERO, 4, 4, 4, 4, "APPLICABLE", "SCORABLE")));
    HypothesisUncertaintyContext context =
        new HypothesisUncertaintyContext(SOURCE_ATTEMPT_ID, "KAFKA", List.of(), List.of());
    when(assembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(context);
    when(uncertaintyCalculator.calculate(context)).thenReturn(new HypothesisUncertaintyResult(
        "HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.APPLICABLE,
        List.of(
            new CandidateUncertainty(HYPOTHESIS_A, DiagnosticConfidenceBand.MODERATE, true, new BigDecimal("0.2500")),
            new CandidateUncertainty(HYPOTHESIS_B, DiagnosticConfidenceBand.MODERATE, true, new BigDecimal("0.2500")),
            new CandidateUncertainty(hypothesisC, DiagnosticConfidenceBand.MODERATE, true, new BigDecimal("0.2500")),
            new CandidateUncertainty(hypothesisD, DiagnosticConfidenceBand.MODERATE, true, new BigDecimal("0.2500")))));
    when(discriminationCalculator.calculate(any())).thenReturn(new HypothesisDiscriminationResult(
        "HYPOTHESIS_DISCRIMINATION_V1", HypothesisDiscriminationStatus.SCORABLE,
        List.of(
            new CandidateDiscrimination(ITEM_A1, HYPOTHESIS_A, BigDecimal.ZERO),
            new CandidateDiscrimination(ITEM_B1, HYPOTHESIS_B, BigDecimal.ZERO),
            new CandidateDiscrimination(itemC1, hypothesisC, BigDecimal.ZERO),
            new CandidateDiscrimination(itemD1, hypothesisD, BigDecimal.ZERO))));
    when(replayInputRepository.findCandidateProbes(REPLAY_INPUT_ID)).thenReturn(List.of(
        new CandidateProbe(ITEM_A1, HYPOTHESIS_A, true), new CandidateProbe(ITEM_B1, HYPOTHESIS_B, true),
        new CandidateProbe(itemC1, hypothesisC, true), new CandidateProbe(itemD1, hypothesisD, true)));
    when(replayInputRepository.findActionableHypotheses(REPLAY_INPUT_ID))
        .thenReturn(List.of(HYPOTHESIS_A, HYPOTHESIS_B, hypothesisC, hypothesisD));
    lenient().when(repository.findAdaptiveEligibleItemsForItemVersions(any())).thenReturn(List.of(
        eligibleItem(ITEM_A1, "SKILL_A"), eligibleItem(ITEM_B1, "SKILL_B"),
        eligibleItem(itemC1, "SKILL_C"), eligibleItem(itemD1, "SKILL_D")));
    // V5's own uncapped walk reached a fifth (miss, type) pair V6 never got to -- a real item,
    // unrelated to any of the four persisted candidates above.
    UUID fifthItem = UUID.fromString("01900000-0000-7000-8000-0000000a5005");
    UUID fifthTargetObjective = UUID.fromString("01900000-0000-7000-8000-0000000a3005");
    ProbeProvenance beyondTheCap = new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID, fifthItem,
        SOURCE_ATTEMPT_ID, TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.SAME_OBJECTIVE_CONFIRMATION,
        fifthTargetObjective, null);
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of(beyondTheCap));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.VERIFIED);
    assertThat(result.verifiedProbeProvenance()).isEqualTo(beyondTheCap);
  }

  @Test
  @DisplayName("V6-activated provenance matches itemVersionId but diverges on relationshipType -> "
      + "INTEGRITY_FAILURE (a matching item id alone is insufficient historical integrity)")
  void replay_activatedProvenanceFullIdentityMismatch_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, true, null)));
    stubGenuineTieActivation();
    // Same winning item (ITEM_B1) and source attempt, but a relationshipType that does not match
    // HYPOTHESIS_B's own ROOT_CAUSE_PROBE.
    ProbeProvenance wrongRelationshipType = new ProbeProvenance(UUID.randomUUID(), DESTINATION_ATTEMPT_ID,
        ITEM_B1, SOURCE_ATTEMPT_ID, TRIGGER_ITEM, TRIGGER_OBJECTIVE,
        ProbeRelationshipType.PREREQUISITE_VALIDATION, TARGET_B, AUTHORIZING_RELATIONSHIP);
    when(probeProvenanceRepository.findByAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(List.of(wrongRelationshipType));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("no matching core.diagnostic_probe_provenance row");
  }

  // -- Additional review hardening: persisted audit counts/statuses are integrity-checked too -----

  @Test
  @DisplayName("persisted candidate_probe_count does not match actual candidate rows -> "
      + "INTEGRITY_FAILURE before Step 1/Step 2 ever run")
  void replay_candidateProbeCountMismatch_failsBeforeStep1Step2() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, true, null)));
    // Header claims 2 candidates; only 1 actual row is returned.
    when(replayInputRepository.findCandidateProbes(REPLAY_INPUT_ID))
        .thenReturn(List.of(new CandidateProbe(ITEM_A1, HYPOTHESIS_A, true)));
    when(replayInputRepository.findActionableHypotheses(REPLAY_INPUT_ID))
        .thenReturn(List.of(HYPOTHESIS_A, HYPOTHESIS_B));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("candidate_probe_count");
    assertThat(result.recomputedDecision()).isNull();
    verifyNoInteractions(assembler, uncertaintyCalculator, discriminationCalculator);
  }

  @Test
  @DisplayName("persisted actionable_hypothesis_count does not match the hypotheses reconstructed "
      + "from candidate rows -> INTEGRITY_FAILURE before Step 1/Step 2 ever run")
  void replay_actionableHypothesisCountMismatch_failsBeforeStep1Step2() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID))
        .thenReturn(Optional.of(header(SOURCE_ATTEMPT_ID, true, null)));
    when(replayInputRepository.findCandidateProbes(REPLAY_INPUT_ID)).thenReturn(List.of(
        new CandidateProbe(ITEM_A1, HYPOTHESIS_A, true), new CandidateProbe(ITEM_B1, HYPOTHESIS_B, true)));
    // Header claims 2 actionable hypotheses; only 1 distinct hypothesis is reconstructed.
    when(replayInputRepository.findActionableHypotheses(REPLAY_INPUT_ID)).thenReturn(List.of(HYPOTHESIS_A));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("actionable_hypothesis_count");
    assertThat(result.recomputedDecision()).isNull();
    verifyNoInteractions(assembler, uncertaintyCalculator, discriminationCalculator);
  }

  @Test
  @DisplayName("persisted participating_hypothesis_count diverges from the recomputed value -> "
      + "INTEGRITY_FAILURE even though activated/fallbackReason match")
  void replay_participatingHypothesisCountMismatch_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    // Real decision: only HYPOTHESIS_A participates (1), so it falls back to
    // FEWER_THAN_TWO_PARTICIPANTS -- but the header wrongly persists participating count 2.
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(
        header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.FEWER_THAN_TWO_PARTICIPANTS,
            2, 2, 2, 2, "APPLICABLE", null)));
    HypothesisUncertaintyContext context =
        new HypothesisUncertaintyContext(SOURCE_ATTEMPT_ID, "KAFKA", List.of(), List.of());
    when(assembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(context);
    when(uncertaintyCalculator.calculate(context)).thenReturn(new HypothesisUncertaintyResult(
        "HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.APPLICABLE,
        List.of(
            new CandidateUncertainty(HYPOTHESIS_A, DiagnosticConfidenceBand.HIGH, true, new BigDecimal("1.0000")),
            new CandidateUncertainty(HYPOTHESIS_B, DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE, false, null))));
    when(replayInputRepository.findCandidateProbes(REPLAY_INPUT_ID)).thenReturn(List.of(
        new CandidateProbe(ITEM_A1, HYPOTHESIS_A, true), new CandidateProbe(ITEM_B1, HYPOTHESIS_B, true)));
    when(replayInputRepository.findActionableHypotheses(REPLAY_INPUT_ID))
        .thenReturn(List.of(HYPOTHESIS_A, HYPOTHESIS_B));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("participatingHypothesisCount");
    verifyNoInteractions(discriminationCalculator);
  }

  @Test
  @DisplayName("persisted step1_status diverges from the recomputed value -> INTEGRITY_FAILURE even "
      + "though activated/fallbackReason match")
  void replay_step1StatusMismatch_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    // Real decision: HYPOTHESIS_UNCERTAINTY_V1 is genuinely NOT_APPLICABLE -- but the header wrongly
    // persists step1_status = APPLICABLE.
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(
        header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.STEP1_NOT_APPLICABLE,
            2, 2, 2, 0, "APPLICABLE", null)));
    HypothesisUncertaintyContext context =
        new HypothesisUncertaintyContext(SOURCE_ATTEMPT_ID, "KAFKA", List.of(), List.of());
    when(assembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(context);
    when(uncertaintyCalculator.calculate(context)).thenReturn(
        new HypothesisUncertaintyResult("HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.NOT_APPLICABLE, List.of()));
    when(replayInputRepository.findCandidateProbes(REPLAY_INPUT_ID)).thenReturn(List.of(
        new CandidateProbe(ITEM_A1, HYPOTHESIS_A, true), new CandidateProbe(ITEM_B1, HYPOTHESIS_B, true)));
    when(replayInputRepository.findActionableHypotheses(REPLAY_INPUT_ID))
        .thenReturn(List.of(HYPOTHESIS_A, HYPOTHESIS_B));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("step1Status");
    verifyNoInteractions(discriminationCalculator);
  }

  @Test
  @DisplayName("persisted step2_status diverges from the recomputed value -> INTEGRITY_FAILURE even "
      + "though activated/fallbackReason match")
  void replay_step2StatusMismatch_returnsIntegrityFailure() {
    when(repository.findAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(destinationAttempt));
    // Real decision: HYPOTHESIS_DISCRIMINATION_V1 is genuinely NOT_APPLICABLE -- but the header
    // wrongly persists step2_status = SCORABLE.
    when(replayInputRepository.findByDestinationAttempt(DESTINATION_ATTEMPT_ID)).thenReturn(Optional.of(
        header(SOURCE_ATTEMPT_ID, false, V6FallbackReason.STEP2_NOT_APPLICABLE,
            2, 2, 2, 2, "APPLICABLE", "SCORABLE")));
    HypothesisUncertaintyContext context =
        new HypothesisUncertaintyContext(SOURCE_ATTEMPT_ID, "KAFKA", List.of(), List.of());
    when(assembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(context);
    when(uncertaintyCalculator.calculate(context)).thenReturn(new HypothesisUncertaintyResult(
        "HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.APPLICABLE,
        List.of(
            new CandidateUncertainty(HYPOTHESIS_A, DiagnosticConfidenceBand.MODERATE, true, new BigDecimal("0.5000")),
            new CandidateUncertainty(HYPOTHESIS_B, DiagnosticConfidenceBand.MODERATE, true, new BigDecimal("0.5000")))));
    when(discriminationCalculator.calculate(any())).thenReturn(new HypothesisDiscriminationResult(
        "HYPOTHESIS_DISCRIMINATION_V1", HypothesisDiscriminationStatus.NOT_APPLICABLE, List.of()));
    when(replayInputRepository.findCandidateProbes(REPLAY_INPUT_ID)).thenReturn(List.of(
        new CandidateProbe(ITEM_A1, HYPOTHESIS_A, true), new CandidateProbe(ITEM_B1, HYPOTHESIS_B, true)));
    when(replayInputRepository.findActionableHypotheses(REPLAY_INPUT_ID))
        .thenReturn(List.of(HYPOTHESIS_A, HYPOTHESIS_B));

    DiagnosticSelectionV6ReplayResult result = service.replay(DESTINATION_ATTEMPT_ID);

    assertThat(result.status()).isEqualTo(DiagnosticSelectionV6ReplayStatus.INTEGRITY_FAILURE);
    assertThat(result.integrityFailureDetail()).contains("step2Status");
  }
}
