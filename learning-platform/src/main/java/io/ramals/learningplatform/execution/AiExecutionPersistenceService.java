package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
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
public class AiExecutionPersistenceService
    implements AiExecutionRecorder, DiagnosticAssessmentExecutionRecorder {
  private static final Logger LOGGER = LoggerFactory.getLogger(AiExecutionPersistenceService.class);

  private final AiExecutionRepository repository;
  private final PlatformTransactionManager transactionManager;

  public AiExecutionPersistenceService(AiExecutionRepository repository,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.transactionManager = transactionManager;
  }

  @Override
  public AiExecutionCommission commission(AiRequestEnvelope request, String agentType) {
    try {
      AiExecutionCommission commission = independent(() -> repository.commission(request, agentType));
      BusinessEventLogger.info(LOGGER, "ai.execution.commissioned", "AI execution commission evaluated",
          Map.of("requestId", request.requestId(), "interactionId", request.interactionId(),
              "agentType", agentType, "state", commission.state(),
              "dispatchAllowed", commission.dispatchAllowed()));
      return commission;
    } catch (RuntimeException failure) {
      BusinessEventLogger.error(LOGGER, "ai.execution.commission_failed",
          "AI execution could not be commissioned", failure,
          Map.of("requestId", request.requestId(), "interactionId", request.interactionId(),
              "agentType", agentType));
      throw failure;
    }
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

  @Override
  public AiExecutionCommission commission(DiagnosticAssessmentRequest request) {
    try {
      AiExecutionCommission commission =
          independent(() -> repository.commissionDiagnosticAssessment(request));
      logCommission(request.requestId(), request.interactionId(), "DIAGNOSTIC", commission);
      return commission;
    } catch (RuntimeException failure) {
      logCommissionFailure(
          request.requestId(), request.interactionId(), "DIAGNOSTIC", failure);
      throw failure;
    }
  }

  @Override
  public AiExecution recordSuccess(
      DiagnosticAssessmentRequest request,
      AiProposalEnvelope proposal,
      Instant startedAt,
      Instant completedAt) {
    try {
      AiExecution execution =
          independent(
              () ->
                  repository.insertDiagnosticAssessmentSuccess(
                      request, proposal, startedAt, completedAt));
      BusinessEventLogger.info(
          LOGGER,
          "ai.execution.persisted",
          "AI execution recorded",
          Map.of(
              "requestId", request.requestId(),
              "interactionId", request.interactionId(),
              "agentType", "DIAGNOSTIC",
              "status", execution.status()));
      return execution;
    } catch (RuntimeException failure) {
      logPersistenceFailure(
          request.requestId(), request.interactionId(), "DIAGNOSTIC", "SUCCEEDED", null, failure);
      throw failure;
    }
  }

  @Override
  public AiExecution recordFailure(
      DiagnosticAssessmentRequest request,
      String errorCode,
      Instant startedAt,
      Instant completedAt) {
    try {
      AiExecution execution =
          independent(
              () ->
                  repository.insertDiagnosticAssessmentFailure(
                      request, errorCode, startedAt, completedAt));
      BusinessEventLogger.info(
          LOGGER,
          "ai.execution.failure_persisted",
          "AI execution failure recorded",
          Map.of(
              "requestId", request.requestId(),
              "interactionId", request.interactionId(),
              "agentType", "DIAGNOSTIC",
              "status", execution.status(),
              "errorCode", errorCode));
      return execution;
    } catch (RuntimeException failure) {
      logPersistenceFailure(
          request.requestId(),
          request.interactionId(),
          "DIAGNOSTIC",
          "FAILED",
          errorCode,
          failure);
      throw failure;
    }
  }

  private static void logCommission(
      String requestId,
      String interactionId,
      String agentType,
      AiExecutionCommission commission) {
    BusinessEventLogger.info(
        LOGGER,
        "ai.execution.commissioned",
        "AI execution commission evaluated",
        Map.of(
            "requestId", requestId,
            "interactionId", interactionId,
            "agentType", agentType,
            "state", commission.state(),
            "dispatchAllowed", commission.dispatchAllowed()));
  }

  private static void logCommissionFailure(
      String requestId, String interactionId, String agentType, RuntimeException failure) {
    BusinessEventLogger.error(
        LOGGER,
        "ai.execution.commission_failed",
        "AI execution could not be commissioned",
        failure,
        Map.of(
            "requestId", requestId,
            "interactionId", interactionId,
            "agentType", agentType));
  }

  private static void logPersistenceFailure(
      String requestId,
      String interactionId,
      String agentType,
      String status,
      String errorCode,
      RuntimeException failure) {
    Map<String, Object> fields = new java.util.HashMap<>();
    fields.put("requestId", requestId);
    fields.put("interactionId", interactionId);
    fields.put("agentType", agentType);
    fields.put("status", status);
    if (errorCode != null) {
      fields.put("errorCode", errorCode);
    }
    BusinessEventLogger.error(
        LOGGER,
        "ai.execution.persistence_failed",
        "AI execution could not be recorded",
        failure,
        fields);
  }

  private <T> T independent(java.util.function.Supplier<T> operation) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transaction.execute(status -> operation.get());
  }
}
