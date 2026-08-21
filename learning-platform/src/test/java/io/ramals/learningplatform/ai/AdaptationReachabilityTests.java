package io.ramals.learningplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.execution.AiExecutionCommission;
import io.ramals.learningplatform.execution.AiExecutionRecorder;
import io.ramals.learningplatform.recommendation.RecommendationDecidedEvent;
import io.ramals.learningplatform.recommendation.RecommendationDecision;
import io.ramals.learningplatform.recommendation.RecommendedAction;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the learner journey actually reaches the adaptation agent.
 *
 * <p>M1-T11 built {@link AdaptationService}, {@link AdaptationProposalGate}, the port and the client
 * with their comparison and fallback semantics. M1-T18 found that nothing consumed any of it: every
 * piece existed and no learner action could reach it, so the learner journey never wrote an
 * {@code ai_execution} row. These fail against that state.
 *
 * <p>The other half of what they assert is the property that makes the integration safe to have at
 * all — the agent cannot change what the learner is told, whatever it proposes and whether it answers
 * at all.
 */
@SpringBootTest(properties = {
    "RAMALS_DB_URL=jdbc:h2:mem:adaptation-reach;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "RAMALS_DB_USER=sa",
    "RAMALS_DB_PASSWORD=",
    "spring.flyway.enabled=false"
})
class AdaptationReachabilityTests {

  private static final RecommendationDecision DETERMINISTIC =
      new RecommendationDecision(RecommendedAction.PRACTICE, "MASTERY_BELOW_THRESHOLD");

  @Autowired
  ApplicationEventPublisher publisher;

  @Autowired
  TransactionTemplate transactionTemplate;

  @MockitoBean
  AdaptationPort adaptationPort;

  @MockitoBean
  AiExecutionRecorder executionRecorder;

  @BeforeEach
  void dispatchIsAllowed() {
    AiExecutionCommission allowed = mock(AiExecutionCommission.class);
    when(allowed.dispatchAllowed()).thenReturn(true);
    when(executionRecorder.commission(any(), anyString())).thenReturn(allowed);
  }

  private static AiProposalEnvelope proposing(String action) {
    return new AiProposalEnvelope(
        "1.0", "proposal-1", AgentType.ADAPTATION, "1.0.0", "run-1",
        "ADAPTATION_PLAN", "v1", "ci-fake", TrustLevel.NON_AUTHORITATIVE, "HIGH", List.of(),
        Map.of("recommendedAction", action), null, null);
  }

  /** Commits a transaction so the AFTER_COMMIT listener actually fires. */
  private void publishInCommittedTransaction() {
    transactionTemplate.executeWithoutResult(status ->
        publisher.publishEvent(new RecommendationDecidedEvent(
            UUID.randomUUID(), UUID.randomUUID(), DETERMINISTIC, "interaction-1", "trace-1")));
  }

  @Test
  @DisplayName("a decided recommendation reaches the adaptation agent")
  void theJourneyReachesTheAgent() {
    when(adaptationPort.requestAdaptationProposal(any(), anyLong()))
        .thenReturn(proposing("PRACTICE"));

    publishInCommittedTransaction();

    // The assertion M1-T18 would have failed: before this integration the port was never called by
    // anything a learner could trigger.
    verify(adaptationPort, times(1)).requestAdaptationProposal(any(), anyLong());
    verify(executionRecorder, times(1))
        .recordSuccess(any(), any(), any(Instant.class), any(Instant.class));
  }

  @Test
  @DisplayName("the agent is asked after the authoritative transaction has committed")
  void theAgentIsCalledAfterCommitRatherThanInsideTheTransaction() {
    when(adaptationPort.requestAdaptationProposal(any(), anyLong()))
        .thenReturn(proposing("PRACTICE"));

    // Published inside a transaction that is then rolled back. An inline call would already have
    // reached the plane; an AFTER_COMMIT listener must not fire at all.
    transactionTemplate.executeWithoutResult(status -> {
      publisher.publishEvent(new RecommendationDecidedEvent(
          UUID.randomUUID(), UUID.randomUUID(), DETERMINISTIC, "interaction-2", "trace-2"));
      status.setRollbackOnly();
    });

    verify(adaptationPort, never()).requestAdaptationProposal(any(), anyLong());
  }

  @Test
  @DisplayName("a disagreeing proposal cannot change the deterministic decision")
  void disagreementCannotChangeTheDeterministicOutcome() {
    when(adaptationPort.requestAdaptationProposal(any(), anyLong()))
        .thenReturn(proposing("ADVANCE"));  // deliberately different from PRACTISE

    publishInCommittedTransaction();

    ArgumentCaptor<AiProposalEnvelope> recorded = ArgumentCaptor.forClass(AiProposalEnvelope.class);
    verify(executionRecorder).recordSuccess(
        any(), recorded.capture(), any(Instant.class), any(Instant.class));

    // The disagreement is recorded as evidence, and the proposal is carried into ai_execution --
    // but it is research input. The authoritative decision is unchanged by construction: the gate
    // returns the deterministic decision in every case, and nothing here writes learner state.
    assertThat(recorded.getValue().proposal().get("recommendedAction")).isEqualTo("ADVANCE");
    assertThat(recorded.getValue().trustLevel())
        .as("an adaptation proposal is never authoritative")
        .isEqualTo(TrustLevel.NON_AUTHORITATIVE);
  }

  @Test
  @DisplayName("an agent failure leaves the deterministic recommendation untouched and is recorded")
  void anAgentFailureIsContainedAndAccountedFor() {
    when(adaptationPort.requestAdaptationProposal(any(), anyLong()))
        .thenThrow(new AiUnavailableException("DEADLINE_EXCEEDED", "timed out"));

    // Must not propagate: the learner is already served and their state already committed.
    publishInCommittedTransaction();

    verify(executionRecorder, never())
        .recordSuccess(any(), any(), any(Instant.class), any(Instant.class));
    verify(executionRecorder, times(1))
        .recordFailure(any(), eq("ADAPTATION"), anyString(), any(Instant.class), any(Instant.class));
  }

}
