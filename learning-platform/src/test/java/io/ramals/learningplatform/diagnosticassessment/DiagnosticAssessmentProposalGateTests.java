package io.ramals.learningplatform.diagnosticassessment;

import static org.assertj.core.api.Assertions.assertThat;

import io.ramals.learningplatform.grounding.GroundedContext;
import io.ramals.learningplatform.grounding.GroundedContextItem;
import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import io.ramals.learningplatform.grounding.GroundedContextValidator;
import io.ramals.learningplatform.grounding.ProposalGateReason;
import io.ramals.learningplatform.grounding.ProposalGateResult;
import io.ramals.learningplatform.grounding.ProposalGroundingGate;
import io.ramals.learningplatform.grounding.ProposalGroundingPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * M2-T09 gate rules: the diagnostic semantics layered on the generic grounding gate.
 *
 * <p>Covers E05, E06 and E09 from the MVP-2 matrix plus the structural rules the task enumerates.
 * E08 and the transport failures are service-level and live in {@link DiagnosticAssessmentServiceTests}.
 */
class DiagnosticAssessmentProposalGateTests {

  private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
  private static final String CONTEXT_ID = "ctx-diagnostic-1";

  private final DiagnosticAssessmentProposalGate gate =
      new DiagnosticAssessmentProposalGate(
          new ProposalGroundingGate(
              new GroundedContextValidator(JsonMapper.builder().findAndAddModules().build()),
              new ProposalGroundingPolicy()));

  // -- accepted readings ---------------------------------------------------------------------------

  @Test
  void aWeakReadingBackedByOneAuthoritativeReferenceIsAccepted() {
    ProposalGateResult result =
        gate.evaluate(
            proposal("0.8000", List.of(diagnosis("offsets", "WEAK", "e-1"))), context(), NOW);

    assertThat(result.accepted()).isTrue();
    assertThat(result.reasons()).containsExactly(ProposalGateReason.ACCEPTED);
    assertThat(result.referencedEvidenceIds()).contains("e-1");
  }

  @Test
  void aStrongReadingBackedByTwoAuthoritativeReferencesIsAccepted() {
    ProposalGateResult result =
        gate.evaluate(
            proposal("0.8000", List.of(diagnosis("offsets", "STRONG", "e-1", "m-1"))),
            context(),
            NOW);

    assertThat(result.accepted()).isTrue();
  }

  @Test
  void anInconsistentReadingIsAccepted() {
    ProposalGateResult result =
        gate.evaluate(
            proposal("0.8000", List.of(diagnosis("offsets", "INCONSISTENT", "e-1"))), context(), NOW);

    assertThat(result.accepted()).isTrue();
  }

  @Test
  void anInsufficientEvidenceReadingIsAcceptedWhenItDoesNotAlsoClaimCertainty() {
    ProposalGateResult result =
        gate.evaluate(
            proposal("0.7000", List.of(diagnosis("offsets", "INSUFFICIENT_EVIDENCE", "e-1"))),
            context(),
            NOW);

    assertThat(result.accepted()).isTrue();
  }

  // -- E05: schema-valid but unsupported -------------------------------------------------------------

  @Test
  void e05_aSchemaValidClaimCitingEvidenceOutsideTheContextIsRejected() {
    ProposalGateResult result =
        gate.evaluate(
            proposal("0.8000", List.of(diagnosis("offsets", "WEAK", "not-in-context"))),
            context(),
            NOW);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons())
        .contains(
            ProposalGateReason.EVIDENCE_REFERENCE_UNKNOWN, ProposalGateReason.CLAIM_UNSUPPORTED);
  }

  @Test
  void e05_aClaimRestingOnlyOnAModelSummaryIsRejected() {
    GroundedContext withSummary =
        context(
            item("s-1", SourceType.LEARNER_EVIDENCE, ContextAuthority.MODEL_GENERATED_SUMMARY, null));

    ProposalGateResult result =
        gate.evaluate(proposal("0.8000", List.of(diagnosis("offsets", "WEAK", "s-1"))), withSummary,
            NOW);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons()).contains(ProposalGateReason.EVIDENCE_REFERENCE_NON_AUTHORITATIVE);
  }

  // -- E06: confidence policy -------------------------------------------------------------------------

  @Test
  void e06_confidenceBelowTheDeterministicThresholdIsRejected() {
    // The policy minimum for DIAGNOSTIC is 0.65. The model asserting a number is not the model
    // choosing the threshold.
    ProposalGateResult result =
        gate.evaluate(
            proposal("0.6400", List.of(diagnosis("offsets", "WEAK", "e-1"))), context(), NOW);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons()).contains(ProposalGateReason.CONFIDENCE_BELOW_POLICY);
  }

  @Test
  void e06_confidenceExactlyAtTheThresholdIsAccepted() {
    ProposalGateResult result =
        gate.evaluate(
            proposal("0.6500", List.of(diagnosis("offsets", "WEAK", "e-1"))), context(), NOW);

    assertThat(result.accepted()).isTrue();
  }

  // -- E09: business rejection of a structurally valid proposal ------------------------------------------

  @Test
  void e09_strongWithoutSufficientEvidenceIsALegitimateBusinessRejection() {
    ProposalGateResult result =
        gate.evaluate(
            proposal("0.9500", List.of(diagnosis("offsets", "STRONG", "e-1"))), context(), NOW);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons()).containsExactly(ProposalGateReason.EVIDENCE_INSUFFICIENT_FOR_STRONG);
  }

  @Test
  void e09_insufficientEvidenceAssertedWithNearCertaintyIsRejected() {
    ProposalGateResult result =
        gate.evaluate(
            proposal("0.9000", List.of(diagnosis("offsets", "INSUFFICIENT_EVIDENCE", "e-1"))),
            context(),
            NOW);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons())
        .contains(ProposalGateReason.INSUFFICIENT_EVIDENCE_OVERCONFIDENT);
  }

  // -- structural diagnostic rules ------------------------------------------------------------------------

  @Test
  void anUnknownSkillCodeIsRejectedWhenTheContextNamesSkills() {
    ProposalGateResult result =
        gate.evaluate(
            proposal("0.8000", List.of(diagnosis("skill-that-does-not-exist", "WEAK", "e-1"))),
            context(),
            NOW);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons()).contains(ProposalGateReason.SKILL_NOT_IN_CONTEXT);
  }

  @Test
  void twoClassificationsForOneSkillAreRejectedRatherThanNormalised() {
    ProposalGateResult result =
        gate.evaluate(
            proposal(
                "0.8000",
                List.of(diagnosis("offsets", "WEAK", "e-1"), diagnosis("offsets", "STRONG", "e-1", "m-1"))),
            context(),
            NOW);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons()).contains(ProposalGateReason.CLASSIFICATION_CONFLICT);
  }

  @Test
  void aRecommendedSkillOutsideTheContextIsRejected() {
    DiagnosticAssessmentProposal proposal =
        new DiagnosticAssessmentProposal(
            DiagnosticAssessmentProposal.CONTRACT_VERSION,
            "p-1",
            "r-1",
            "run-1",
            CONTEXT_ID,
            List.of(diagnosis("offsets", "WEAK", "e-1")),
            List.of("no-such-skill"),
            new java.math.BigDecimal("0.8000"));

    ProposalGateResult result = gate.evaluate(proposal, context(), NOW);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons()).contains(ProposalGateReason.RECOMMENDATION_INVALID);
  }

  @Test
  void anUnsupportedProposalContractVersionFailsClosedBeforeAnyOtherRule() {
    DiagnosticAssessmentProposal proposal =
        new DiagnosticAssessmentProposal(
            "9.9", "p-1", "r-1", "run-1", CONTEXT_ID,
            List.of(diagnosis("offsets", "WEAK", "e-1")), List.of(),
            new java.math.BigDecimal("0.8000"));

    ProposalGateResult result = gate.evaluate(proposal, context(), NOW);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons()).containsExactly(ProposalGateReason.PROPOSAL_VERSION_UNSUPPORTED);
  }

  @Test
  void aProposalJudgedAgainstADifferentContextIsRejected() {
    DiagnosticAssessmentProposal proposal =
        new DiagnosticAssessmentProposal(
            DiagnosticAssessmentProposal.CONTRACT_VERSION, "p-1", "r-1", "run-1",
            "some-other-context", List.of(diagnosis("offsets", "WEAK", "e-1")), List.of(),
            new java.math.BigDecimal("0.8000"));

    ProposalGateResult result = gate.evaluate(proposal, context(), NOW);

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons()).contains(ProposalGateReason.CONTEXT_ID_MISMATCH);
  }

  @Test
  void anExpiredContextIsRejected() {
    ProposalGateResult result =
        gate.evaluate(
            proposal("0.8000", List.of(diagnosis("offsets", "WEAK", "e-1"))),
            context(),
            NOW.plusSeconds(3_600));

    assertThat(result.accepted()).isFalse();
    assertThat(result.reasons()).contains(ProposalGateReason.GROUNDING_INVALID);
  }

  // -- E08 determinism + perturbation -------------------------------------------------------------------

  @Test
  void theSameProposalAgainstTheSameStateYieldsTheSameDecision() {
    DiagnosticAssessmentProposal proposal =
        proposal("0.8000", List.of(diagnosis("offsets", "WEAK", "e-1")));

    assertThat(gate.evaluate(proposal, context(), NOW))
        .isEqualTo(gate.evaluate(proposal, context(), NOW));
  }

  @Test
  void perturbingTheEvidenceIdentifierFlipsTheDecisionAndRestoringItFlipsItBack() {
    // The control proven live, in one test. A gate whose acceptance does not move when the thing it
    // checks is mutated is not checking it.
    ProposalGateResult before =
        gate.evaluate(proposal("0.8000", List.of(diagnosis("offsets", "WEAK", "e-1"))), context(), NOW);
    ProposalGateResult mutated =
        gate.evaluate(
            proposal("0.8000", List.of(diagnosis("offsets", "WEAK", "e-999"))), context(), NOW);
    ProposalGateResult restored =
        gate.evaluate(proposal("0.8000", List.of(diagnosis("offsets", "WEAK", "e-1"))), context(), NOW);

    assertThat(before.accepted()).isTrue();
    assertThat(mutated.accepted()).isFalse();
    assertThat(mutated.reasons()).contains(ProposalGateReason.EVIDENCE_REFERENCE_UNKNOWN);
    assertThat(restored.accepted()).isTrue();
  }

  // -- fixtures ---------------------------------------------------------------------------------------

  private static DiagnosticAssessmentProposal proposal(
      String confidence, List<DiagnosticAssessmentProposal.Diagnosis> diagnoses) {
    return new DiagnosticAssessmentProposal(
        DiagnosticAssessmentProposal.CONTRACT_VERSION,
        "p-1",
        "r-1",
        "run-1",
        CONTEXT_ID,
        diagnoses,
        List.of(),
        new java.math.BigDecimal(confidence));
  }

  private static DiagnosticAssessmentProposal.Diagnosis diagnosis(
      String skill, String classification, String... evidenceIds) {
    return new DiagnosticAssessmentProposal.Diagnosis(
        skill,
        DiagnosticAssessmentProposal.Classification.valueOf(classification),
        "Because the recorded evidence says so.",
        java.util.Set.of(evidenceIds));
  }

  /** A context carrying every required source, and naming one skill. */
  static GroundedContext context(GroundedContextItem... extra) {
    List<GroundedContextItem> items = new ArrayList<>();
    items.add(item("e-1", SourceType.LEARNER_EVIDENCE, ContextAuthority.AUTHORITATIVE_FACT, null));
    items.add(item("m-1", SourceType.MASTERY, ContextAuthority.AUTHORITATIVE_FACT, null));
    items.add(item("p-1", SourceType.CURRICULUM_POLICY, ContextAuthority.AUTHORITATIVE_FACT, null));
    items.add(item("s-skill", SourceType.SKILL_GRAPH, ContextAuthority.AUTHORITATIVE_FACT, "offsets"));
    items.addAll(List.of(extra));
    return new GroundedContext(
        GroundedContext.CONTRACT_VERSION,
        CONTEXT_ID,
        "opaque-learner",
        NOW,
        NOW.plusSeconds(600),
        "POLICY_V1",
        items);
  }

  static GroundedContextItem item(
      String evidenceId, SourceType source, ContextAuthority authority, String skillCode) {
    boolean namesSkill = skillCode != null;
    return new GroundedContextItem(
        evidenceId,
        source,
        "v1",
        authority,
        namesSkill ? "SKILL_CODE" : "MASTERY_SCORE",
        namesSkill ? skillCode : "0.2100",
        NOW,
        null);
  }

  /** Exposed so the service tests can build an agent payload from the same shapes. */
  static Map<String, Object> payload(String confidence, List<Map<String, Object>> diagnoses) {
    Map<String, Object> map = new HashMap<>();
    map.put("contractVersion", DiagnosticAssessmentProposal.CONTRACT_VERSION);
    map.put("diagnoses", diagnoses);
    map.put("recommendedNextSkills", List.of());
    map.put("confidence", confidence);
    return map;
  }
}
