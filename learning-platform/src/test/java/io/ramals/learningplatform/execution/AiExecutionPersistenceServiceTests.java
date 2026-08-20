package io.ramals.learningplatform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.ai.contract.Validation;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class AiExecutionPersistenceServiceTests {
  private static final Instant START = Instant.parse("2026-08-20T00:00:00Z");
  private static final Instant END = Instant.parse("2026-08-20T00:00:01Z");

  @Test
  void recordsSuccessInRequiresNewTransaction() {
    AiExecutionRepository repository = mock(AiExecutionRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus status = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(status);
    AiRequestEnvelope request = request();
    AiProposalEnvelope proposal = proposal();
    AiExecution expected = new AiExecution(UUID.randomUUID(), request.requestId(),
        request.interactionId(), "ASSESSMENT", request.contractVersion(), "agent-v1", "prompt-v1",
        "ci-fake", null, "SUCCEEDED", null, "request-digest", "proposal-digest", null, null,
        null, null, null, START, END);
    when(repository.insertSuccess(request, proposal, START, END)).thenReturn(expected);

    AiExecution actual = new AiExecutionPersistenceService(repository, transactionManager)
        .recordSuccess(request, proposal, START, END);

    assertThat(actual).isSameAs(expected);
    verify(transactionManager).getTransaction(any());
    verify(transactionManager).commit(status);
  }

  @Test
  void recordsFailuresThroughTheSameIndependentBoundary() {
    AiExecutionRepository repository = mock(AiExecutionRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus status = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(status);
    AiRequestEnvelope request = request();
    AiExecution expected = new AiExecution(UUID.randomUUID(), request.requestId(),
        request.interactionId(), "ASSESSMENT", request.contractVersion(), null, null, null, null,
        "FAILED", "AI_TIMEOUT", "request-digest", null, null, null, null, null, null, START, END);
    when(repository.insertFailure(request, "ASSESSMENT", "AI_TIMEOUT", START, END))
        .thenReturn(expected);

    AiExecution actual = new AiExecutionPersistenceService(repository, transactionManager)
        .recordFailure(request, "ASSESSMENT", "AI_TIMEOUT", START, END);

    assertThat(actual.status()).isEqualTo("FAILED");
    verify(transactionManager).commit(status);
  }

  private static AiRequestEnvelope request() {
    return new AiRequestEnvelope("1.0", "interaction-123", "request-123", null, null, null, null,
        null, "ASSESSMENT");
  }

  private static AiProposalEnvelope proposal() {
    return new AiProposalEnvelope("1.0", "proposal-123", AgentType.ASSESSMENT, "agent-v1",
        "prompt-v1", "ci-fake", TrustLevel.UNVERIFIED, "0.5", java.util.List.of(),
        Map.of("itemCode", "ITEM-1"), new Validation(true, true, 0), null);
  }
}
