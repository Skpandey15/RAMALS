package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import java.time.Instant;

/** Application port for durable AI execution accounting. */
public interface AiExecutionRecorder {
  AiExecution recordSuccess(AiRequestEnvelope request, AiProposalEnvelope proposal,
      Instant startedAt, Instant completedAt);

  AiExecution recordFailure(AiRequestEnvelope request, String agentType, String errorCode,
      Instant startedAt, Instant completedAt);
}
