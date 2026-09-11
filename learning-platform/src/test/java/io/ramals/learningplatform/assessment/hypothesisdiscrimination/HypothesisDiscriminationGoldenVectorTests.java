package io.ramals.learningplatform.assessment.hypothesisdiscrimination;

import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.PROBE_1;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.PROBE_2;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.baseContext;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.discriminationContext;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.ha;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.hb;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.hc;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.observations;
import static io.ramals.learningplatform.assessment.hypothesisdiscrimination.HypothesisDiscriminationTestFixtures.probe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.assessment.DiagnosticConfidenceCalculatorV1;
import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisEvidenceInput;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The eleven normative golden vectors of M2-ADR-034 Amendment 2 §Q -- the implementation oracle.
 * Every expected value is the exact decimal the ADR names; no epsilon comparisons.
 */
class HypothesisDiscriminationGoldenVectorTests {

  private final HypothesisUncertaintyCalculatorV1 uncertaintyCalculator =
      new HypothesisUncertaintyCalculatorV1(new DiagnosticConfidenceCalculatorV1());
  private final HypothesisDiscriminationCalculatorV1 calculator =
      new HypothesisDiscriminationCalculatorV1(uncertaintyCalculator);

  @Test
  @DisplayName("#1 -- two equal hypotheses, most-separating available probe -> 0.2667")
  void vector1TwoEqualHypothesesMostSeparating() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b), evidence(a, 2, b, 2));

    HypothesisDiscriminationResult result = calculator.calculate(
        discriminationContext(base, List.of(probe(PROBE_1, a, true))));

    assertThat(result.status()).isEqualTo(HypothesisDiscriminationStatus.SCORABLE);
    assertScore(result, 0, PROBE_1, a, "0.2667");
  }

  @Test
  @DisplayName("#2 -- two equal hypotheses, non-scoreable probe -> 0.0000")
  void vector2NonScoreableProbe() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b), evidence(a, 2, b, 2));

    HypothesisDiscriminationResult result = calculator.calculate(
        discriminationContext(base, List.of(probe(PROBE_1, a, false))));

    assertThat(result.status()).isEqualTo(HypothesisDiscriminationStatus.SCORABLE);
    assertScore(result, 0, PROBE_1, a, "0.0000");
  }

  @Test
  @DisplayName("#3 -- uneven base distribution -> 0.1500")
  void vector3UnevenBaseDistribution() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b), evidence(a, 4, b, 1));

    HypothesisDiscriminationResult result = calculator.calculate(
        discriminationContext(base, List.of(probe(PROBE_1, b, true))));

    assertThat(result.status()).isEqualTo(HypothesisDiscriminationStatus.SCORABLE);
    assertScore(result, 0, PROBE_1, b, "0.1500");
  }

  @Test
  @DisplayName("#4 -- three hypotheses, 1-vs-2 split, probe on the \"1\" side -> 0.2500")
  void vector4ThreeHypothesesSplit() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    HypothesisUncertaintyContext base =
        baseContext(List.of(a, b, c), threeWay(a, 3, b, 2, c, 1));

    HypothesisDiscriminationResult result = calculator.calculate(
        discriminationContext(base, List.of(probe(PROBE_1, a, true))));

    assertThat(result.status()).isEqualTo(HypothesisDiscriminationStatus.SCORABLE);
    assertScore(result, 0, PROBE_1, a, "0.2500");
  }

  @Test
  @DisplayName("#5 -- probe inconclusive for its hypothesis, any base -> 0.0000")
  void vector5NonScoreableAnyBase() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b, c), threeWay(a, 2, b, 1, c, 4));

    HypothesisDiscriminationResult result = calculator.calculate(
        discriminationContext(base, List.of(probe(PROBE_1, a, false))));

    assertThat(result.status()).isEqualTo(HypothesisDiscriminationStatus.SCORABLE);
    assertScore(result, 0, PROBE_1, a, "0.0000");
  }

  @Test
  @DisplayName("#6 -- one participating hypothesis only -> 0.0000")
  void vector6OneHypothesisOnly() {
    DiagnosticHypothesis a = ha();
    HypothesisUncertaintyContext base = baseContext(List.of(a), observations(a, 3, 0));

    HypothesisDiscriminationResult result = calculator.calculate(
        discriminationContext(base, List.of(probe(PROBE_1, a, true))));

    assertThat(result.status()).isEqualTo(HypothesisDiscriminationStatus.SCORABLE);
    assertScore(result, 0, PROBE_1, a, "0.0000");
  }

  @Test
  @DisplayName("#7 -- two probes, identical score -> deterministic tie-break by hypothesis order")
  void vector7DeterministicTieBreak() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b), evidence(a, 2, b, 2));

    HypothesisDiscriminationResult result = calculator.calculate(discriminationContext(
        base, List.of(probe(PROBE_1, a, true), probe(PROBE_2, b, true))));

    assertThat(result.status()).isEqualTo(HypothesisDiscriminationStatus.SCORABLE);
    assertScore(result, 0, PROBE_1, a, "0.2667");
    assertScore(result, 1, PROBE_2, b, "0.2667");

    List<CandidateDiscrimination> ranked = result.probes().stream()
        .sorted(HypothesisDiscriminationCalculatorV1.RANKING_ORDER)
        .toList();
    assertThat(ranked.get(0).probeItemVersionId()).isEqualTo(PROBE_1);
    assertThat(ranked.get(1).probeItemVersionId()).isEqualTo(PROBE_2);
  }

  @Test
  @DisplayName("#8 -- shuffled hypothesis order -> identical result, canonical emission")
  void vector8ShuffledHypothesisOrder() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    // Candidates supplied in [c, b, a] order -- HypothesisUncertaintyContext takes them as-is.
    HypothesisUncertaintyContext base = new HypothesisUncertaintyContext(
        HypothesisDiscriminationTestFixtures.INTERACTION_ID, HypothesisDiscriminationTestFixtures.DOMAIN,
        List.of(HypothesisDiscriminationTestFixtures.candidate(c),
            HypothesisDiscriminationTestFixtures.candidate(b),
            HypothesisDiscriminationTestFixtures.candidate(a)),
        threeWay(a, 3, b, 2, c, 1));

    HypothesisDiscriminationResult result = calculator.calculate(
        discriminationContext(base, List.of(probe(PROBE_1, a, true))));

    assertThat(result.status()).isEqualTo(HypothesisDiscriminationStatus.SCORABLE);
    assertScore(result, 0, PROBE_1, a, "0.2500");
  }

  @Test
  @DisplayName("#9 -- shuffled probe input order -> identical scores and canonical emission")
  void vector9ShuffledProbeOrder() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b, c), threeWay(a, 3, b, 2, c, 1));
    HypothesisDiscriminationContext context = discriminationContext(
        base, List.of(probe(PROBE_1, a, true), probe(PROBE_2, b, true)));
    HypothesisDiscriminationContext shuffled = discriminationContext(
        base, List.of(probe(PROBE_2, b, true), probe(PROBE_1, a, true)));

    HypothesisDiscriminationResult result = calculator.calculate(context);
    HypothesisDiscriminationResult shuffledResult = calculator.calculate(shuffled);

    assertThat(shuffledResult).isEqualTo(result);
    assertScore(result, 0, PROBE_1, a, "0.2500");
  }

  @Test
  @DisplayName("#10 -- insufficient uncertainty input -> NOT_APPLICABLE, no scores")
  void vector10InsufficientBase() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b, c), List.of());

    HypothesisDiscriminationResult result = calculator.calculate(
        discriminationContext(base, List.of(probe(PROBE_1, a, true))));

    assertThat(result.status()).isEqualTo(HypothesisDiscriminationStatus.NOT_APPLICABLE);
    assertThat(result.probes()).isEmpty();
  }

  @Test
  @DisplayName("#11 -- probe for a hypothesis outside the candidate set -> PROBE_FOR_UNKNOWN_HYPOTHESIS")
  void vector11ProbeForUnknownHypothesis() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    HypothesisUncertaintyContext base = baseContext(List.of(a, b), evidence(a, 2, b, 2));

    assertThatThrownBy(() -> calculator.calculate(
        discriminationContext(base, List.of(probe(PROBE_1, c, true)))))
        .isInstanceOf(HypothesisDiscriminationValidationException.class)
        .satisfies(exception -> assertThat(((HypothesisDiscriminationValidationException) exception).reasonCode())
            .isEqualTo(HypothesisDiscriminationReasonCode.PROBE_FOR_UNKNOWN_HYPOTHESIS));
  }

  // -- helpers ---------------------------------------------------------------------------------------

  private static List<HypothesisEvidenceInput> evidence(
      DiagnosticHypothesis a, int aSupporting, DiagnosticHypothesis b, int bSupporting) {
    List<HypothesisEvidenceInput> result = new ArrayList<>();
    result.addAll(observations(a, aSupporting, 0));
    result.addAll(observations(b, bSupporting, 0));
    return result;
  }

  private static List<HypothesisEvidenceInput> threeWay(
      DiagnosticHypothesis a, int aSupporting, DiagnosticHypothesis b, int bSupporting,
      DiagnosticHypothesis c, int cSupporting) {
    List<HypothesisEvidenceInput> result = new ArrayList<>();
    result.addAll(observations(a, aSupporting, 0));
    result.addAll(observations(b, bSupporting, 0));
    result.addAll(observations(c, cSupporting, 0));
    return result;
  }

  private static void assertScore(
      HypothesisDiscriminationResult result, int index, java.util.UUID probeItemVersionId,
      DiagnosticHypothesis hypothesis, String expectedScore) {
    CandidateDiscrimination candidate = result.probes().get(index);
    assertThat(candidate.probeItemVersionId()).isEqualTo(probeItemVersionId);
    assertThat(candidate.hypothesis()).isEqualTo(hypothesis);
    assertThat(candidate.score()).isEqualByComparingTo(new BigDecimal(expectedScore));
  }
}
