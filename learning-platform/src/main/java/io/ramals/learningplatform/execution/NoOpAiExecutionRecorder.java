package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import java.time.Instant;

/** Test adapter for callers whose unit tests intentionally exclude infrastructure persistence. */
public final class NoOpAiExecutionRecorder implements AiExecutionRecorder {
  @Override
  public AiExecution recordSuccess(AiRequestEnvelope request, AiProposalEnvelope proposal,
      Instant startedAt, Instant completedAt) {
    return null;
  }

  @Override
  public AiExecution recordFailure(AiRequestEnvelope request, String agentType, String errorCode,
      Instant startedAt, Instant completedAt) {
    return null;
  }
}
