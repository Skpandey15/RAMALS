package io.ramals.learningplatform.execution;

import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.DiagnosticAssessmentRequest;
import java.time.Instant;
import java.util.Optional;

/** Durable execution accounting for the grounded diagnostic-assessment contract. */
public interface DiagnosticAssessmentExecutionRecorder {

  AiExecutionCommission commission(DiagnosticAssessmentRequest request);

  /** Returns the exact grounding identity of an ownerless commission, when one exists. */
  Optional<DiagnosticCommissionContext> findRecoverableCommission(String requestId);

  /** Atomically acquires the right to make this commission's first provider call. */
  AiExecutionDispatchClaim acquireDispatch(String requestId);

  /** Fences the owner and durably marks the provider invocation as having begun. */
  boolean markProviderInvocationStarted(String requestId, AiExecutionDispatchClaim claim);

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
