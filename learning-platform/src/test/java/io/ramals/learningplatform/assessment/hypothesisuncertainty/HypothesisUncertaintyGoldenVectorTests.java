package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.DOMAIN;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.INTERACTION_ID;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.candidate;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.evidence;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.ha;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.hb;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.hc;
import static io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyTestFixtures.observations;
import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.assessment.DiagnosticConfidenceBand;
import io.ramals.learningplatform.assessment.DiagnosticConfidenceCalculatorV1;
import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The eleven normative golden vectors of M2-ADR-034 Amendment 1 §J -- the implementation oracle.
 * Every expected value is the exact decimal the ADR names; no epsilon comparisons.
 */
class HypothesisUncertaintyGoldenVectorTests {

  private final HypothesisUncertaintyCalculatorV1 calculator =
      new HypothesisUncertaintyCalculatorV1(new DiagnosticConfidenceCalculatorV1());

  @Test
  @DisplayName("#1 -- no candidate -> NOT_APPLICABLE, empty candidates")
  void vector1NoCandidate() {
    HypothesisUncertaintyResult result = calculator.calculate(
        new HypothesisUncertaintyContext(INTERACTION_ID, DOMAIN, List.of(), List.of()));

    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.NOT_APPLICABLE);
    assertThat(result.candidates()).isEmpty();
  }

  @Test
  @DisplayName("#2 -- single candidate, HIGH -> 1.0000")
  void vector2SingleCandidate() {
    DiagnosticHypothesis a = ha();
    HypothesisUncertaintyResult result = calculator.calculate(HypothesisUncertaintyTestFixtures.context(
        List.of(a), observations(a, 3, 0, 0)));

    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.APPLICABLE);
    assertThat(result.candidates()).hasSize(1);
    assertCandidate(result, 0, a, DiagnosticConfidenceBand.HIGH, true, "1.0000");
  }

  @Test
  @DisplayName("#3 -- two equal candidates -> 0.5000 / 0.5000")
  void vector3TwoEqualCandidates() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    List<HypothesisEvidenceInput> evidence = new ArrayList<>();
    evidence.addAll(observations(a, 2, 0, 0));
    evidence.addAll(observations(b, 2, 0, 0));

    HypothesisUncertaintyResult result =
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(a, b), evidence));

    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.APPLICABLE);
    assertCandidate(result, 0, a, DiagnosticConfidenceBand.MODERATE, true, "0.5000");
    assertCandidate(result, 1, b, DiagnosticConfidenceBand.MODERATE, true, "0.5000");
  }

  @Test
  @DisplayName("#4 -- HIGH vs LOW -> 0.7500 / 0.2500")
  void vector4HighVsLow() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    List<HypothesisEvidenceInput> evidence = new ArrayList<>();
    evidence.addAll(observations(a, 4, 0, 0));
    evidence.addAll(observations(b, 1, 0, 0));

    HypothesisUncertaintyResult result =
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(a, b), evidence));

    assertCandidate(result, 0, a, DiagnosticConfidenceBand.HIGH, true, "0.7500");
    assertCandidate(result, 1, b, DiagnosticConfidenceBand.LOW, true, "0.2500");
  }

  @Test
  @DisplayName("#5 -- HIGH vs INSUFFICIENT_EVIDENCE -> 1.0000 / null, never 0.9xxx / 0.0xxx")
  void vector5HighVsInsufficient() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();

    HypothesisUncertaintyResult result = calculator.calculate(HypothesisUncertaintyTestFixtures.context(
        List.of(a, b), observations(a, 5, 0, 0)));

    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.APPLICABLE);
    assertCandidate(result, 0, a, DiagnosticConfidenceBand.HIGH, true, "1.0000");
    assertCandidate(result, 1, b, DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE, false, null);
  }

  @Test
  @DisplayName("#6 -- all INSUFFICIENT_EVIDENCE -> status INSUFFICIENT_EVIDENCE, no distribution")
  void vector6AllInsufficient() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();

    HypothesisUncertaintyResult result = calculator.calculate(
        HypothesisUncertaintyTestFixtures.context(List.of(a, b, c), List.of()));

    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.INSUFFICIENT_EVIDENCE);
    assertThat(result.candidates()).hasSize(3);
    result.candidates().forEach(candidate -> {
      assertThat(candidate.band()).isEqualTo(DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE);
      assertThat(candidate.participates()).isFalse();
      assertThat(candidate.normalizedValue()).isNull();
    });
  }

  @Test
  @DisplayName("#7 -- all INCONCLUSIVE evidence -> identical shape to #6")
  void vector7AllInconclusive() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    List<HypothesisEvidenceInput> evidence = new ArrayList<>();
    evidence.addAll(observations(a, 0, 0, 4));
    evidence.addAll(observations(b, 0, 0, 2));

    HypothesisUncertaintyResult result =
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(a, b), evidence));

    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.INSUFFICIENT_EVIDENCE);
    result.candidates().forEach(candidate -> {
      assertThat(candidate.band()).isEqualTo(DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE);
      assertThat(candidate.participates()).isFalse();
      assertThat(candidate.normalizedValue()).isNull();
    });
  }

  @Test
  @DisplayName("#8 -- rounding/residual, 3 candidates -> 0.4286 / 0.4286 / 0.1428, sum 1.0000")
  void vector8RoundingResidual() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    List<HypothesisEvidenceInput> evidence = new ArrayList<>();
    evidence.addAll(observations(a, 3, 0, 0));
    evidence.addAll(observations(b, 3, 0, 0));
    evidence.addAll(observations(c, 1, 0, 0));

    HypothesisUncertaintyResult result =
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(a, b, c), evidence));

    assertCandidate(result, 0, a, DiagnosticConfidenceBand.HIGH, true, "0.4286");
    assertCandidate(result, 1, b, DiagnosticConfidenceBand.HIGH, true, "0.4286");
    assertCandidate(result, 2, c, DiagnosticConfidenceBand.LOW, true, "0.1428");
    assertSumIsExactlyOne(result);
  }

  @Test
  @DisplayName("#9 -- input order permuted -> byte-identical to #8, canonical order")
  void vector9InputOrderPermuted() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    List<HypothesisEvidenceInput> evidence = new ArrayList<>();
    evidence.addAll(observations(c, 1, 0, 0));
    evidence.addAll(observations(a, 3, 0, 0));
    evidence.addAll(observations(b, 3, 0, 0));
    Collections.shuffle(evidence, new java.util.Random(7));

    // Candidates supplied out of canonical order (c, b, a).
    HypothesisUncertaintyResult result =
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(c, b, a), evidence));

    assertCandidate(result, 0, a, DiagnosticConfidenceBand.HIGH, true, "0.4286");
    assertCandidate(result, 1, b, DiagnosticConfidenceBand.HIGH, true, "0.4286");
    assertCandidate(result, 2, c, DiagnosticConfidenceBand.LOW, true, "0.1428");
  }

  @Test
  @DisplayName("#10 -- two distinct observations (post de-duplication) -> MODERATE / 1.0000")
  void vector10DuplicateEvidenceDeduplicated() {
    // Amendment 1 §R: de-duplicating a benign repeated observation id is
    // HypothesisUncertaintyContextAssembler's job, not the calculator's -- see
    // HypothesisUncertaintyContextAssemblerTests for the assembler-side proof that a governed
    // observation reaching it through more than one projection is folded into one input before a
    // context is ever built. What reaches the calculator here is exactly what a correctly-assembled
    // context for the ADR's raw scenario ({@code [obs-1: SUPPORTING, obs-2: SUPPORTING, obs-1:
    // SUPPORTING]}) looks like after that de-duplication: two distinct observation ids, both
    // SUPPORTING -- (s,c,i) = (2,0,0) -> MODERATE, not HIGH (which a wrongly-undeduplicated s=3 would
    // give). A repeated id reaching the calculator directly is instead rejected -- see
    // duplicateEvidenceIdenticalRepeatIsRejected in HypothesisUncertaintyCalculatorV1Tests.
    DiagnosticHypothesis a = ha();
    UUID obs1 = UUID.randomUUID();
    UUID obs2 = UUID.randomUUID();
    List<HypothesisEvidenceInput> evidence = List.of(
        evidence(obs1, a, HypothesisEvidenceOutcome.SUPPORTING),
        evidence(obs2, a, HypothesisEvidenceOutcome.SUPPORTING));

    HypothesisUncertaintyResult result =
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(a), evidence));

    assertCandidate(result, 0, a, DiagnosticConfidenceBand.MODERATE, true, "1.0000");
  }

  @Test
  @DisplayName("#11 -- mixed sufficient/insufficient, 3 candidates")
  void vector11MixedSufficientInsufficient() {
    DiagnosticHypothesis a = ha();
    DiagnosticHypothesis b = hb();
    DiagnosticHypothesis c = hc();
    List<HypothesisEvidenceInput> evidence = new ArrayList<>();
    evidence.addAll(observations(a, 4, 0, 0));
    evidence.addAll(observations(b, 1, 0, 0));

    HypothesisUncertaintyResult result =
        calculator.calculate(HypothesisUncertaintyTestFixtures.context(List.of(a, b, c), evidence));

    assertThat(result.status()).isEqualTo(HypothesisUncertaintyStatus.APPLICABLE);
    assertCandidate(result, 0, a, DiagnosticConfidenceBand.HIGH, true, "0.7500");
    assertCandidate(result, 1, b, DiagnosticConfidenceBand.LOW, true, "0.2500");
    assertCandidate(result, 2, c, DiagnosticConfidenceBand.INSUFFICIENT_EVIDENCE, false, null);
    assertSumIsExactlyOne(result);
  }

  // -- helpers -----------------------------------------------------------------------------------

  private static void assertCandidate(
      HypothesisUncertaintyResult result, int index, DiagnosticHypothesis expectedHypothesis,
      DiagnosticConfidenceBand expectedBand, boolean expectedParticipates, String expectedValue) {
    CandidateUncertainty candidate = result.candidates().get(index);
    assertThat(candidate.hypothesis()).isEqualTo(expectedHypothesis);
    assertThat(candidate.band()).isEqualTo(expectedBand);
    assertThat(candidate.participates()).isEqualTo(expectedParticipates);
    if (expectedValue == null) {
      assertThat(candidate.normalizedValue()).isNull();
    } else {
      assertThat(candidate.normalizedValue()).isEqualByComparingTo(new BigDecimal(expectedValue));
    }
  }

  private static void assertSumIsExactlyOne(HypothesisUncertaintyResult result) {
    BigDecimal sum = result.candidates().stream()
        .filter(CandidateUncertainty::participates)
        .map(CandidateUncertainty::normalizedValue)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo(new BigDecimal("1.0000"));
  }
}
