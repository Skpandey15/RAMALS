package io.ramals.learningplatform.assessment.hypothesisdiscrimination;

import io.ramals.learningplatform.assessment.DiagnosticConfidenceCalculatorV1;
import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome;
import io.ramals.learningplatform.assessment.ProbeRelationshipType;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.CandidateHypothesis;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisEvidenceInput;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyCalculatorV1;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyContext;
import io.ramals.learningplatform.assessment.hypothesisuncertainty.HypothesisUncertaintyResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared fixtures for {@code HYPOTHESIS_DISCRIMINATION_V1} tests: the same three hypotheses,
 * already in M2-ADR-034 Amendment 1 §H canonical order, one interaction, one domain, that
 * Amendment 1 §J's and Amendment 2 §Q's golden vectors both use -- the identical UUIDs {@code
 * HypothesisUncertaintyTestFixtures} uses, reconstructed here since that class is package-private
 * to its own test package.
 */
final class HypothesisDiscriminationTestFixtures {

  private static final UUID TRIGGER_ITEM = UUID.fromString("01900000-0000-7000-8000-000000001000");
  private static final UUID TRIGGER_OBJECTIVE = UUID.fromString("01900000-0000-7000-8000-000000002000");
  private static final UUID AUTHORIZING_RELATIONSHIP = UUID.fromString("01900000-0000-7000-8000-000000004000");

  private static final UUID TARGET_A = UUID.fromString("01900000-0000-7000-8000-000000003001");
  private static final UUID TARGET_B = UUID.fromString("01900000-0000-7000-8000-000000003002");
  private static final UUID TARGET_C = UUID.fromString("01900000-0000-7000-8000-000000003003");

  static final UUID INTERACTION_ID = UUID.fromString("01900000-0000-7000-8000-00000000a000");
  static final String DOMAIN = "KAFKA";
  static final String OTHER_DOMAIN = "POSTGRES";

  static final UUID PROBE_1 = UUID.fromString("01900000-0000-7000-8000-000000005001");
  static final UUID PROBE_2 = UUID.fromString("01900000-0000-7000-8000-000000005002");

  private static final HypothesisUncertaintyCalculatorV1 UNCERTAINTY_CALCULATOR =
      new HypothesisUncertaintyCalculatorV1(new DiagnosticConfidenceCalculatorV1());

  private HypothesisDiscriminationTestFixtures() {
  }

  static DiagnosticHypothesis ha() {
    return rootCauseHypothesis(TARGET_A);
  }

  static DiagnosticHypothesis hb() {
    return rootCauseHypothesis(TARGET_B);
  }

  static DiagnosticHypothesis hc() {
    return rootCauseHypothesis(TARGET_C);
  }

  static DiagnosticHypothesis rootCauseHypothesis(UUID targetObjectiveId) {
    return new DiagnosticHypothesis(
        TRIGGER_ITEM, TRIGGER_OBJECTIVE, ProbeRelationshipType.ROOT_CAUSE_PROBE, targetObjectiveId,
        AUTHORIZING_RELATIONSHIP);
  }

  static CandidateHypothesis candidate(DiagnosticHypothesis hypothesis) {
    return new CandidateHypothesis(hypothesis, DOMAIN);
  }

  static HypothesisEvidenceInput evidence(DiagnosticHypothesis hypothesis, HypothesisEvidenceOutcome outcome) {
    return new HypothesisEvidenceInput(UUID.randomUUID(), hypothesis, outcome, INTERACTION_ID, DOMAIN);
  }

  /** {@code count} distinct SUPPORTING/CONTRADICTORY observations for one hypothesis. */
  static List<HypothesisEvidenceInput> observations(
      DiagnosticHypothesis hypothesis, int supporting, int contradictory) {
    List<HypothesisEvidenceInput> result = new ArrayList<>();
    for (int i = 0; i < supporting; i++) {
      result.add(evidence(hypothesis, HypothesisEvidenceOutcome.SUPPORTING));
    }
    for (int i = 0; i < contradictory; i++) {
      result.add(evidence(hypothesis, HypothesisEvidenceOutcome.CONTRADICTORY));
    }
    return result;
  }

  static HypothesisUncertaintyContext baseContext(
      List<DiagnosticHypothesis> hypotheses, List<HypothesisEvidenceInput> evidence) {
    return new HypothesisUncertaintyContext(
        INTERACTION_ID, DOMAIN,
        hypotheses.stream().map(HypothesisDiscriminationTestFixtures::candidate).toList(), evidence);
  }

  static HypothesisUncertaintyResult baseResult(HypothesisUncertaintyContext baseContext) {
    return UNCERTAINTY_CALCULATOR.calculate(baseContext);
  }

  static HypothesisDiscriminationContext discriminationContext(
      HypothesisUncertaintyContext baseContext, List<CandidateProbe> candidates) {
    return new HypothesisDiscriminationContext(baseContext, baseResult(baseContext), candidates);
  }

  static CandidateProbe probe(UUID probeItemVersionId, DiagnosticHypothesis hypothesis, boolean scoreable) {
    return new CandidateProbe(probeItemVersionId, hypothesis, scoreable);
  }
}
