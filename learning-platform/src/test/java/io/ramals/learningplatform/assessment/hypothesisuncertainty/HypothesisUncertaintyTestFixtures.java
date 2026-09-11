package io.ramals.learningplatform.assessment.hypothesisuncertainty;

import io.ramals.learningplatform.assessment.DiagnosticHypothesis;
import io.ramals.learningplatform.assessment.HypothesisEvidenceOutcome;
import io.ramals.learningplatform.assessment.ProbeRelationshipType;
import java.util.List;
import java.util.UUID;

/**
 * Shared fixtures for {@code HYPOTHESIS_UNCERTAINTY_V1} tests: three hypotheses already in
 * M2-ADR-034 Amendment 1 §H canonical order (all {@code ROOT_CAUSE_PROBE}, ascending {@code
 * targetObjectiveId}), one interaction, one domain.
 */
final class HypothesisUncertaintyTestFixtures {

  static final UUID TRIGGER_ITEM = UUID.fromString("01900000-0000-7000-8000-000000001000");
  static final UUID TRIGGER_OBJECTIVE = UUID.fromString("01900000-0000-7000-8000-000000002000");
  static final UUID AUTHORIZING_RELATIONSHIP = UUID.fromString("01900000-0000-7000-8000-000000004000");

  static final UUID TARGET_A = UUID.fromString("01900000-0000-7000-8000-000000003001");
  static final UUID TARGET_B = UUID.fromString("01900000-0000-7000-8000-000000003002");
  static final UUID TARGET_C = UUID.fromString("01900000-0000-7000-8000-000000003003");

  static final UUID INTERACTION_ID = UUID.fromString("01900000-0000-7000-8000-00000000a000");
  static final String DOMAIN = "KAFKA";
  static final String OTHER_DOMAIN = "POSTGRES";

  private HypothesisUncertaintyTestFixtures() {
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

  static HypothesisEvidenceInput evidence(
      DiagnosticHypothesis hypothesis, HypothesisEvidenceOutcome outcome) {
    return new HypothesisEvidenceInput(UUID.randomUUID(), hypothesis, outcome, INTERACTION_ID, DOMAIN);
  }

  static HypothesisEvidenceInput evidence(
      UUID observationId, DiagnosticHypothesis hypothesis, HypothesisEvidenceOutcome outcome) {
    return new HypothesisEvidenceInput(observationId, hypothesis, outcome, INTERACTION_ID, DOMAIN);
  }

  /** {@code count} distinct SUPPORTING/CONTRADICTORY/INCONCLUSIVE observations for one hypothesis. */
  static List<HypothesisEvidenceInput> observations(
      DiagnosticHypothesis hypothesis, int supporting, int contradictory, int inconclusive) {
    List<HypothesisEvidenceInput> result = new java.util.ArrayList<>();
    for (int i = 0; i < supporting; i++) {
      result.add(evidence(hypothesis, HypothesisEvidenceOutcome.SUPPORTING));
    }
    for (int i = 0; i < contradictory; i++) {
      result.add(evidence(hypothesis, HypothesisEvidenceOutcome.CONTRADICTORY));
    }
    for (int i = 0; i < inconclusive; i++) {
      result.add(evidence(hypothesis, HypothesisEvidenceOutcome.INCONCLUSIVE));
    }
    return result;
  }

  static HypothesisUncertaintyContext context(
      List<DiagnosticHypothesis> hypotheses, List<HypothesisEvidenceInput> evidence) {
    return new HypothesisUncertaintyContext(
        INTERACTION_ID, DOMAIN, hypotheses.stream().map(HypothesisUncertaintyTestFixtures::candidate).toList(),
        evidence);
  }
}
