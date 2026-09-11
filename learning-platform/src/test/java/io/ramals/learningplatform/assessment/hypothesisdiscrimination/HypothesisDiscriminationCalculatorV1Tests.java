package io.ramals.learningplatform.assessment.hypothesisdiscrimination;

import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.PROBE_1;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.PROBE_2;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.baseContext;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.baseResult;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.discriminationContext;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.ha;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.hb;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.observations;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.probe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.DiagnosticConfidenceCalculatorV1;
import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisEvidenceInput;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContext;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validation reason codes (M2-ADR-034 Amendment 2 §N) and implementation-test-obligation
 * invariants (§R) not already exercised by the golden vectors.
 */
class HypothesisDiscriminationCalculatorV1Tests {

  private final HypothesisUncertaintyCalculatorV1 uncertaintyCalculator =
      new HypothesisUncertaintyCalculatorV1(new DiagnosticConfidenceCalculatorV1());
  private final HypothesisDiscriminationCalculatorV1 calculator =
      new HypothesisDiscriminationCalculatorV1(uncertaintyCalculator);

  @Test
  @DisplayName("BASE_RESULT_MISMATCH: baseResult does not equal a fresh recompute of baseContext")
  void baseResultMismatchIsRejected() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    HypothesisUncertaintyContext base = baseContext(
        List.of(a, b), evidence(a, 2, b, 2));
    // A stale result computed from a different (empty-evidence) context, paired with the real one.
    HypothesisUncertaintyResult staleResult = uncertaintyCalculator.calculate(baseContext(List.of(a, b), List.of()));

    HypothesisDiscriminationContext context =
        new HypothesisDiscriminationContext(base, staleResult, List.of(probe(PROBE_1, a, true)));

    assertThatThrownBy(() -> calculator.calculate(context))
        .isInstanceOf(HypothesisDiscriminationValidationException.class)
        .satisfies(exception -> assertThat(((HypothesisDiscriminationValidationException) exception).reasonCode())
            .isEqualTo(HypothesisDiscriminationReasonCode.BASE_RESULT_MISMATCH));
  }

  @Test
  @DisplayName("MALFORMED_CANDIDATE_PROBE: a null probeItemVersionId is rejected")
  void malformedCandidateProbeMissingItemIsRejected() {
    DiagnosticHypothesis a = ha();
    HypothesisUncertaintyContext base = baseContext(List.of(a), observations(a, 3, 0));

    assertThatThrownBy(() -> calculator.calculate(
        discriminationContext(base, List.of(probe(null, a, true)))))
        .isInstanceOf(HypothesisDiscriminationValidationException.class)
        .satisfies(exception -> assertThat(((HypothesisDiscriminationValidationException) exception).reasonCode())
            .isEqualTo(HypothesisDiscriminationReasonCode.MALFORMED_CANDIDATE_PROBE));
  }

  @Test
  @DisplayName("MALFORMED_CANDIDATE_PROBE: a null hypothesis is rejected")
  void malformedCandidateProbeMissingHypothesisIsRejected() {
    DiagnosticHypothesis a = ha();
    HypothesisUncertaintyContext base = baseContext(List.of(a), observations(a, 3, 0));

    assertThatThrownBy(() -> calculator.calculate(
        discriminationContext(base, List.of(probe(PROBE_1, null, true)))))
        .isInstanceOf(HypothesisDiscriminationValidationException.class)
        .satisfies(exception -> assertThat(((HypothesisDiscriminationValidationException) exception).reasonCode())
            .isEqualTo(HypothesisDiscriminationReasonCode.MALFORMED_CANDIDATE_PROBE));
  }

  @Test
  @DisplayName("DUPLICATE_CANDIDATE_PROBE: the same (probeItemVersionId, hypothesis) pair repeated")
  void duplicateCandidateProbeIsRejected() {
    DiagnosticHypothesis a = ha();
    HypothesisUncertaintyContext base = baseContext(List.of(a), observations(a, 3, 0));

    assertThatThrownBy(() -> calculator.calculate(discriminationContext(
        base, List.of(probe(PROBE_1, a, true), probe(PROBE_1, a, true)))))
        .isInstanceOf(HypothesisDiscriminationValidationException.class)
        .satisfies(exception -> assertThat(((HypothesisDiscriminationValidationException) exception).reasonCode())
            .isEqualTo(HypothesisDiscriminationReasonCode.DUPLICATE_CANDIDATE_PROBE));
  }

  @Test
  @DisplayName("the same item version id for two different hypotheses is not a duplicate")
  void sameItemDifferentHypothesisIsNotADuplicate() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b), evidence(a, 2, b, 2));

    HypothesisDiscriminationResult result = calculator.calculate(discriminationContext(
        base, List.of(probe(PROBE_1, a, true), probe(PROBE_1, b, true))));

    assertThat(result.status()).isEqualTo(HypothesisDiscriminationStatus.SCORABLE);
    assertThat(result.probes()).hasSize(2);
  }

  @Test
  @DisplayName("determinism: identical input produces an identical result")
  void determinism() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b), evidence(a, 2, b, 2));
    HypothesisDiscriminationContext context =
        discriminationContext(base, List.of(probe(PROBE_1, a, true), probe(PROBE_2, b, true)));

    HypothesisDiscriminationResult first = calculator.calculate(context);
    HypothesisDiscriminationResult second = calculator.calculate(context);

    assertThat(first).isEqualTo(second);
    assertThat(first.engineVersion()).isEqualTo("HYPOTHESIS_DISCRIMINATION_V1");
  }

  @Test
  @DisplayName("range: every score is between 0.0000 and 1.0000 inclusive")
  void scoreRange() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b), evidence(a, 4, b, 1));

    HypothesisDiscriminationResult result = calculator.calculate(discriminationContext(
        base, List.of(probe(PROBE_1, a, true), probe(PROBE_2, b, true))));

    for (CandidateDiscrimination candidate : result.probes()) {
      assertThat(candidate.score()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
      assertThat(candidate.score()).isLessThanOrEqualTo(BigDecimal.ONE);
    }
  }

  @Test
  @DisplayName("reuse fidelity: the hypothetical worlds equal direct HYPOTHESIS_UNCERTAINTY_V1 calls")
  void reuseFidelity() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b), evidence(a, 2, b, 2));

    HypothesisDiscriminationResult result = calculator.calculate(
        discriminationContext(base, List.of(probe(PROBE_1, a, true))));

    UUID supportingObservationId = UUID.nameUUIDFromBytes(
        (PROBE_1 + ":" + HypothesisEvidenceOutcome.SUPPORTING.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID contradictoryObservationId = UUID.nameUUIDFromBytes(
        (PROBE_1 + ":" + HypothesisEvidenceOutcome.CONTRADICTORY.name()).getBytes(java.nio.charset.StandardCharsets.UTF_8));

    List<HypothesisEvidenceInput> supportingEvidence = new ArrayList<>(base.evidence());
    supportingEvidence.add(new HypothesisEvidenceInput(
        supportingObservationId, a, HypothesisEvidenceOutcome.SUPPORTING, base.interactionId(), base.domainCode()));
    List<HypothesisEvidenceInput> contradictoryEvidence = new ArrayList<>(base.evidence());
    contradictoryEvidence.add(new HypothesisEvidenceInput(
        contradictoryObservationId, a, HypothesisEvidenceOutcome.CONTRADICTORY, base.interactionId(),
        base.domainCode()));

    HypothesisUncertaintyResult expectedSupporting = uncertaintyCalculator.calculate(
        new HypothesisUncertaintyContext(base.interactionId(), base.domainCode(), base.candidates(), supportingEvidence));
    HypothesisUncertaintyResult expectedContradictory = uncertaintyCalculator.calculate(
        new HypothesisUncertaintyContext(base.interactionId(), base.domainCode(), base.candidates(), contradictoryEvidence));

    BigDecimal expectedScore = BigDecimal.ZERO;
    for (int i = 0; i < expectedSupporting.candidates().size(); i++) {
      BigDecimal s = expectedSupporting.candidates().get(i).normalizedValue();
      BigDecimal c = expectedContradictory.candidates().get(i).normalizedValue();
      expectedScore = expectedScore.add(
          (s == null ? BigDecimal.ZERO.setScale(4) : s).subtract(c == null ? BigDecimal.ZERO.setScale(4) : c).abs());
    }
    expectedScore = expectedScore.divide(BigDecimal.valueOf(2), 4, java.math.RoundingMode.HALF_EVEN);

    assertThat(result.probes().get(0).score()).isEqualByComparingTo(expectedScore);
  }

  @Test
  @DisplayName("baseResult status APPLICABLE with zero candidates is a valid SCORABLE empty result")
  void zeroCandidatesIsValidScorableEmptyResult() {
    DiagnosticHypothesis a = ha();
    HypothesisUncertaintyContext base = baseContext(List.of(a), observations(a, 3, 0));

    HypothesisDiscriminationResult result = calculator.calculate(discriminationContext(base, List.of()));

    assertThat(result.status()).isEqualTo(HypothesisDiscriminationStatus.SCORABLE);
    assertThat(result.probes()).isEmpty();
  }

  private static List<HypothesisEvidenceInput> evidence(
      DiagnosticHypothesis a, int aSupporting, DiagnosticHypothesis b, int bSupporting) {
    List<HypothesisEvidenceInput> result = new ArrayList<>();
    result.addAll(observations(a, aSupporting, 0));
    result.addAll(observations(b, bSupporting, 0));
    return result;
  }
}
