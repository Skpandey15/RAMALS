package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import java.time.Instant;

/**
 * Test adapter for unit tests that intentionally exclude infrastructure persistence.
 *
 * <p>Lives in test sources on purpose. A recorder that silently discards AI execution provenance is
 * exactly right for a unit test and exactly wrong for a running platform, and while it sat in
 * {@code src/main} a production constructor could -- and did -- reach it.
 */
public final class NoOpAiExecutionRecorder implements AiExecutionRecorder {
  @Override
  public AiExecutionCommission commission(AiRequestEnvelope request, String agentType) {
    return AiExecutionCommission.claimed();
  }

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
