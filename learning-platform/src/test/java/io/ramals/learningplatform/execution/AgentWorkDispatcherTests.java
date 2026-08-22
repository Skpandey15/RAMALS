package io.ramals.learningplatform.execution;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.ramals.learningplatform.ai.AiUnavailableException;
import io.ramals.learningplatform.ai.FailureOrigin;
import io.ramals.learningplatform.recommendation.RecommendedAction;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentWorkDispatcherTests {

  private final AgentWorkOutboxRepository repository = mock(AgentWorkOutboxRepository.class);
  private final AgentWorkProcessor processor = mock(AgentWorkProcessor.class);
  private final AgentWorkDispatcherProperties properties = new AgentWorkDispatcherProperties();
  private final AgentWorkDispatcher dispatcher = new AgentWorkDispatcher(
      repository, processor, properties, new SimpleMeterRegistry());

  @Test
  void successfulWorkCompletes() {
    ClaimedAgentWork work = work(1);
    dispatcher.dispatch(work);
    verify(processor).process(work);
    verify(repository).complete(work);
  }

  @Test
  void dependencyFailureRetriesWithBoundedBackoff() {
    ClaimedAgentWork work = work(1);
    doThrow(new AiUnavailableException("AI_TRANSPORT_FAILURE", "down", FailureOrigin.DEPENDENCY))
        .when(processor).process(work);
    dispatcher.dispatch(work);
    verify(repository).retry(
        org.mockito.ArgumentMatchers.eq(work),
        org.mockito.ArgumentMatchers.eq("AI_TRANSPORT_FAILURE"),
        org.mockito.ArgumentMatchers.longThat(delay -> delay >= 800 && delay <= 1_200));
  }

  @Test
  void callerFailureIsTerminalWithoutRetry() {
    ClaimedAgentWork work = work(1);
    doThrow(new AiUnavailableException("AI_NOT_CONFIGURED", "off", FailureOrigin.CALLER))
        .when(processor).process(work);
    dispatcher.dispatch(work);
    verify(processor).recordTerminalFailure(work, "AI_NOT_CONFIGURED");
    verify(repository).terminal(work, "AI_NOT_CONFIGURED");
    verifyNoMoreInteractions(repository);
  }

  @Test
  void finalTransientAttemptBecomesTerminal() {
    ClaimedAgentWork work = work(properties.getMaxAttempts());
    doThrow(new AiUnavailableException("AI_DEADLINE_EXCEEDED", "slow", FailureOrigin.DEPENDENCY))
        .when(processor).process(work);
    dispatcher.dispatch(work);
    verify(processor).recordTerminalFailure(work, "AI_DEADLINE_EXCEEDED");
    verify(repository).terminal(work, "AI_DEADLINE_EXCEEDED");
  }

  private static ClaimedAgentWork work(int attempt) {
    return new ClaimedAgentWork(
        UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        UUID.randomUUID().toString(), "ADAPTATION", "ADAPT", UUID.randomUUID(),
        UUID.randomUUID(), RecommendedAction.PRACTICE, "PRACTICE", attempt, "worker-a");
  }
}
