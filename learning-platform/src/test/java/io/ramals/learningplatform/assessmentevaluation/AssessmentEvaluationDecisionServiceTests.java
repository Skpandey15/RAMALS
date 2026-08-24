package io.ramals.learningplatform.assessmentevaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AssessmentEvaluationRequest;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.AcceptedEvaluationDecision;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.EvaluationDecisionRecord;
import io.ramals.learningplatform.assessmentevaluation.AssessmentEvaluationDecisionPort.EvaluationTarget;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Decision;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.DeterministicCheck;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Outcome;
import io.ramals.learningplatform.assessmentevaluation.EvaluationProposalGate.Reason;
import io.ramals.learningplatform.grounding.GroundedContextValidator;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

/** Service-boundary proof that every returned proposal becomes one traceable gate decision. */
class AssessmentEvaluationDecisionServiceTests {

  private final RecordingDecisions decisions = new RecordingDecisions();
  private final AssessmentEvaluationDecisionService service =
      new AssessmentEvaluationDecisionService(
          new EvaluationProposalGate(
              new GroundedContextValidator(JsonMapper.builder().findAndAddModules().build())),
          decisions,
          Clock.fixed(
              java.time.Instant.parse("2026-08-23T03:00:00Z"), ZoneOffset.UTC));

  @Test
  void acceptedProposalIsPersistedWithAnswerRubricExecutionAndGateIdentity() {
    Decision result =
        service.decide(
            envelope(AssessmentEvaluationProposalTestsPayload.valid()),
            request(),
            DeterministicCheck.notApplicable(),
            new EvaluationTarget(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID()));

    assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
    assertThat(decisions.records).singleElement().satisfies(record -> {
      assertThat(record.proposalId()).isEqualTo("proposal-1");
      assertThat(record.requestId()).isEqualTo("request-1");
      assertThat(record.agentRunId()).isEqualTo("run-1");
      assertThat(record.contextId()).isEqualTo("evaluation-context-v7");
      assertThat(record.answerEvidenceId()).isEqualTo("answer-v7-evidence");
      assertThat(record.answerVersion()).isEqualTo("answer-v7");
      assertThat(record.rubricVersion()).isEqualTo("rubric-v3");
      assertThat(record.decision().allowsAuthoritativeEffect()).isTrue();
      assertThat(record.parserReasonCode()).isNull();
      assertThat(record.target()).isNotNull();
    });
  }

  @Test
  void acceptedProposalWithoutSpringOwnedTargetFactsFailsClosedBeforePersistence() {
    assertThatThrownBy(
            () ->
                service.decide(
                    envelope(AssessmentEvaluationProposalTestsPayload.valid()),
                    request(),
                    DeterministicCheck.notApplicable()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("an accepted evaluation decision requires complete Spring-owned target facts");
    assertThat(decisions.records).isEmpty();
  }

  @Test
  void malformedPayloadIsPersistedAsARejectionWithoutModelContentInTheReason() {
    Decision result =
        service.decide(
            envelope(Map.of("dimensions", "not-an-array")),
            request(),
            DeterministicCheck.notApplicable());

    assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(result.reasons()).containsExactly(Reason.PROPOSAL_INVALID);
    assertThat(decisions.records).singleElement().satisfies(record -> {
      assertThat(record.parserReasonCode()).isEqualTo("EVALUATION_PAYLOAD_FIELDS_INVALID");
      assertThat(record.decision().feedback()).isNull();
      assertThat(record.decision().dimensions()).isEmpty();
      assertThat(record.decision().allowsAuthoritativeEffect()).isFalse();
    });
  }

  @Test
  void anEnvelopeClaimingAuthoritativeTrustIsRejectedBeforePayloadAdoption() {
    AiProposalEnvelope envelope =
        envelope(AssessmentEvaluationProposalTestsPayload.valid(), TrustLevel.VERIFIED_CONTENT);

    Decision result = service.decide(envelope, request(), DeterministicCheck.notApplicable());

    assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
    assertThat(result.reasons()).containsExactly(Reason.ENVELOPE_TRUST_INVALID);
    assertThat(decisions.records).singleElement().extracting(EvaluationDecisionRecord::parserReasonCode)
        .isEqualTo("EVALUATION_ENVELOPE_TRUST_INVALID");
  }

  @Test
  void deterministicDisagreementSurvivesTheServiceAndPersistenceBoundary() {
    Decision result =
        service.decide(
            envelope(AssessmentEvaluationProposalTestsPayload.valid()),
            request(),
            DeterministicCheck.conflicts("DETERMINISTIC_FACT_DISAGREES"));

    assertThat(result.outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
    assertThat(decisions.records).singleElement().satisfies(record -> {
      assertThat(record.decision().reasons()).contains(Reason.DETERMINISTIC_CONFLICT);
      assertThat(record.decision().deterministicCheck().reasonCode())
          .isEqualTo("DETERMINISTIC_FACT_DISAGREES");
      assertThat(record.decision().allowsAuthoritativeEffect()).isFalse();
    });
  }

  @Test
  void unavailableTraceContextRemainsNullAndIsNotFabricatedFromInteractionIdentity() {
    MDC.remove("traceId");

    service.decide(
        envelope(AssessmentEvaluationProposalTestsPayload.valid()),
        request(),
        DeterministicCheck.notApplicable(),
        new EvaluationTarget(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID()));

    assertThat(decisions.records).singleElement().satisfies(record -> {
      assertThat(record.interactionId()).isEqualTo("interaction-1");
      assertThat(record.traceId()).isNull();
    });
  }

  private static AssessmentEvaluationRequest request() {
    return EvaluationProposalGateTests.request(EvaluationProposalGateTests.context());
  }

  private static AiProposalEnvelope envelope(Map<String, Object> payload) {
    return envelope(payload, TrustLevel.NON_AUTHORITATIVE);
  }

  private static AiProposalEnvelope envelope(
      Map<String, Object> payload, TrustLevel trustLevel) {
    return new AiProposalEnvelope(
        "1.0",
        "proposal-1",
        AgentType.ASSESSMENT,
        "ASSESSMENT_EVALUATION_AGENT_V1",
        "run-1",
        "ASSESSMENT_RUBRIC_EVALUATE",
        "ASSESSMENT_RUBRIC_EVALUATE_V1",
        "assessment-default",
        trustLevel,
        null,
        List.of(),
        payload,
        null,
        null);
  }

  private static final class RecordingDecisions implements AssessmentEvaluationDecisionPort {
    private final List<EvaluationDecisionRecord> records = new ArrayList<>();

    @Override
    public void append(EvaluationDecisionRecord record) {
      records.add(record);
    }

    @Override
    public Optional<AcceptedEvaluationDecision> findAcceptedByRequestId(String requestId) {
      return Optional.empty();
    }
  }

  /** Keeps the service fixture independent from private helpers in the gate test. */
  private static final class AssessmentEvaluationProposalTestsPayload {
    private static Map<String, Object> valid() {
      return EvaluationProposalGateTests.payload();
    }
  }
}
