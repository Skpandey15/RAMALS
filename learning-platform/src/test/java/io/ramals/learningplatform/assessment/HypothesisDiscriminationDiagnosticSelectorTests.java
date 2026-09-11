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

import io.ramals.learningplatform.assessment.HypothesisDiscriminationDiagnosticSelector.Decision;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.CandidateDiscrimination;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.CandidateProbe;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationResult;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationStatus;
import io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationValidationException;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.CandidateHypothesis;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.CandidateUncertainty;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisEvidenceInput;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContext;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContextAssembler;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyResult;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyStatus;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code DIAGNOSTIC_SELECTION_V6} orchestration (M2-ADR-034 Amendment 3): every activation
 * condition, every {@link V6FallbackReason}, the frozen {@code MAX_AUTHORIZED_HYPOTHESES_V6} bound,
 * the misses x {@code RELATIONSHIP_TYPE_PRIORITY} enumeration order, and fail-closed propagation of
 * both frozen calculators' validation exceptions.
 *
 * <p>Step 1/Step 2 collaborators are mocked for every branch test so each {@link V6FallbackReason}
 * -- including two documented as structurally unreachable in production, retained here purely as
 * defense-in-depth coverage -- is reachable deterministically. The two fail-closed tests instead
 * wire the real, frozen {@code HYPOTHESIS_UNCERTAINTY_V1}/{@code HYPOTHESIS_DISCRIMINATION_V1}
 * calculators to prove genuine, uncaught propagation rather than a mocked throw.
 */
@ExtendWith(MockitoExtension.class)
class HypothesisDiscriminationDiagnosticSelectorTests {

  private static final UUID LEARNER_ID = UUID.fromString("01900000-0000-7000-8000-0000000e0001");
  private static final UUID ASSESSMENT_VERSION_ID = UUID.fromString("01900000-0000-7000-8000-0000000e0002");
  private static final UUID SOURCE_ATTEMPT_ID = UUID.fromString("01900000-0000-7000-8000-0000000e0003");
  private static final String DOMAIN = "KAFKA";

  private static final UUID TRIGGER_OBJECTIVE = UUID.fromString("01900000-0000-7000-8000-000000002000");
  private static final UUID TARGET_A = UUID.fromString("01900000-0000-7000-8000-000000003001");
  private static final UUID TARGET_B = UUID.fromString("01900000-0000-7000-8000-000000003002");
  private static final UUID TARGET_C = UUID.fromString("01900000-0000-7000-8000-000000003003");
  private static final UUID TARGET_D = UUID.fromString("01900000-0000-7000-8000-000000003004");
  private static final UUID TARGET_E = UUID.fromString("01900000-0000-7000-8000-000000003005");
  private static final UUID AUTHORIZING_RELATIONSHIP = UUID.fromString("01900000-0000-7000-8000-000000004000");

  private static final UUID MISS_1 = UUID.fromString("01900000-0000-7000-8000-000000006001");
  private static final UUID MISS_2 = UUID.fromString("01900000-0000-7000-8000-000000006002");
  private static final UUID MISS_3 = UUID.fromString("01900000-0000-7000-8000-000000006003");
  private static final UUID MISS_4 = UUID.fromString("01900000-0000-7000-8000-000000006004");
  private static final UUID MISS_5 = UUID.fromString("01900000-0000-7000-8000-000000006005");
  private static final UUID MISS_6 = UUID.fromString("01900000-0000-7000-8000-000000006006");
  private static final UUID MISS_7 = UUID.fromString("01900000-0000-7000-8000-000000006007");

  private static final UUID ITEM_A1 = UUID.fromString("01900000-0000-7000-8000-000000007001");
  private static final UUID ITEM_B1 = UUID.fromString("01900000-0000-7000-8000-000000007002");
  private static final UUID ITEM_C1 = UUID.fromString("01900000-0000-7000-8000-000000007003");
  private static final UUID ITEM_D1 = UUID.fromString("01900000-0000-7000-8000-000000007004");
  private static final UUID ITEM_X1 = UUID.fromString("01900000-0000-7000-8000-000000007099");

  /** Deliberately the lexicographically LARGEST probe id in this file, used opposite a hypothesis
   * whose canonical order sorts first -- the tie test proves canonical hypothesis order wins over
   * probe-id ordering, never the reverse. */
  private static final UUID ITEM_A1_LARGE = UUID.fromString("01900000-0000-7000-8000-ffffffffffff");
  /** Deliberately the lexicographically SMALLEST probe id, paired with the hypothesis that must
   * still lose the tie. */
  private static final UUID ITEM_B1_SMALL = UUID.fromString("01900000-0000-7000-8000-000000000001");

  private static final HypothesisUncertaintyContext DUMMY_CONTEXT =
      new HypothesisUncertaintyContext(SOURCE_ATTEMPT_ID, DOMAIN, List.of(), List.of());

  @Mock private AssessmentRepository repository;
  @Mock private ProbeRelationshipService probeRelationshipService;
  @Mock private HypothesisUncertaintyContextAssembler uncertaintyContextAssembler;
  @Mock private HypothesisUncertaintyCalculatorV1 uncertaintyCalculator;
  @Mock private HypothesisDiscriminationCalculatorV1 discriminationCalculator;

  private HypothesisDiscriminationDiagnosticSelector selector;

  private final ResolvedDiagnostic diagnostic =
      new ResolvedDiagnostic(ASSESSMENT_VERSION_ID, DOMAIN, "PROBE_CODE", "v1", "PUBLISHED");
  private final AssessmentAttempt sourceAttempt = new AssessmentAttempt(
      SOURCE_ATTEMPT_ID, LEARNER_ID, ASSESSMENT_VERSION_ID, "COMPLETED", "idem-key",
      Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

  @BeforeEach
  void setUp() {
    selector = new HypothesisDiscriminationDiagnosticSelector(
        repository, probeRelationshipService, uncertaintyContextAssembler, uncertaintyCalculator,
        discriminationCalculator);
    lenient().when(probeRelationshipService.resolve(any(), any(), eq(LEARNER_ID))).thenReturn(
        new ProbeResolution(ProbeResolutionOutcome.NO_RELATIONSHIP_DEFINED, null, List.of(), List.of()));
  }

  // -- fixtures -------------------------------------------------------------------------------

  private static DiagnosticHypothesis hypothesis(
      UUID triggerItem, ProbeRelationshipType type, UUID targetObjectiveId) {
    return new DiagnosticHypothesis(triggerItem, TRIGGER_OBJECTIVE, type, targetObjectiveId, AUTHORIZING_RELATIONSHIP);
  }

  private static ProbeResolution available(DiagnosticHypothesis hypothesis, UUID... itemVersionIds) {
    List<ProbeCandidateItem> candidates = new java.util.ArrayList<>();
    for (UUID itemVersionId : itemVersionIds) {
      candidates.add(new ProbeCandidateItem(itemVersionId, UUID.randomUUID()));
    }
    return new ProbeResolution(ProbeResolutionOutcome.CANDIDATES_AVAILABLE, hypothesis, candidates, List.of());
  }

  private static AdaptiveEligibleItem eligibleItem(UUID itemVersionId, String skillCode) {
    return new AdaptiveEligibleItem(
        itemVersionId, UUID.randomUUID(), UUID.randomUUID(), skillCode, "MULTIPLE_CHOICE", "MEDIUM");
  }

  private static HypothesisEvidenceInput evidenceFor(DiagnosticHypothesis h, HypothesisEvidenceOutcome outcome) {
    return new HypothesisEvidenceInput(UUID.randomUUID(), h, outcome, SOURCE_ATTEMPT_ID, DOMAIN);
  }

  private void stubSourceAttempt() {
    when(repository.findMostRecentCompletedAttempt(LEARNER_ID, ASSESSMENT_VERSION_ID))
        .thenReturn(Optional.of(sourceAttempt));
  }

  private void stubMisses(UUID... misses) {
    when(repository.findIncorrectItemVersionIdsInPresentationOrder(SOURCE_ATTEMPT_ID))
        .thenReturn(List.of(misses));
  }

  /** Two actionable hypotheses, one candidate probe each -- not the single-candidate-total case. */
  private DiagnosticHypothesis[] setUpTwoActionableHypotheses() {
    stubSourceAttempt();
    stubMisses(MISS_1, MISS_2);
    DiagnosticHypothesis hA = hypothesis(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_A);
    DiagnosticHypothesis hB = hypothesis(MISS_2, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_B);
    when(probeRelationshipService.resolve(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hA, ITEM_A1));
    when(probeRelationshipService.resolve(MISS_2, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hB, ITEM_B1));
    return new DiagnosticHypothesis[] {hA, hB};
  }

  private static final List<AdaptiveEligibleItem> TWO_ITEM_POOL =
      List.of(eligibleItem(ITEM_A1, "SKILL_A"), eligibleItem(ITEM_B1, "SKILL_B"));

  // -- activation condition 1: source attempt ---------------------------------------------------

  @Test
  @DisplayName("no source attempt -> falls back without touching relationship resolution")
  void noSourceAttempt_fallsBackWithoutTouchingRelationships() {
    when(repository.findMostRecentCompletedAttempt(LEARNER_ID, ASSESSMENT_VERSION_ID))
        .thenReturn(Optional.empty());

    Decision decision = selector.select(LEARNER_ID, diagnostic, List.of());

    assertThat(decision.activated()).isFalse();
    assertThat(decision.selection()).isEmpty();
    assertThat(decision.sourceAttemptId()).isNull();
    assertThat(decision.fallbackReason()).isEqualTo(V6FallbackReason.NO_SOURCE_ATTEMPT);
    assertThat(decision.relationshipAuthorizedHypothesisCount()).isZero();
    assertThat(decision.actionableHypothesisCount()).isZero();
    assertThat(decision.candidateProbeCount()).isZero();
    verifyNoInteractions(probeRelationshipService, uncertaintyContextAssembler, uncertaintyCalculator,
        discriminationCalculator);
  }

  // -- activation condition 2: actionable hypotheses ---------------------------------------------

  @Test
  @DisplayName("no relationship authorized at all -> falls back to NO_ACTIONABLE_HYPOTHESES")
  void noRelationshipAuthorized_fallsBackToNoActionableHypotheses() {
    stubSourceAttempt();
    stubMisses(MISS_1);

    Decision decision = selector.select(LEARNER_ID, diagnostic, List.of());

    assertThat(decision.activated()).isFalse();
    assertThat(decision.sourceAttemptId()).isEqualTo(SOURCE_ATTEMPT_ID);
    assertThat(decision.fallbackReason()).isEqualTo(V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);
    assertThat(decision.relationshipAuthorizedHypothesisCount()).isZero();
    assertThat(decision.actionableHypothesisCount()).isZero();
    verifyNoInteractions(uncertaintyContextAssembler, uncertaintyCalculator, discriminationCalculator);
  }

  @Test
  @DisplayName("relationship authorized but no candidate survives destination eligibility -> "
      + "falls back to NO_ACTIONABLE_HYPOTHESES, relationship authorization unaffected")
  void relationshipAuthorizedButNotDestinationEligible_fallsBackToNoActionableHypotheses() {
    stubSourceAttempt();
    stubMisses(MISS_1);
    DiagnosticHypothesis hA = hypothesis(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_A);
    when(probeRelationshipService.resolve(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hA, ITEM_A1));
    // ITEM_A1 is authorized but not in the destination's unseen pool.
    List<AdaptiveEligibleItem> poolWithoutTheCandidate = List.of(eligibleItem(ITEM_B1, "SKILL_B"));

    Decision decision = selector.select(LEARNER_ID, diagnostic, poolWithoutTheCandidate);

    assertThat(decision.activated()).isFalse();
    assertThat(decision.fallbackReason()).isEqualTo(V6FallbackReason.NO_ACTIONABLE_HYPOTHESES);
    assertThat(decision.relationshipAuthorizedHypothesisCount()).isEqualTo(1);
    assertThat(decision.actionableHypothesisCount()).isZero();
  }

  // -- amendment 3 sec P: single-candidate-total optimization -------------------------------------

  @Test
  @DisplayName("exactly one candidate probe across the whole working set -> falls back to "
      + "SINGLE_CANDIDATE_TOTAL without invoking Step 1 or Step 2")
  void exactlyOneCandidateAcrossWholeWorkingSet_fallsBackWithoutInvokingStep1Or2() {
    stubSourceAttempt();
    stubMisses(MISS_1);
    DiagnosticHypothesis hA = hypothesis(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_A);
    when(probeRelationshipService.resolve(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hA, ITEM_A1));

    Decision decision = selector.select(LEARNER_ID, diagnostic, List.of(eligibleItem(ITEM_A1, "SKILL_A")));

    assertThat(decision.activated()).isFalse();
    assertThat(decision.fallbackReason()).isEqualTo(V6FallbackReason.SINGLE_CANDIDATE_TOTAL);
    assertThat(decision.actionableHypothesisCount()).isEqualTo(1);
    assertThat(decision.candidateProbeCount()).isEqualTo(1);
    assertThat(decision.step1Status()).isNull();
    assertThat(decision.step2Status()).isNull();
    verifyNoInteractions(uncertaintyContextAssembler, uncertaintyCalculator, discriminationCalculator);
  }

  @Test
  @DisplayName("one candidate within one hypothesis among several -> NOT SINGLE_CANDIDATE_TOTAL, "
      + "proceeds into Step 1")
  void oneCandidateWithinOneHypothesisButMultipleHypothesesTotal_proceedsPastSingleCandidateOptimization() {
    stubSourceAttempt();
    stubMisses(MISS_1, MISS_2);
    DiagnosticHypothesis hA = hypothesis(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_A);
    DiagnosticHypothesis hB = hypothesis(MISS_2, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_B);
    when(probeRelationshipService.resolve(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hA, ITEM_A1));
    when(probeRelationshipService.resolve(MISS_2, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hB, ITEM_B1, ITEM_C1));
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(DUMMY_CONTEXT);
    when(uncertaintyCalculator.calculate(DUMMY_CONTEXT)).thenReturn(
        new HypothesisUncertaintyResult("HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.NOT_APPLICABLE, List.of()));
    List<AdaptiveEligibleItem> pool = List.of(
        eligibleItem(ITEM_A1, "SKILL_A"), eligibleItem(ITEM_B1, "SKILL_B"), eligibleItem(ITEM_C1, "SKILL_B"));

    Decision decision = selector.select(LEARNER_ID, diagnostic, pool);

    assertThat(decision.fallbackReason()).isEqualTo(V6FallbackReason.STEP1_NOT_APPLICABLE);
    assertThat(decision.actionableHypothesisCount()).isEqualTo(2);
    assertThat(decision.candidateProbeCount()).isEqualTo(3);
    verify(uncertaintyContextAssembler).assemble(eq(SOURCE_ATTEMPT_ID), any());
  }

  // -- Step-1 fallback semantics ------------------------------------------------------------------

  @Test
  @DisplayName("HYPOTHESIS_UNCERTAINTY_V1 NOT_APPLICABLE -> falls back, Step 2 never invoked")
  void step1NotApplicable_fallsBack() {
    setUpTwoActionableHypotheses();
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(DUMMY_CONTEXT);
    when(uncertaintyCalculator.calculate(DUMMY_CONTEXT)).thenReturn(
        new HypothesisUncertaintyResult("HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.NOT_APPLICABLE, List.of()));

    Decision decision = selector.select(LEARNER_ID, diagnostic, TWO_ITEM_POOL);

    assertThat(decision.activated()).isFalse();
    assertThat(decision.fallbackReason()).isEqualTo(V6FallbackReason.STEP1_NOT_APPLICABLE);
    assertThat(decision.step1Status()).isEqualTo("NOT_APPLICABLE");
    assertThat(decision.step2Status()).isNull();
    verifyNoInteractions(discriminationCalculator);
  }

  @Test
  @DisplayName("HYPOTHESIS_UNCERTAINTY_V1 INSUFFICIENT_EVIDENCE -> falls back, Step 2 never invoked")
  void step1InsufficientEvidence_fallsBack() {
    DiagnosticHypothesis[] hypotheses = setUpTwoActionableHypotheses();
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(DUMMY_CONTEXT);
    when(uncertaintyCalculator.calculate(DUMMY_CONTEXT)).thenReturn(new HypothesisUncertaintyResult(
        "HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.INSUFFICIENT_EVIDENCE,
        List.of(
            new CandidateUncertainty(hypotheses[0], DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE, false, null),
            new CandidateUncertainty(hypotheses[1], DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE, false, null))));

    Decision decision = selector.select(LEARNER_ID, diagnostic, TWO_ITEM_POOL);

    assertThat(decision.activated()).isFalse();
    assertThat(decision.fallbackReason()).isEqualTo(V6FallbackReason.STEP1_INSUFFICIENT_EVIDENCE);
    assertThat(decision.step1Status()).isEqualTo("INSUFFICIENT_EVIDENCE");
    verifyNoInteractions(discriminationCalculator);
  }

  @Test
  @DisplayName("fewer than two participating hypotheses -> falls back, Step 2 never invoked")
  void fewerThanTwoParticipatingHypotheses_fallsBackWithoutInvokingStep2() {
    DiagnosticHypothesis[] hypotheses = setUpTwoActionableHypotheses();
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(DUMMY_CONTEXT);
    when(uncertaintyCalculator.calculate(DUMMY_CONTEXT)).thenReturn(new HypothesisUncertaintyResult(
        "HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.APPLICABLE,
        List.of(
            new CandidateUncertainty(hypotheses[0], DiagnosticConfidenceBand.HIGH, true, new BigDecimal("1.0000")),
            new CandidateUncertainty(hypotheses[1], DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE, false, null))));

    Decision decision = selector.select(LEARNER_ID, diagnostic, TWO_ITEM_POOL);

    assertThat(decision.activated()).isFalse();
    assertThat(decision.fallbackReason()).isEqualTo(V6FallbackReason.FEWER_THAN_TWO_PARTICIPANTS);
    assertThat(decision.step1Status()).isEqualTo("APPLICABLE");
    assertThat(decision.participatingHypothesisCount()).isEqualTo(1);
    verifyNoInteractions(discriminationCalculator);
  }

  // -- Step-2 fallback semantics --------------------------------------------------------------

  private HypothesisUncertaintyResult twoParticipantsApplicable(DiagnosticHypothesis[] hypotheses) {
    return new HypothesisUncertaintyResult(
        "HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.APPLICABLE,
        List.of(
            new CandidateUncertainty(hypotheses[0], DiagnosticConfidenceBand.MODERATE, true, new BigDecimal("0.5000")),
            new CandidateUncertainty(hypotheses[1], DiagnosticConfidenceBand.MODERATE, true, new BigDecimal("0.5000"))));
  }

  @Test
  @DisplayName("HYPOTHESIS_DISCRIMINATION_V1 NOT_APPLICABLE -> falls back (defense-in-depth)")
  void step2NotApplicable_fallsBack() {
    DiagnosticHypothesis[] hypotheses = setUpTwoActionableHypotheses();
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(DUMMY_CONTEXT);
    when(uncertaintyCalculator.calculate(DUMMY_CONTEXT)).thenReturn(twoParticipantsApplicable(hypotheses));
    when(discriminationCalculator.calculate(any())).thenReturn(
        new HypothesisDiscriminationResult("HYPOTHESIS_DISCRIMINATION_V1", HypothesisDiscriminationStatus.NOT_APPLICABLE, List.of()));

    Decision decision = selector.select(LEARNER_ID, diagnostic, TWO_ITEM_POOL);

    assertThat(decision.activated()).isFalse();
    assertThat(decision.fallbackReason()).isEqualTo(V6FallbackReason.STEP2_NOT_APPLICABLE);
    assertThat(decision.step2Status()).isEqualTo("NOT_APPLICABLE");
    assertThat(decision.maxDiscriminationScore()).isNull();
  }

  @Test
  @DisplayName("every candidate probe scores 0.0000 -> falls back to ALL_SCORES_ZERO, not an error")
  void allCandidateScoresZero_fallsBack() {
    DiagnosticHypothesis[] hypotheses = setUpTwoActionableHypotheses();
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(DUMMY_CONTEXT);
    when(uncertaintyCalculator.calculate(DUMMY_CONTEXT)).thenReturn(twoParticipantsApplicable(hypotheses));
    when(discriminationCalculator.calculate(any())).thenReturn(new HypothesisDiscriminationResult(
        "HYPOTHESIS_DISCRIMINATION_V1", HypothesisDiscriminationStatus.SCORABLE,
        List.of(
            new CandidateDiscrimination(ITEM_A1, hypotheses[0], BigDecimal.ZERO),
            new CandidateDiscrimination(ITEM_B1, hypotheses[1], BigDecimal.ZERO))));

    Decision decision = selector.select(LEARNER_ID, diagnostic, TWO_ITEM_POOL);

    assertThat(decision.activated()).isFalse();
    assertThat(decision.fallbackReason()).isEqualTo(V6FallbackReason.ALL_SCORES_ZERO);
    assertThat(decision.maxDiscriminationScore()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  // -- positive activation ----------------------------------------------------------------------

  @Test
  @DisplayName("positive maxScore -> activates and selects the highest-scoring probe")
  void positiveActivation_selectsHighestScoringProbe() {
    DiagnosticHypothesis[] hypotheses = setUpTwoActionableHypotheses();
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(DUMMY_CONTEXT);
    when(uncertaintyCalculator.calculate(DUMMY_CONTEXT)).thenReturn(twoParticipantsApplicable(hypotheses));
    when(discriminationCalculator.calculate(any())).thenReturn(new HypothesisDiscriminationResult(
        "HYPOTHESIS_DISCRIMINATION_V1", HypothesisDiscriminationStatus.SCORABLE,
        List.of(
            new CandidateDiscrimination(ITEM_A1, hypotheses[0], new BigDecimal("0.3000")),
            new CandidateDiscrimination(ITEM_B1, hypotheses[1], new BigDecimal("0.7000")))));

    Decision decision = selector.select(LEARNER_ID, diagnostic, TWO_ITEM_POOL);

    assertThat(decision.activated()).isTrue();
    assertThat(decision.fallbackReason()).isNull();
    assertThat(decision.maxDiscriminationScore()).isEqualByComparingTo("0.7000");
    assertThat(decision.selection()).isPresent();
    HypothesisDrivenProbeDiagnosticSelector.Selection selection = decision.selection().orElseThrow();
    assertThat(selection.hypothesis()).isEqualTo(hypotheses[1]);
    assertThat(selection.chosenItemVersionId()).isEqualTo(ITEM_B1);
    assertThat(selection.sourceAttemptId()).isEqualTo(SOURCE_ATTEMPT_ID);
    assertThat(selection.targetSkillCode()).isEqualTo("SKILL_B");
  }

  @Test
  @DisplayName("tied top score -> resolves by hypothesis canonical order, never by probe id")
  void positiveTie_resolvesByHypothesisCanonicalOrderNotProbeId() {
    stubSourceAttempt();
    stubMisses(MISS_1, MISS_2);
    DiagnosticHypothesis hA = hypothesis(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_A);
    DiagnosticHypothesis hB = hypothesis(MISS_2, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_B);
    when(probeRelationshipService.resolve(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hA, ITEM_A1_LARGE));
    when(probeRelationshipService.resolve(MISS_2, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hB, ITEM_B1_SMALL));
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(DUMMY_CONTEXT);
    when(uncertaintyCalculator.calculate(DUMMY_CONTEXT)).thenReturn(twoParticipantsApplicable(new DiagnosticHypothesis[] {hA, hB}));
    when(discriminationCalculator.calculate(any())).thenReturn(new HypothesisDiscriminationResult(
        "HYPOTHESIS_DISCRIMINATION_V1", HypothesisDiscriminationStatus.SCORABLE,
        List.of(
            new CandidateDiscrimination(ITEM_A1_LARGE, hA, new BigDecimal("0.5000")),
            new CandidateDiscrimination(ITEM_B1_SMALL, hB, new BigDecimal("0.5000")))));
    List<AdaptiveEligibleItem> pool = List.of(
        eligibleItem(ITEM_A1_LARGE, "SKILL_A"), eligibleItem(ITEM_B1_SMALL, "SKILL_B"));

    Decision decision = selector.select(LEARNER_ID, diagnostic, pool);

    assertThat(decision.activated()).isTrue();
    // TARGET_A precedes TARGET_B in canonical order; the probe id ordering (B < A) must not win.
    assertThat(decision.selection().orElseThrow().hypothesis()).isEqualTo(hA);
    assertThat(decision.selection().orElseThrow().chosenItemVersionId()).isEqualTo(ITEM_A1_LARGE);
  }

  // -- Amendment 3 sec E/F/H/I: bound enforcement, dedup, actionability --------------------------

  @Test
  @DisplayName("bound enforcement: stops at 4 actionable hypotheses; exact-identity duplicate and "
      + "non-actionable relationship authorization consume no slot")
  void boundEnforcement_stopsAtFourActionableHypotheses_duplicateAndNonActionableDoNotConsumeSlots() {
    stubSourceAttempt();
    stubMisses(MISS_1, MISS_2, MISS_3, MISS_4, MISS_5, MISS_6, MISS_7);

    DiagnosticHypothesis hA = hypothesis(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_A);
    DiagnosticHypothesis hB = hypothesis(MISS_3, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_B);
    DiagnosticHypothesis hNonActionable = hypothesis(MISS_4, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_C);
    DiagnosticHypothesis hC = hypothesis(MISS_5, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_D);
    DiagnosticHypothesis hD = hypothesis(MISS_6, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_E);

    when(probeRelationshipService.resolve(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hA, ITEM_A1));
    // MISS_2 resolves to the exact same hypothesis tuple as MISS_1 -- an exact-identity duplicate.
    when(probeRelationshipService.resolve(MISS_2, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hA, ITEM_A1));
    when(probeRelationshipService.resolve(MISS_3, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hB, ITEM_B1));
    // MISS_4's candidate is authorized but not in the destination pool -- non-actionable.
    when(probeRelationshipService.resolve(MISS_4, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hNonActionable, ITEM_X1));
    when(probeRelationshipService.resolve(MISS_5, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hC, ITEM_C1));
    when(probeRelationshipService.resolve(MISS_6, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hD, ITEM_D1));
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(DUMMY_CONTEXT);
    when(uncertaintyCalculator.calculate(DUMMY_CONTEXT)).thenReturn(
        new HypothesisUncertaintyResult("HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.NOT_APPLICABLE, List.of()));

    List<AdaptiveEligibleItem> pool = List.of(
        eligibleItem(ITEM_A1, "SKILL_A"), eligibleItem(ITEM_B1, "SKILL_B"),
        eligibleItem(ITEM_C1, "SKILL_C"), eligibleItem(ITEM_D1, "SKILL_D"));
        // ITEM_X1 deliberately absent from the pool.

    Decision decision = selector.select(LEARNER_ID, diagnostic, pool);

    assertThat(decision.actionableHypothesisCount())
        .as("exactly MAX_AUTHORIZED_HYPOTHESES_V6 actionable hypotheses admitted")
        .isEqualTo(HypothesisDiscriminationDiagnosticSelector.MAX_AUTHORIZED_HYPOTHESES_V6)
        .isEqualTo(4);
    assertThat(decision.relationshipAuthorizedHypothesisCount())
        .as("every CANDIDATES_AVAILABLE resolution counts, duplicate and non-actionable included")
        .isEqualTo(6);
    assertThat(decision.candidateProbeCount())
        .as("hNonActionable contributes no candidate probes")
        .isEqualTo(4);
    verify(probeRelationshipService, never()).resolve(eq(MISS_7), any(), any());
  }

  @Test
  @DisplayName("enumeration order: misses outer, RELATIONSHIP_TYPE_PRIORITY inner")
  void enumerationOrder_missesOuterThenRelationshipTypePriorityInner() {
    stubSourceAttempt();
    stubMisses(MISS_1, MISS_2);
    DiagnosticHypothesis hRoot = hypothesis(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_A);
    DiagnosticHypothesis hContra = hypothesis(MISS_1, ProbeRelationshipType.CONTRADICTION_CHECK, TARGET_B);
    DiagnosticHypothesis hPrereq = hypothesis(MISS_2, ProbeRelationshipType.PREREQUISITE_VALIDATION, TARGET_C);

    when(probeRelationshipService.resolve(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, LEARNER_ID))
        .thenReturn(available(hRoot, ITEM_A1));
    when(probeRelationshipService.resolve(MISS_1, ProbeRelationshipType.CONTRADICTION_CHECK, LEARNER_ID))
        .thenReturn(available(hContra, ITEM_B1));
    when(probeRelationshipService.resolve(MISS_2, ProbeRelationshipType.PREREQUISITE_VALIDATION, LEARNER_ID))
        .thenReturn(available(hPrereq, ITEM_C1));
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(DUMMY_CONTEXT);
    when(uncertaintyCalculator.calculate(DUMMY_CONTEXT)).thenReturn(
        new HypothesisUncertaintyResult("HYPOTHESIS_UNCERTAINTY_V1", HypothesisUncertaintyStatus.NOT_APPLICABLE, List.of()));
    List<AdaptiveEligibleItem> pool = List.of(
        eligibleItem(ITEM_A1, "SKILL_A"), eligibleItem(ITEM_B1, "SKILL_B"), eligibleItem(ITEM_C1, "SKILL_C"));

    selector.select(LEARNER_ID, diagnostic, pool);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<DiagnosticHypothesis>> captor = ArgumentCaptor.forClass(List.class);
    verify(uncertaintyContextAssembler).assemble(eq(SOURCE_ATTEMPT_ID), captor.capture());
    assertThat(captor.getValue()).containsExactly(hRoot, hContra, hPrereq);
  }

  @Test
  @DisplayName("determinism: repeated invocation with identical inputs produces an equal Decision")
  void determinism_repeatedInvocationProducesEqualDecision() {
    DiagnosticHypothesis[] hypotheses = setUpTwoActionableHypotheses();
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(DUMMY_CONTEXT);
    when(uncertaintyCalculator.calculate(DUMMY_CONTEXT)).thenReturn(twoParticipantsApplicable(hypotheses));
    when(discriminationCalculator.calculate(any())).thenReturn(new HypothesisDiscriminationResult(
        "HYPOTHESIS_DISCRIMINATION_V1", HypothesisDiscriminationStatus.SCORABLE,
        List.of(
            new CandidateDiscrimination(ITEM_A1, hypotheses[0], new BigDecimal("0.3000")),
            new CandidateDiscrimination(ITEM_B1, hypotheses[1], new BigDecimal("0.7000")))));

    Decision first = selector.select(LEARNER_ID, diagnostic, TWO_ITEM_POOL);
    Decision second = selector.select(LEARNER_ID, diagnostic, TWO_ITEM_POOL);

    assertThat(first).isEqualTo(second);
  }

  // -- fail-closed validation: real, frozen calculators --------------------------------------------

  @Test
  @DisplayName("HypothesisUncertaintyValidationException propagates uncaught, never a fallback")
  void validationFailClosed_uncertaintyValidationExceptionPropagatesUncaught() {
    HypothesisUncertaintyCalculatorV1 realUncertainty =
        new HypothesisUncertaintyCalculatorV1(new DiagnosticConfidenceCalculatorV1());
    HypothesisDiscriminationCalculatorV1 realDiscrimination =
        new HypothesisDiscriminationCalculatorV1(realUncertainty);
    HypothesisDiscriminationDiagnosticSelector realSelector = new HypothesisDiscriminationDiagnosticSelector(
        repository, probeRelationshipService, uncertaintyContextAssembler, realUncertainty, realDiscrimination);
    DiagnosticHypothesis[] hypotheses = setUpTwoActionableHypotheses();

    UUID sharedObservationId = UUID.randomUUID();
    HypothesisUncertaintyContext malformedContext = new HypothesisUncertaintyContext(
        SOURCE_ATTEMPT_ID, DOMAIN,
        List.of(new CandidateHypothesis(hypotheses[0], DOMAIN), new CandidateHypothesis(hypotheses[1], DOMAIN)),
        List.of(
            new HypothesisEvidenceInput(sharedObservationId, hypotheses[0], HypothesisEvidenceOutcome.SUPPORTING,
                SOURCE_ATTEMPT_ID, DOMAIN),
            // Same observationId reused for a second, distinct observation -- refused, never
            // de-duplicated (M2-ADR-034 Amendment 1 sec R).
            new HypothesisEvidenceInput(sharedObservationId, hypotheses[1], HypothesisEvidenceOutcome.CONTRADICTORY,
                SOURCE_ATTEMPT_ID, DOMAIN)));
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(malformedContext);

    assertThatThrownBy(() -> realSelector.select(LEARNER_ID, diagnostic, TWO_ITEM_POOL))
        .isInstanceOf(HypothesisUncertaintyValidationException.class);
    verifyNoInteractions(discriminationCalculator);
  }

  @Test
  @DisplayName("HypothesisDiscriminationValidationException propagates uncaught, never a fallback")
  void validationFailClosed_discriminationValidationExceptionPropagatesUncaught() {
    HypothesisUncertaintyCalculatorV1 realUncertainty =
        new HypothesisUncertaintyCalculatorV1(new DiagnosticConfidenceCalculatorV1());
    HypothesisDiscriminationCalculatorV1 realDiscrimination =
        new HypothesisDiscriminationCalculatorV1(realUncertainty);
    HypothesisDiscriminationDiagnosticSelector realSelector = new HypothesisDiscriminationDiagnosticSelector(
        repository, probeRelationshipService, uncertaintyContextAssembler, realUncertainty, realDiscrimination);
    // The real working set is authorized for hA/hB (see setUpTwoActionableHypotheses), but the
    // mocked assembler substitutes an entirely different, mismatched candidate set below -- so the
    // candidate probes (still targeting hA/hB) are for hypotheses unknown to the base context.
    setUpTwoActionableHypotheses();

    DiagnosticHypothesis hZ1 = hypothesis(MISS_1, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_D);
    DiagnosticHypothesis hZ2 = hypothesis(MISS_2, ProbeRelationshipType.ROOT_CAUSE_PROBE, TARGET_E);
    HypothesisUncertaintyContext mismatchedContext = new HypothesisUncertaintyContext(
        SOURCE_ATTEMPT_ID, DOMAIN,
        List.of(new CandidateHypothesis(hZ1, DOMAIN), new CandidateHypothesis(hZ2, DOMAIN)),
        List.of(
            evidenceFor(hZ1, HypothesisEvidenceOutcome.SUPPORTING), evidenceFor(hZ1, HypothesisEvidenceOutcome.SUPPORTING),
            evidenceFor(hZ2, HypothesisEvidenceOutcome.SUPPORTING), evidenceFor(hZ2, HypothesisEvidenceOutcome.SUPPORTING)));
    when(uncertaintyContextAssembler.assemble(eq(SOURCE_ATTEMPT_ID), any())).thenReturn(mismatchedContext);

    assertThatThrownBy(() -> realSelector.select(LEARNER_ID, diagnostic, TWO_ITEM_POOL))
        .isInstanceOf(HypothesisDiscriminationValidationException.class);
  }
}
