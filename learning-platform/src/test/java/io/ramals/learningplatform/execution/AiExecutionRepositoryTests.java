package io.ramals.learningplatform.execution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiExecutionRepositoryTests {
  private static final Instant START = Instant.parse("2026-08-20T00:00:00Z");
  private static final Instant END = Instant.parse("2026-08-20T00:00:01Z");

  @Test
  void differentTerminalStatusConflicts() {
    AiExecution failed = execution("FAILED", "AI_TIMEOUT", null);

    assertThatThrownBy(() -> AiExecutionRepository.validateTerminalCompatibility(
        failed, "SUCCEEDED", null, failed.requestDigest(), "proposal"))
        .isInstanceOf(AiExecutionRepository.AiExecutionConflictException.class)
        .hasMessageContaining("different terminal outcome");
  }

  @Test
  void differentProposalDigestConflictsForSuccessfulReplay() {
    AiExecution succeeded = execution("SUCCEEDED", null, "proposal-a");

    assertThatThrownBy(() -> AiExecutionRepository.validateTerminalCompatibility(
        succeeded, "SUCCEEDED", null, succeeded.requestDigest(), "proposal-b"))
        .isInstanceOf(AiExecutionRepository.AiExecutionConflictException.class)
        .hasMessageContaining("different proposal digest");
  }

  @Test
  void differentFailureCodeConflictsForFailedReplay() {
    AiExecution failed = execution("FAILED", "AI_TIMEOUT", null);

    assertThatThrownBy(() -> AiExecutionRepository.validateTerminalCompatibility(
        failed, "FAILED", "AI_PROVIDER_FAILURE", failed.requestDigest(), null))
        .isInstanceOf(AiExecutionRepository.AiExecutionConflictException.class)
        .hasMessageContaining("different failure code");
  }

  @Test
  void identicalTerminalReplayIsIdempotent() {
    AiExecution succeeded = execution("SUCCEEDED", null, "proposal-a");

    assertThatCode(() -> AiExecutionRepository.validateTerminalCompatibility(
        succeeded, "SUCCEEDED", null, succeeded.requestDigest(), succeeded.proposalDigest()))
        .doesNotThrowAnyException();
  }

  private static AiExecution execution(String status, String errorCode, String proposalDigest) {
    return new AiExecution(UUID.randomUUID(), "request-123", "interaction-123", "ASSESSMENT", "1.0",
        "agent-v1", "run-1", "ASSESSMENT_ITEM", "prompt-v1", "ci-fake", null, status,
        errorCode, "a".repeat(64),
        proposalDigest, null, null, null, null, null, START, END);
  }
}
