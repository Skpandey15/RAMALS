package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.DOMAIN;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.INTERACTION_ID;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.OTHER_DOMAIN;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.candidate;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.evidence;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.ha;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.hb;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.hc;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.observations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.DiagnosticConfidenceBand;
import io.ramals.learningplatform.assessment.DiagnosticConfidenceCalculatorV1;
import io.ramals.learningplatform.assessment.DiagnosticConfidenceInputs;
import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome;
import io.ramals.learningplatform.assessment.ProbeRelationshipType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Core behaviour of {@code HYPOTHESIS_UNCERTAINTY_V1} beyond the golden vectors: determinism,
 * ordering independence, invariants, validation, and reuse fidelity against
 * {@link DiagnosticConfidenceCalculatorV1}.
 */
class HypothesisUncertaintyCalculatorV1Tests {

  private final HypothesisUncertaintyCalculatorV1 calculator =
      new HypothesisUncertaintyCalculatorV1(new DiagnosticConfidenceCalculatorV1());

  // -- determinism / ordering independence -----------------------------------------------------

  @Test
  @DisplayName("identical input produces an identical result on repeated invocation")
  void deterministicRepeatedInvocation() {
    HypothesisUncertaintyContext context = mixedContext();

    HypothesisUncertaintyResult first = calculator.calculate(context);
    HypothesisUncertaintyResult second = calculator.calculate(context);

    assertThat(first).isEqualTo(second);
  }

  @Test
  @DisplayName("permuting the candidate list does not change the result")
  void candidateInputPermutationInvariant() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    List<HypothesisEvidenceInput> evidenceList = allEvidenceFor(a, b, c);

    HypothesisUncertaintyResult inOrder = calculator.calculate(
        HypothesisUncertaintyTestFixtures.context(List.of(a, b, c), evidenceList));
    HypothesisUncertaintyResult reversed = calculator.calculate(
        HypothesisUncertaintyTestFixtures.context(List.of(c, b, a), evidenceList));

    assertThat(inOrder).isEqualTo(reversed);
  }

  @Test
  @DisplayName("permuting the evidence list does not change the result")
  void evidenceInputPermutationInvariant() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    List<HypothesisEvidenceInput> evidenceList = new ArrayList<>(allEvidenceFor(a, b));
    HypothesisUncertaintyResult inOrder = calculator.calculate(
        HypothesisUncertaintyTestFixtures.context(List.of(a, b), evidenceList));

    List<HypothesisEvidenceInput> shuffled = new ArrayList<>(evidenceList);
    Collections.shuffle(shuffled, new Random(42));
    HypothesisUncertaintyResult afterShuffle = calculator.calculate(
        HypothesisUncertaintyTestFixtures.context(List.of(a, b), shuffled));

    assertThat(inOrder).isEqualTo(afterShuffle);
  }

  // -- range / normalization invariants ----------------------------------------------------------

  @Test
  @DisplayName("every normalized value is within [0.0000, 1.0000]")
  void rangeInvariant() {
    HypothesisUncertaintyResult result = calculator.calculate(mixedContext());
    result.candidates().stream()
        .filter(CandidateUncertainty::participates)
        .forEach(candidate -> {
          assertThat(candidate.normalizedValue()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
          assertThat(candidate.normalizedValue()).isLessThanOrEqualTo(BigDecimal.ONE);
        });
  }

  @Test
  @DisplayName("APPLICABLE participating values sum to exactly 1.0000")
  void exactSumInvariant() {
    HypothesisUncertaintyResult result = calculator.calculate(mixedContext());
    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.APPLICABLE);
    BigDecimal sum = result.candidates().stream()
        .filter(CandidateUncertainty::participates)
        .map(CandidateUncertainty::normalizedValue)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo(new BigDecimal("1.0000"));
  }

  @Test
  @DisplayName("all participating LOW: equal weights, near-uniform after Hamilton residual, sum exactly 1.0000")
  void allParticipatingLowIsNearUniform() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    List<HypothesisEvidenceInput> evidenceList = new ArrayList<>();
    evidenceList.addAll(observations(a, 1, 0, 0));
    evidenceList.addAll(observations(b, 1, 0, 0));
    evidenceList.addAll(observations(c, 1, 0, 0));

    HypothesisUncertaintyResult result =
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(a, b, c), evidenceList));

    result.candidates().forEach(candidate -> assertThat(candidate.band()).isEqualTo(DiagnosticConfidenceBand.LOW));
    // 1/3 exactly cannot be represented at scale 4 for all three and still sum to 1.0000: the
    // three-way remainder tie is broken by canonical order (Ha first), so Ha alone receives the
    // one 0.0001 residual unit.
    assertThat(result.candidates().get(0).normalizedValue()).isEqualByComparingTo(new BigDecimal("0.3334"));
    assertThat(result.candidates().get(1).normalizedValue()).isEqualByComparingTo(new BigDecimal("0.3333"));
    assertThat(result.candidates().get(2).normalizedValue()).isEqualByComparingTo(new BigDecimal("0.3333"));
    assertSumIsExactlyOne(result);
  }

  private static void assertSumIsExactlyOne(HypothesisUncertaintyResult result) {
    BigDecimal sum = result.candidates().stream()
        .filter(CandidateUncertainty::participates)
        .map(CandidateUncertainty::normalizedValue)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo(new BigDecimal("1.0000"));
  }

  @Test
  @DisplayName("mixed LOW/MODERATE/HIGH: higher band never has a smaller normalized value")
  void mixedBandsMonotonicity() {
    HypothesisUncertaintyResult result = calculator.calculate(mixedContext());
    BigDecimal low = valueOf(result, DiagnosticConfidenceBand.LOW);
    BigDecimal high = valueOf(result, DiagnosticConfidenceBand.HIGH);
    assertThat(high).isGreaterThan(low);
  }

  // -- Hamilton residual allocation ----------------------------------------------------------------

  @Test
  @DisplayName("Hamilton residual allocation: weights 3,3,1 over 7 -> 0.4286/0.4286/0.1428")
  void hamiltonResidualAllocation() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    List<HypothesisEvidenceInput> evidenceList = new ArrayList<>();
    evidenceList.addAll(observations(a, 3, 0, 0));
    evidenceList.addAll(observations(b, 3, 0, 0));
    evidenceList.addAll(observations(c, 1, 0, 0));

    HypothesisUncertaintyResult result =
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(a, b, c), evidenceList));

    assertThat(result.candidates().get(0).normalizedValue()).isEqualByComparingTo(new BigDecimal("0.4286"));
    assertThat(result.candidates().get(1).normalizedValue()).isEqualByComparingTo(new BigDecimal("0.4286"));
    assertThat(result.candidates().get(2).normalizedValue()).isEqualByComparingTo(new BigDecimal("0.1428"));
  }

  @Test
  @DisplayName("Hamilton tie-break uses canonical order, not collection order")
  void hamiltonTieBreakUsesCanonicalOrder() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    // Same weights (3,3,1) as the residual case above, but candidates supplied out of canonical
    // order in the context -- the residual must still land on Ha/Hb (canonical-first among the tied
    // largest remainders), never on whichever happened to be first in the input list (Hc).
    List<HypothesisEvidenceInput> evidenceList = new ArrayList<>();
    evidenceList.addAll(observations(c, 1, 0, 0));
    evidenceList.addAll(observations(b, 3, 0, 0));
    evidenceList.addAll(observations(a, 3, 0, 0));

    HypothesisUncertaintyResult result = calculator.calculate(
        HypothesisUncertaintyTestFixtures.context(List.of(c, b, a), evidenceList));

    assertThat(result.candidates()).extracting(c2 -> c2.hypothesis().targetObjectiveId())
        .containsExactly(a.targetObjectiveId(), b.targetObjectiveId(), c.targetObjectiveId());
    assertThat(result.candidates().get(0).normalizedValue()).isEqualByComparingTo(new BigDecimal("0.4286"));
    assertThat(result.candidates().get(1).normalizedValue()).isEqualByComparingTo(new BigDecimal("0.4286"));
    assertThat(result.candidates().get(2).normalizedValue()).isEqualByComparingTo(new BigDecimal("0.1428"));
  }

  // -- empty / insufficient / inconclusive cases --------------------------------------------------

  @Test
  @DisplayName("no candidate hypotheses -> NOT_APPLICABLE")
  void noCandidateHypotheses() {
    HypothesisUncertaintyResult result = calculator.calculate(
        new HypothesisUncertaintyContext(INTERACTION_ID, DOMAIN, List.of(), List.of()));
    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.NOT_APPLICABLE);
  }

  @Test
  @DisplayName("all INSUFFICIENT_EVIDENCE -> status INSUFFICIENT_EVIDENCE")
  void allInsufficientEvidence() {
    DiagnosticHypothesis a = ha();
    HypothesisUncertaintyResult result = calculator.calculate(
        HypothesisUncertaintyTestFixtures.context(List.of(a), List.of()));
    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.INSUFFICIENT_EVIDENCE);
  }

  @Test
  @DisplayName("all INCONCLUSIVE -> status INSUFFICIENT_EVIDENCE, never LOW/MODERATE/HIGH")
  void allInconclusive() {
    DiagnosticHypothesis a = ha();
    HypothesisUncertaintyResult result = calculator.calculate(HypothesisUncertaintyTestFixtures.context(
        List.of(a), observations(a, 0, 0, 7)));
    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.INSUFFICIENT_EVIDENCE);
    assertThat(result.candidates().get(0).band()).isEqualTo(DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE);
  }

  // -- duplicate evidence: the calculator de-duplicates nothing -- §R is the assembler's job -------

  @Test
  @DisplayName("duplicate evidence: an identical repeated observation id is rejected, not de-duplicated "
      + "-- de-duplication is HypothesisUncertaintyContextAssembler's job (Amendment 1 §R), not the "
      + "calculator's")
  void duplicateEvidenceIdenticalRepeatIsRejected() {
    DiagnosticHypothesis a = ha();
    UUID observationId = UUID.randomUUID();
    List<HypothesisEvidenceInput> evidenceList = List.of(
        evidence(observationId, a, HypothesisEvidenceOutcome.SUPPORTING),
        evidence(observationId, a, HypothesisEvidenceOutcome.SUPPORTING));

    assertThatThrownBy(() ->
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(a), evidenceList)))
        .isInstanceOf(HypothesisUncertaintyValidationException.class)
        .extracting(e -> ((HypothesisUncertaintyValidationException) e).reasonCode())
        .isEqualTo(HypothesisUncertaintyReasonCode.DUPLICATE_EVIDENCE_OBSERVATION);
  }

  @Test
  @DisplayName("duplicate evidence: disagreeing outcome for the same id is rejected the same way")
  void duplicateEvidenceDisagreeingOutcomeRejected() {
    DiagnosticHypothesis a = ha();
    UUID observationId = UUID.randomUUID();
    List<HypothesisEvidenceInput> evidenceList = List.of(
        evidence(observationId, a, HypothesisEvidenceOutcome.SUPPORTING),
        evidence(observationId, a, HypothesisEvidenceOutcome.CONTRADICTORY));

    assertThatThrownBy(() ->
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(a), evidenceList)))
        .isInstanceOf(HypothesisUncertaintyValidationException.class)
        .extracting(e -> ((HypothesisUncertaintyValidationException) e).reasonCode())
        .isEqualTo(HypothesisUncertaintyReasonCode.DUPLICATE_EVIDENCE_OBSERVATION);
  }

  @Test
  @DisplayName("duplicate evidence: same id attributed to two different hypotheses is rejected")
  void duplicateEvidenceDisagreeingHypothesisRejected() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    UUID observationId = UUID.randomUUID();
    List<HypothesisEvidenceInput> evidenceList = List.of(
        evidence(observationId, a, HypothesisEvidenceOutcome.SUPPORTING),
        evidence(observationId, b, HypothesisEvidenceOutcome.SUPPORTING));

    assertThatThrownBy(() ->
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(a, b), evidenceList)))
        .isInstanceOf(HypothesisUncertaintyValidationException.class)
        .extracting(e -> ((HypothesisUncertaintyValidationException) e).reasonCode())
        .isEqualTo(HypothesisUncertaintyReasonCode.DUPLICATE_EVIDENCE_OBSERVATION);
  }

  // -- validation: fail closed --------------------------------------------------------------------

  @Test
  @DisplayName("duplicate hypothesis in the candidate set is rejected")
  void duplicateHypothesisRejected() {
    DiagnosticHypothesis a = ha();
    assertThatThrownBy(() -> calculator.calculate(
        HypothesisUncertaintyTestFixtures.context(List.of(a, a), List.of())))
        .isInstanceOf(HypothesisUncertaintyValidationException.class)
        .extracting(e -> ((HypothesisUncertaintyValidationException) e).reasonCode())
        .isEqualTo(HypothesisUncertaintyReasonCode.DUPLICATE_HYPOTHESIS);
  }

  @Test
  @DisplayName("evidence referencing a hypothesis not in the candidate set is rejected")
  void evidenceForUnknownHypothesisRejected() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis unknown = hb();
    assertThatThrownBy(() -> calculator.calculate(HypothesisUncertaintyTestFixtures.context(
        List.of(a), List.of(evidence(unknown, HypothesisEvidenceOutcome.SUPPORTING)))))
        .isInstanceOf(HypothesisUncertaintyValidationException.class)
        .extracting(e -> ((HypothesisUncertaintyValidationException) e).reasonCode())
        .isEqualTo(HypothesisUncertaintyReasonCode.EVIDENCE_FOR_UNKNOWN_HYPOTHESIS);
  }

  @Test
  @DisplayName("evidence from a different interaction is rejected (cross-interaction isolation)")
  void crossInteractionEvidenceRejected() {
    DiagnosticHypothesis a = ha();
    HypothesisEvidenceInput fromAnotherInteraction = new HypothesisEvidenceInput(
        UUID.randomUUID(), a, HypothesisEvidenceOutcome.SUPPORTING, UUID.randomUUID(), DOMAIN);

    assertThatThrownBy(() -> calculator.calculate(
        HypothesisUncertaintyTestFixtures.context(List.of(a), List.of(fromAnotherInteraction))))
        .isInstanceOf(HypothesisUncertaintyValidationException.class)
        .extracting(e -> ((HypothesisUncertaintyValidationException) e).reasonCode())
        .isEqualTo(HypothesisUncertaintyReasonCode.EVIDENCE_INTERACTION_MISMATCH);
  }

  @Test
  @DisplayName("a candidate from a different domain than the context is rejected (cross-domain isolation)")
  void crossDomainCandidateRejected() {
    DiagnosticHypothesis a = ha();
    HypothesisUncertaintyContext context = new HypothesisUncertaintyContext(
        INTERACTION_ID, DOMAIN, List.of(new CandidateHypothesis(a, OTHER_DOMAIN)), List.of());

    assertThatThrownBy(() -> calculator.calculate(context))
        .isInstanceOf(HypothesisUncertaintyValidationException.class)
        .extracting(e -> ((HypothesisUncertaintyValidationException) e).reasonCode())
        .isEqualTo(HypothesisUncertaintyReasonCode.CROSS_DOMAIN_CANDIDATE_SET);
  }

  @Test
  @DisplayName("evidence whose domain differs from its hypothesis's domain is rejected")
  void crossDomainEvidenceRejected() {
    DiagnosticHypothesis a = ha();
    HypothesisEvidenceInput crossDomainEvidence = new HypothesisEvidenceInput(
        UUID.randomUUID(), a, HypothesisEvidenceOutcome.SUPPORTING, INTERACTION_ID, OTHER_DOMAIN);

    assertThatThrownBy(() -> calculator.calculate(
        HypothesisUncertaintyTestFixtures.context(List.of(a), List.of(crossDomainEvidence))))
        .isInstanceOf(HypothesisUncertaintyValidationException.class)
        .extracting(e -> ((HypothesisUncertaintyValidationException) e).reasonCode())
        .isEqualTo(HypothesisUncertaintyReasonCode.CROSS_DOMAIN_EVIDENCE);
  }

  @Test
  @DisplayName("a hypothesis missing a required identity field is rejected")
  void malformedHypothesisIdentityRejected() {
    DiagnosticHypothesis malformed = new DiagnosticHypothesis(
        null, HypothesisUncertaintyTestFixtures.TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE,
        HypothesisUncertaintyTestFixtures.TARGET_A, HypothesisUncertaintyTestFixtures.AUTHORIZING_RELATIONSHIP);

    assertThatThrownBy(() -> calculator.calculate(
        HypothesisUncertaintyTestFixtures.context(List.of(malformed), List.of())))
        .isInstanceOf(HypothesisUncertaintyValidationException.class)
        .extracting(e -> ((HypothesisUncertaintyValidationException) e).reasonCode())
        .isEqualTo(HypothesisUncertaintyReasonCode.MALFORMED_HYPOTHESIS_IDENTITY);
  }

  @Test
  @DisplayName("a null authorizingRelationshipId is not malformed -- SAME_OBJECTIVE_CONFIRMATION/"
      + "PREREQUISITE_VALIDATION legitimately carry none")
  void nullAuthorizingRelationshipIdIsNotMalformed() {
    DiagnosticHypothesis viaCurriculum = new DiagnosticHypothesis(
        HypothesisUncertaintyTestFixtures.TRIGGER_ITEM, HypothesisUncertaintyTestFixtures.TRIGGER_OBJECTIVE,
        ProbeRelationshipType.PREREQUISITE_VALIDATION, HypothesisUncertaintyTestFixtures.TARGET_A, null);

    HypothesisUncertaintyResult result = calculator.calculate(HypothesisUncertaintyTestFixtures.context(
        List.of(viaCurriculum), observations(viaCurriculum, 1, 0, 0)));

    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.APPLICABLE);
  }

  // -- reuse fidelity -------------------------------------------------------------------------------

  @Test
  @DisplayName("the band used is exactly DiagnosticConfidenceCalculatorV1's own band, for every "
      + "representative evidence-count tuple")
  void reuseFidelityAgainstDiagnosticConfidenceCalculatorV1() {
    DiagnosticConfidenceCalculatorV1 reference = new DiagnosticConfidenceCalculatorV1();
    int[][] cases = {
        {0, 0, 0}, {1, 0, 0}, {2, 0, 0}, {3, 0, 0}, {10, 0, 0},
        {2, 1, 0}, {3, 1, 0}, {4, 1, 0}, {10, 1, 0}, {100, 1, 0},
        {3, 2, 0}, {3, 3, 0}, {4, 3, 0}, {10, 5, 0}, {100, 97, 0},
        {1, 3, 0}, {0, 3, 0}, {4, 1, 50},
    };
    for (int[] sci : cases) {
      DiagnosticHypothesis h = ha();
      HypothesisUncertaintyResult result = calculator.calculate(HypothesisUncertaintyTestFixtures.context(
          List.of(h), observations(h, sci[0], sci[1], sci[2])));
      DiagnosticConfidenceBand expected =
          reference.compute(new DiagnosticConfidenceInputs(sci[0], sci[1], sci[2])).band();
      assertThat(result.candidates().get(0).band())
          .as("supporting=%d contradictory=%d inconclusive=%d", sci[0], sci[1], sci[2])
          .isEqualTo(expected);
    }
  }

  // -- helpers -----------------------------------------------------------------------------------

  private HypothesisUncertaintyContext mixedContext() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    return HypothesisUncertaintyTestFixtures.context(List.of(a, b, c), allEvidenceFor(a, b, c));
  }

  private List<HypothesisEvidenceInput> allEvidenceFor(DiagnosticHypothesis... hypotheses) {
    List<HypothesisEvidenceInput> evidenceList = new ArrayList<>();
    int[] supportingCounts = {4, 2, 1};
    for (int i = 0; i < hypotheses.length; i++) {
      evidenceList.addAll(observations(hypotheses[i], supportingCounts[i % supportingCounts.length], 0, 0));
    }
    return evidenceList;
  }

  private static BigDecimal valueOf(HypothesisUncertaintyResult result, DiagnosticConfidenceBand band) {
    return result.candidates().stream()
        .filter(candidate -> candidate.band() == band)
        .map(CandidateUncertainty::normalizedValue)
        .findFirst().orElseThrow();
  }
}
