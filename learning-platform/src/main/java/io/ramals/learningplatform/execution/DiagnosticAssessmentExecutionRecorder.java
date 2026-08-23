package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import java.time.Instant;

/** Durable execution accounting for the grounded diagnostic-assessment contract. */
public interface DiagnosticAssessmentExecutionRecorder {

  AiExecutionCommission commission(DiagnosticAssessmentRequest request);

  AiExecution recordSuccess(
      DiagnosticAssessmentRequest request,
      AiProposalEnvelope proposal,
      Instant startedAt,
      Instant completedAt);

  AiExecution recordFailure(
      DiagnosticAssessmentRequest request,
      String errorCode,
      Instant startedAt,
      Instant completedAt);
}
