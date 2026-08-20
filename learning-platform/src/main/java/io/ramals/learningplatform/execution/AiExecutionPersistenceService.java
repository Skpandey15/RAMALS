package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.observability.BusinessEventLogger;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Records AI execution metadata in an independent transaction. */
@Service
public class AiExecutionPersistenceService implements AiExecutionRecorder {
  private static final Logger LOGGER = LoggerFactory.getLogger(AiExecutionPersistenceService.class);

  private final AiExecutionRepository repository;
  private final PlatformTransactionManager transactionManager;

  public AiExecutionPersistenceService(AiExecutionRepository repository,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.transactionManager = transactionManager;
  }

  @Override
  public AiExecution recordSuccess(AiRequestEnvelope request, AiProposalEnvelope proposal,
      Instant startedAt, Instant completedAt) {
    try {
      AiExecution execution = independent(
          () -> repository.insertSuccess(request, proposal, startedAt, completedAt));
      BusinessEventLogger.info(LOGGER, "ai.execution.persisted", "AI execution recorded",
          Map.of("requestId", request.requestId(), "interactionId", request.interactionId(),
              "agentType", proposal.agentType(), "status", execution.status()));
      return execution;
    } catch (RuntimeException failure) {
      BusinessEventLogger.error(LOGGER, "ai.execution.persistence_failed",
          "AI execution could not be recorded", failure,
          Map.of("requestId", request.requestId(), "interactionId", request.interactionId(),
              "status", "SUCCEEDED"));
      throw failure;
    }
  }

  @Override
  public AiExecution recordFailure(AiRequestEnvelope request, String agentType, String errorCode,
      Instant startedAt, Instant completedAt) {
    try {
      AiExecution execution = independent(
          () -> repository.insertFailure(request, agentType, errorCode, startedAt, completedAt));
      BusinessEventLogger.info(LOGGER, "ai.execution.failure_persisted",
          "AI execution failure recorded",
          Map.of("requestId", request.requestId(), "interactionId", request.interactionId(),
              "agentType", agentType, "status", execution.status(), "errorCode", errorCode));
      return execution;
    } catch (RuntimeException failure) {
      BusinessEventLogger.error(LOGGER, "ai.execution.persistence_failed",
          "AI execution failure could not be recorded", failure,
          Map.of("requestId", request.requestId(), "interactionId", request.interactionId(),
              "agentType", agentType, "status", "FAILED", "errorCode", errorCode));
      throw failure;
    }
  }

  private <T> T independent(java.util.function.Supplier<T> operation) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction.execute(status -> operation.get());
  }
}
