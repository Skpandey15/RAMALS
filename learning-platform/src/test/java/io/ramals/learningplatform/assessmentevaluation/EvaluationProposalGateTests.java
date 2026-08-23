package io.ramals.learningplatform.assessmentevaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.ai.contract.AiEvaluatedResponseType;
import io.ramals.learningplatform.ai.contract.AssessmentEvaluationContext;
import io.ramals.learningplatform.ai.contract.AssessmentEvaluationRequest;
import io.ramals.learningplatform.ai.contract.AssessmentRubricDimension;
import io.ramals.learningplatform.ai.contract.Constraints;
import io.ramals.learningplatform.ai.contract.InteractionClass;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationProposal.Dimension;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationProposal.MalformedProposalException;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationProposal.RuntimeIdentity;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Decision;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.DeterministicCheck;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Outcome;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Reason;
import io.ramals.learningplatform.grounding.GroundedContext;
import io.ramals.learningplatform.grounding.GroundedContextItem;
import io.ramals.learningplatform.grounding.GroundedContextItem.ContextAuthority;
import io.ramals.learningplatform.grounding.GroundedContextItem.SourceType;
import io.ramals.learningplatform.grounding.GroundedContextValidator;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** M2-T12 gate coverage for F03-F06, F08 and F09. */
class EvaluationProposalGateTests {

  private static final Instant NOW = Instant.parse("2026-08-23T03:00:00Z");
  private static final String CONTEXT_ID = "evaluation-context-v7";
  private static final String ANSWER_EVIDENCE = "answer-v7-evidence";
  private static final String RUBRIC_EVIDENCE = "rubric-accuracy-v3";
  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  private final EvaluationProposalGate gate =
      new EvaluationProposalGate(
          new GroundedContextValidator(JsonMapper.builder().findAndAddModules().build()));

  @Test
  void aGroundedRubricBoundProposalIsAcceptedAndAloneAllowsAuthoritativeEffect() {
    Decision decision =
        gate.evaluate(proposal("3", "4", "0.8500"), request(context()),
            DeterministicCheck.notApplicable(), NOW);

    assertThat(decision.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decision.reasons()).containsExactly(Reason.ACCEPTED);
    assertThat(decision.referencedEvidenceIds())
        .containsExactlyInAnyOrder(ANSWER_EVIDENCE, RUBRIC_EVIDENCE);
    assertThat(decision.allowsAuthoritativeEffect()).isTrue();
  }

  @Test
  void f03_anInventedRubricDimensionIsRejected() {
    AssessmentEvaluationProposal proposal =
        proposalWithDimensions(
            "0.8500",
            List.of(
                dimension("accuracy", "3", "4", ANSWER_EVIDENCE, RUBRIC_EVIDENCE),
                dimension("style-points", "1", "2", ANSWER_EVIDENCE, RUBRIC_EVIDENCE)));

    Decision decision =
        gate.evaluate(proposal, request(context()), DeterministicCheck.notApplicable(), NOW);

    assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(decision.reasons()).contains(Reason.RUBRIC_DIMENSIONS_MISMATCH);
    assertThat(decision.allowsAuthoritativeEffect()).isFalse();
  }

  @Test
  void f03_aDuplicateRubricDimensionIsRejectedRatherThanNormalized() {
    AssessmentEvaluationProposal proposal =
        proposalWithDimensions(
            "0.8500",
            List.of(
                dimension("accuracy", "3", "4", ANSWER_EVIDENCE, RUBRIC_EVIDENCE),
                dimension("accuracy", "2", "4", ANSWER_EVIDENCE, RUBRIC_EVIDENCE)));

    Decision decision =
        gate.evaluate(proposal, request(context()), DeterministicCheck.notApplicable(), NOW);

    assertThat(decision.reasons()).contains(Reason.RUBRIC_DIMENSION_DUPLICATE);
    assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
  }

  @Test
  void f04_anOutOfRangeScoreIsRejected() {
    Decision decision =
        gate.evaluate(proposal("5", "4", "0.8500"), request(context()),
            DeterministicCheck.notApplicable(), NOW);

    assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(decision.reasons()).contains(Reason.RUBRIC_SCORE_OUT_OF_RANGE);
  }

  @Test
  void f04_aModelCannotRedefineTheApprovedMaximum() {
    Decision decision =
        gate.evaluate(proposal("3", "10", "0.8500"), request(context()),
            DeterministicCheck.notApplicable(), NOW);

    assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(decision.reasons()).contains(Reason.RUBRIC_MAX_SCORE_MISMATCH);
  }

  @Test
  void f05_unsupportedFeedbackEvidenceIsRejected() {
    AssessmentEvaluationProposal proposal =
        new AssessmentEvaluationProposal(
            AssessmentEvaluationProposal.CONTRACT_VERSION,
            "proposal-1",
            "request-1",
            "run-1",
            CONTEXT_ID,
            "answer-v7",
            "rubric-v3",
            List.of(dimension("accuracy", "3", "4", ANSWER_EVIDENCE, RUBRIC_EVIDENCE)),
            "Grounded feedback.",
            Set.of(ANSWER_EVIDENCE, "not-in-context"),
            new BigDecimal("0.8500"));

    Decision decision =
        gate.evaluate(proposal, request(context()), DeterministicCheck.notApplicable(), NOW);

    assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(decision.reasons()).contains(Reason.EVIDENCE_REFERENCE_UNKNOWN);
  }

  @Test
  void f05_aModelSummaryCannotSupportAProposedScore() {
    GroundedContext context =
        context(
            item(
                "summary-1",
                "answer-v7",
                "SUMMARY",
                "summary",
                ContextAuthority.MODEL_GENERATED_SUMMARY));
    AssessmentEvaluationProposal proposal =
        proposalWithDimensions(
            "0.8500",
            List.of(
                dimension(
                    "accuracy", "3", "4", ANSWER_EVIDENCE, RUBRIC_EVIDENCE, "summary-1")));

    Decision decision =
        gate.evaluate(proposal, request(context), DeterministicCheck.notApplicable(), NOW);

    assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(decision.reasons()).contains(Reason.EVIDENCE_REFERENCE_NON_AUTHORITATIVE);
  }

  @Test
  void lowConfidenceRoutesToManualReviewAndCannotAffectMastery() {
    Decision decision =
        gate.evaluate(proposal("3", "4", "0.6900"), request(context()),
            DeterministicCheck.notApplicable(), NOW);

    assertThat(decision.outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
    assertThat(decision.reasons()).containsExactly(Reason.CONFIDENCE_BELOW_POLICY);
    assertThat(decision.allowsAuthoritativeEffect()).isFalse();
  }

  @Test
  void f06_aDeterministicConflictIsRecordedAndCannotBeSilentlyAccepted() {
    Decision decision =
        gate.evaluate(
            proposal("3", "4", "0.8500"),
            request(context()),
            DeterministicCheck.conflicts("DETERMINISTIC_FACT_DISAGREES"),
            NOW);

    assertThat(decision.outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
    assertThat(decision.reasons()).containsExactly(Reason.DETERMINISTIC_CONFLICT);
    assertThat(decision.deterministicCheck().reasonCode())
        .isEqualTo("DETERMINISTIC_FACT_DISAGREES");
    assertThat(decision.allowsAuthoritativeEffect()).isFalse();
  }

  @Test
  void aConflictIsStillVisibleWhenAnotherRuleRequiresRejection() {
    Decision decision =
        gate.evaluate(
            proposal("8", "4", "0.8500"),
            request(context()),
            DeterministicCheck.conflicts("DETERMINISTIC_FACT_DISAGREES"),
            NOW);

    assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(decision.reasons())
        .contains(Reason.DETERMINISTIC_CONFLICT, Reason.RUBRIC_SCORE_OUT_OF_RANGE);
  }

  @Test
  void f09_theExactAnswerAndRubricVersionsMustBeGrounded() {
    List<GroundedContextItem> wrong = new ArrayList<>(context().items());
    wrong.set(
        0,
        item(
            ANSWER_EVIDENCE,
            "answer-v6",
            "ANSWER_VERSION",
            "answer-v6",
            ContextAuthority.AUTHORITATIVE_FACT));

    Decision decision =
        gate.evaluate(proposal("3", "4", "0.8500"), request(contextFrom(wrong)),
            DeterministicCheck.notApplicable(), NOW);

    assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(decision.reasons()).contains(Reason.ANSWER_NOT_GROUNDED);
  }

  @Test
  void modelAuthoredClaimsOfFinalAuthorityAreRejected() {
    AssessmentEvaluationProposal proposal =
        new AssessmentEvaluationProposal(
            AssessmentEvaluationProposal.CONTRACT_VERSION,
            "proposal-1",
            "request-1",
            "run-1",
            CONTEXT_ID,
            "answer-v7",
            "rubric-v3",
            List.of(dimension("accuracy", "3", "4", ANSWER_EVIDENCE, RUBRIC_EVIDENCE)),
            "Your final score has been recorded.",
            Set.of(ANSWER_EVIDENCE),
            new BigDecimal("0.8500"));

    Decision decision =
        gate.evaluate(proposal, request(context()), DeterministicCheck.notApplicable(), NOW);

    assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(decision.reasons()).contains(Reason.POLICY_AUTHORITY_CLAIM);
  }

  @Test
  void f08_sameProposalStateAndComparisonYieldTheSameDecision() {
    AssessmentEvaluationProposal proposal = proposal("3", "4", "0.8500");
    AssessmentEvaluationRequest request = request(context());

    assertThat(gate.evaluate(proposal, request, DeterministicCheck.notApplicable(), NOW))
        .isEqualTo(gate.evaluate(proposal, request, DeterministicCheck.notApplicable(), NOW));
  }

  @Test
  void parserBindsEveryRuntimeOwnedIdentity() {
    AssessmentEvaluationProposal parsed =
        AssessmentEvaluationProposal.parse(payload(), identity());

    assertThat(parsed.proposalId()).isEqualTo("proposal-1");
    assertThat(parsed.requestId()).isEqualTo("request-1");
    assertThat(parsed.agentRunId()).isEqualTo("run-1");
    assertThat(parsed.contextId()).isEqualTo(CONTEXT_ID);
    assertThat(parsed.answerVersion()).isEqualTo("answer-v7");
    assertThat(parsed.rubricVersion()).isEqualTo("rubric-v3");
  }

  @Test
  void parserRejectsAForgedAnswerVersionWithAStableReasonCode() {
    Map<String, Object> payload = payload();
    payload.put("answerVersion", "answer-v999");

    assertThatThrownBy(() -> AssessmentEvaluationProposal.parse(payload, identity()))
        .isInstanceOfSatisfying(
            MalformedProposalException.class,
            failure ->
                assertThat(failure.reasonCode())
                    .isEqualTo("EVALUATION_ANSWER_VERSION_MISMATCH"));
  }

  @Test
  void parserRejectsUnknownFieldsInsteadOfIgnoringContractDrift() {
    Map<String, Object> payload = payload();
    payload.put("masteryUpdate", Map.of("status", "MASTERED"));

    assertThatThrownBy(() -> AssessmentEvaluationProposal.parse(payload, identity()))
        .isInstanceOfSatisfying(
            MalformedProposalException.class,
            failure ->
                assertThat(failure.reasonCode())
                    .isEqualTo("EVALUATION_PAYLOAD_FIELDS_INVALID"));
  }

  @Test
  void frozenV1OptionalEvidenceFixtureIsAcceptedByTheParserThenRejectedByTheGate()
      throws IOException {
    AssessmentEvaluationProposal parsed =
        AssessmentEvaluationProposal.parse(
            fixture("assessment-evaluation-proposal-v1-optional-evidence.json"), identity());

    assertThat(parsed.evidenceIds()).isEmpty();
    assertThat(parsed.dimensions()).singleElement().satisfies(dimension ->
        assertThat(dimension.evidenceIds()).isEmpty());
    assertThat(gate.evaluate(parsed, request(context()), DeterministicCheck.notApplicable(), NOW))
        .satisfies(decision -> {
          assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
          assertThat(decision.reasons())
              .contains(Reason.DIMENSION_EVIDENCE_INCOMPLETE, Reason.FEEDBACK_EVIDENCE_INCOMPLETE);
        });
  }

  @Test
  void duplicateEvidenceFixtureIsRejectedByTheJavaParser() throws IOException {
    Map<String, Object> invalid =
        fixture("assessment-evaluation-proposal-v1-duplicate-evidence.invalid.json");

    assertThatThrownBy(() -> AssessmentEvaluationProposal.parse(invalid, identity()))
        .isInstanceOfSatisfying(
            MalformedProposalException.class,
            failure ->
                assertThat(failure.reasonCode())
                    .isEqualTo("EVALUATION_FEEDBACK_EVIDENCE_INVALID"));
  }

  @Test
  void explicitNullEvidenceDoesNotMasqueradeAsAnAbsentOptionalV1Field() {
    Map<String, Object> payload = payload();
    payload.put("evidenceIds", null);

    assertThatThrownBy(() -> AssessmentEvaluationProposal.parse(payload, identity()))
        .isInstanceOfSatisfying(
            MalformedProposalException.class,
            failure ->
                assertThat(failure.reasonCode())
                    .isEqualTo("EVALUATION_FEEDBACK_EVIDENCE_INVALID"));
  }

  @Test
  void parserBoundsNumericInputBeforeBigDecimalConstruction() {
    Map<String, Object> payload = payload();
    payload.put("confidence", BigInteger.TEN.pow(1_000));

    assertThatThrownBy(() -> AssessmentEvaluationProposal.parse(payload, identity()))
        .isInstanceOfSatisfying(
            MalformedProposalException.class,
            failure ->
                assertThat(failure.reasonCode()).isEqualTo("EVALUATION_CONFIDENCE_INVALID"));
  }

  @Test
  void invalidConfidenceIsRejectedAndNormalizedForDurableAudit() {
    for (String invalid : List.of("-1", "1.5", "1E+1000")) {
      Decision decision =
          gate.evaluate(
              proposal("3", "4", invalid),
              request(context()),
              DeterministicCheck.notApplicable(),
              NOW);

      assertThat(decision.outcome()).as("confidence %s", invalid).isEqualTo(Outcome.REJECTED);
      assertThat(decision.reasons()).contains(Reason.PROPOSAL_INVALID);
      assertThat(decision.confidence()).isNull();
    }
  }

  @Test
  void malformedVersionStateIsRejectedWithoutThrowing() {
    AssessmentEvaluationContext valid = evaluationContext();
    AssessmentEvaluationContext missingAnswerVersion =
        new AssessmentEvaluationContext(
            valid.responseType(),
            null,
            valid.rubricVersion(),
            valid.answerEvidenceId(),
            valid.answerText(),
            valid.rubricDimensions());
    AssessmentEvaluationContext missingRubricVersion =
        new AssessmentEvaluationContext(
            valid.responseType(),
            valid.answerVersion(),
            null,
            valid.answerEvidenceId(),
            valid.answerText(),
            valid.rubricDimensions());
    List<GroundedContextItem> malformedItems = new ArrayList<>(context().items());
    GroundedContextItem answer = malformedItems.getFirst();
    malformedItems.set(
        0,
        new GroundedContextItem(
            answer.evidenceId(),
            answer.sourceType(),
            null,
            answer.authority(),
            answer.factType(),
            answer.value(),
            answer.observedAt(),
            answer.expiresAt()));

    assertRejected(gate.evaluate(
        proposal("3", "4", "0.85"), request(context(), missingAnswerVersion),
        DeterministicCheck.notApplicable(), NOW));
    assertRejected(gate.evaluate(
        proposal("3", "4", "0.85"), request(context(), missingRubricVersion),
        DeterministicCheck.notApplicable(), NOW));
    assertRejected(gate.evaluate(
        proposal("3", "4", "0.85"), request(contextFrom(malformedItems)),
        DeterministicCheck.notApplicable(), NOW));
  }

  static AssessmentEvaluationRequest request(GroundedContext context) {
    return request(context, evaluationContext());
  }

  private static AssessmentEvaluationRequest request(
      GroundedContext context, AssessmentEvaluationContext evaluation) {
    return new AssessmentEvaluationRequest(
        AssessmentEvaluationRequest.CONTRACT_VERSION,
        "interaction-1",
        "request-1",
        new Constraints(
            InteractionClass.ASSESSMENT_PROPOSAL,
            8_000,
            1_200,
            List.of(),
            EvaluationProposalGate.REQUEST_POLICY),
        evaluation,
        context);
  }

  static AssessmentEvaluationContext evaluationContext() {
    return new AssessmentEvaluationContext(
        AiEvaluatedResponseType.FREE_TEXT,
        "answer-v7",
        "rubric-v3",
        ANSWER_EVIDENCE,
        "Replication maintains copies; quorum prevents stale writes.",
        List.of(
            new AssessmentRubricDimension(
                "accuracy",
                new BigDecimal("4"),
                "Technical claims about replication are correct.",
                RUBRIC_EVIDENCE)));
  }

  static GroundedContext context(GroundedContextItem... extras) {
    List<GroundedContextItem> items = new ArrayList<>();
    items.add(
        item(
            ANSWER_EVIDENCE,
            "answer-v7",
            "ANSWER_VERSION",
            "answer-v7",
            ContextAuthority.AUTHORITATIVE_FACT));
    items.add(
        item(
            RUBRIC_EVIDENCE,
            "rubric-v3",
            "RUBRIC_DIMENSION",
            "accuracy",
            ContextAuthority.AUTHORITATIVE_FACT));
    items.addAll(List.of(extras));
    return contextFrom(items);
  }

  private static GroundedContext contextFrom(List<GroundedContextItem> items) {
    return new GroundedContext(
        GroundedContext.CONTRACT_VERSION,
        CONTEXT_ID,
        "opaque-learner-ref",
        NOW,
        NOW.plusSeconds(600),
        EvaluationProposalGate.REQUEST_POLICY,
        items);
  }

  private static GroundedContextItem item(
      String evidenceId,
      String sourceVersion,
      String factType,
      String value,
      ContextAuthority authority) {
    return new GroundedContextItem(
        evidenceId,
        SourceType.ASSESSMENT,
        sourceVersion,
        authority,
        factType,
        value,
        NOW,
        null);
  }

  static AssessmentEvaluationProposal proposal(String score, String maxScore, String confidence) {
    return proposalWithDimensions(
        confidence,
        List.of(dimension("accuracy", score, maxScore, ANSWER_EVIDENCE, RUBRIC_EVIDENCE)));
  }

  private static AssessmentEvaluationProposal proposalWithDimensions(
      String confidence, List<Dimension> dimensions) {
    return new AssessmentEvaluationProposal(
        AssessmentEvaluationProposal.CONTRACT_VERSION,
        "proposal-1",
        "request-1",
        "run-1",
        CONTEXT_ID,
        "answer-v7",
        "rubric-v3",
        dimensions,
        "The answer is mostly accurate and should explain quorum acknowledgement more precisely.",
        Set.of(ANSWER_EVIDENCE),
        new BigDecimal(confidence));
  }

  private static Dimension dimension(
      String id, String score, String maxScore, String... evidenceIds) {
    return new Dimension(
        id,
        new BigDecimal(score),
        new BigDecimal(maxScore),
        "The cited answer and rubric support this score.",
        Set.of(evidenceIds));
  }

  static Map<String, Object> payload() {
    Map<String, Object> dimension = new LinkedHashMap<>();
    dimension.put("dimensionId", "accuracy");
    dimension.put("score", 3);
    dimension.put("maxScore", 4);
    dimension.put("reason", "The cited answer and rubric support this score.");
    dimension.put("evidenceIds", List.of(ANSWER_EVIDENCE, RUBRIC_EVIDENCE));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("contractVersion", AssessmentEvaluationProposal.CONTRACT_VERSION);
    payload.put("proposalId", "proposal-1");
    payload.put("requestId", "request-1");
    payload.put("agentRunId", "run-1");
    payload.put("answerVersion", "answer-v7");
    payload.put("rubricVersion", "rubric-v3");
    payload.put("dimensions", List.of(dimension));
    payload.put("feedback", "The answer is mostly accurate.");
    payload.put("evidenceIds", List.of(ANSWER_EVIDENCE));
    payload.put("confidence", 0.85);
    return payload;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> fixture(String filename) throws IOException {
    Path path = Path.of("..", "contracts", "golden", filename);
    return JSON.readValue(Files.readString(path), Map.class);
  }

  private static void assertRejected(Decision decision) {
    assertThat(decision.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(decision.allowsAuthoritativeEffect()).isFalse();
  }

  private static RuntimeIdentity identity() {
    return new RuntimeIdentity(
        "proposal-1", "request-1", "run-1", CONTEXT_ID, "answer-v7", "rubric-v3");
  }
}
