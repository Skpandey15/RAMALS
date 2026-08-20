package io.ramals.learningplatform.content;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.ai.AssessmentPort;
import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.execution.AiExecution;
import io.ramals.learningplatform.execution.AiExecutionCommission;
import io.ramals.learningplatform.execution.AiExecutionRecorder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AssessmentCandidatePreDispatchIdempotencyTests {

  @Test
  void completedSameRequestIsReusedWithoutProviderDispatch() {
    AssessmentPort ai = mock(AssessmentPort.class);
    AiExecutionRecorder recorder = mock(AiExecutionRecorder.class);
    when(recorder.commission(any(), eq("ASSESSMENT")))
        .thenReturn(AiExecutionCommission.existing(completedExecution("SUCCEEDED", null)));

    assertThatThrownBy(() -> service(ai, recorder).intake(
        UUID.randomUUID(), request(), "FOUNDATIONAL", ValidationContext.unavailable(),
        "author", "key", "author", 100L))
        .isInstanceOf(AssessmentCandidateIntakeService.AiExecutionAlreadyCompletedException.class);

    verify(ai, never()).requestAssessmentProposal(any(), any(Long.class), any());
  }

  @Test
  void unresolvedSameRequestIsNotDispatchedAgain() {
    AssessmentPort ai = mock(AssessmentPort.class);
    AiExecutionRecorder recorder = mock(AiExecutionRecorder.class);
    when(recorder.commission(any(), eq("ASSESSMENT"))).thenReturn(AiExecutionCommission.inProgress());

    assertThatThrownBy(() -> service(ai, recorder).intake(
        UUID.randomUUID(), request(), "FOUNDATIONAL", ValidationContext.unavailable(),
        "author", "key", "author", 100L))
        .isInstanceOf(AssessmentCandidateIntakeService.AiExecutionInProgressException.class);

    verify(ai, never()).requestAssessmentProposal(any(), any(Long.class), any());
  }

  @Test
  void failedExecutionBlocksAutomaticSecondDispatch() {
    AssessmentPort ai = mock(AssessmentPort.class);
    AiExecutionRecorder recorder = mock(AiExecutionRecorder.class);
    when(recorder.commission(any(), eq("ASSESSMENT")))
        .thenReturn(AiExecutionCommission.claimed())
        .thenReturn(AiExecutionCommission.existing(completedExecution("FAILED", "AI_TIMEOUT")));
    when(ai.requestAssessmentProposal(any(), eq(100L), eq("FOUNDATIONAL")))
        .thenThrow(new RuntimeException("provider timeout"));

    assertThatThrownBy(() -> service(ai, recorder).intake(
        UUID.randomUUID(), request(), "FOUNDATIONAL", ValidationContext.unavailable(),
        "author", "key", "author", 100L)).isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> service(ai, recorder).intake(
        UUID.randomUUID(), request(), "FOUNDATIONAL", ValidationContext.unavailable(),
        "author", "key", "author", 100L))
        .isInstanceOf(AssessmentCandidateIntakeService.AiExecutionAlreadyCompletedException.class);

    verify(ai).requestAssessmentProposal(any(), eq(100L), eq("FOUNDATIONAL"));
    verify(recorder).recordFailure(any(), eq("ASSESSMENT"), any(), any(), any());
  }

  @Test
  void requestDigestConflictIsRaisedBeforeProviderDispatch() {
    AssessmentPort ai = mock(AssessmentPort.class);
    AiExecutionRecorder recorder = mock(AiExecutionRecorder.class);
    when(recorder.commission(any(), eq("ASSESSMENT")))
        .thenThrow(new RuntimeException("requestId was reused with a different request digest"));

    assertThatThrownBy(() -> service(ai, recorder).intake(
        UUID.randomUUID(), request(), "FOUNDATIONAL", ValidationContext.unavailable(),
        "author", "key", "author", 100L))
        .hasMessageContaining("different request digest");

    verify(ai, never()).requestAssessmentProposal(any(), any(Long.class), any());
  }

  @Test
  void concurrentSameRequestHasExactlyOneProviderDispatch() throws Exception {
    AssessmentPort ai = mock(AssessmentPort.class);
    AtomicInteger providerCalls = new AtomicInteger();
    when(ai.requestAssessmentProposal(any(), eq(100L), eq("FOUNDATIONAL")))
        .thenAnswer(invocation -> {
          providerCalls.incrementAndGet();
          throw new RuntimeException("provider failure");
        });
    AtomicBoolean claimed = new AtomicBoolean();
    AiExecutionRecorder recorder = new AiExecutionRecorder() {
      @Override
      public AiExecutionCommission commission(AiRequestEnvelope ignored, String ignoredAgent) {
        return claimed.compareAndSet(false, true)
            ? AiExecutionCommission.claimed() : AiExecutionCommission.inProgress();
      }

      @Override
      public AiExecution recordSuccess(AiRequestEnvelope ignored, AiProposalEnvelope proposal,
          Instant startedAt, Instant completedAt) {
        return null;
      }

      @Override
      public AiExecution recordFailure(AiRequestEnvelope ignored, String agentType,
          String errorCode, Instant startedAt, Instant completedAt) {
        return null;
      }
    };
    AssessmentCandidateIntakeService service = service(ai, recorder);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> first = executor.submit(() -> invoke(service));
      Future<?> second = executor.submit(() -> invoke(service));
      first.get();
      second.get();
    } finally {
      executor.shutdownNow();
    }

    org.assertj.core.api.Assertions.assertThat(providerCalls).hasValue(1);
  }

  private static void invoke(AssessmentCandidateIntakeService service) {
    try {
      service.intake(UUID.randomUUID(), request(), "FOUNDATIONAL", ValidationContext.unavailable(),
          "author", "key", "author", 100L);
    } catch (RuntimeException expected) {
      // One caller observes the provider failure; the other observes the STARTED claim.
    }
  }

  private static AssessmentCandidateIntakeService service(
      AssessmentPort ai, AiExecutionRecorder recorder) {
    return new AssessmentCandidateIntakeService(ai, mock(ContentValidationPipeline.class),
        new AssessmentCandidatePersistenceService(
            mock(AssessmentCandidateRevisionRepository.class),
            mock(io.ramals.learningplatform.admin.AdminActivityRepository.class)), recorder);
  }

  private static AiExecution completedExecution(String status, String errorCode) {
    return new AiExecution(UUID.randomUUID(), "request", "interaction", "ASSESSMENT", "1.0",
        null, null, null, null, status, errorCode, "a".repeat(64), null, null, null, null,
        null, null, Instant.parse("2026-08-20T00:00:00Z"),
        Instant.parse("2026-08-20T00:00:01Z"));
  }

  private static AiRequestEnvelope request() {
    return new AiRequestEnvelope("1.0", "interaction", "request", null, null, null, null, null,
        "ASSESSMENT");
  }

  @SuppressWarnings("unused")
  private static AiProposalEnvelope proposal() {
    return new AiProposalEnvelope("1.0", "proposal", AgentType.ASSESSMENT, "v1", "run-1",
        "TEMPLATE-1", "prompt-v1",
        "assessment-default", TrustLevel.UNVERIFIED, "0.5", List.of(), Map.of(), null, null);
  }
}
