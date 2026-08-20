package io.ramals.learningplatform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.ramals.learningplatform.ai.AssessmentPort;
import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import io.ramals.learningplatform.execution.NoOpAiExecutionRecorder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssessmentCandidateIntakeServiceTests {

  @Test
  void intakeForcesUnverifiedAndPersistsSourceProvenanceSeparately() {
    AssessmentPort ai = mock(AssessmentPort.class);
    ContentValidationPipeline pipeline = mock(ContentValidationPipeline.class);
    AssessmentCandidateRevisionRepository repository = mock(AssessmentCandidateRevisionRepository.class);
    io.ramals.learningplatform.admin.AdminActivityRepository audit =
        mock(io.ramals.learningplatform.admin.AdminActivityRepository.class);
    UUID version = UUID.randomUUID();
    String sourceProposalId = UUID.randomUUID().toString();
    AiProposalEnvelope proposal = proposal(sourceProposalId, TrustLevel.UNVERIFIED);
    when(ai.requestAssessmentProposal(any(), eq(100L), eq("FOUNDATIONAL"))).thenReturn(proposal);
    when(pipeline.validate(any(), any())).thenReturn(new ContentValidationPipeline.Outcome.NotRejected());
    when(repository.findByIdempotency("author", "key")).thenReturn(Optional.empty());
    AssessmentCandidateRevision saved = revision(version, sourceProposalId);
    when(repository.insert(any(), eq(sourceProposalId), eq("1.0"), eq("ASSESSMENT"), eq("v1"),
        eq("assessment-default"), eq(null), any(), eq("prompt-v1"), any(), eq("author"),
        eq("author"), eq("key"), any(), any(), any())).thenReturn(saved);

    AssessmentCandidateRevision result = new AssessmentCandidateIntakeService(
        ai, pipeline, new AssessmentCandidatePersistenceService(repository, audit),
        new NoOpAiExecutionRecorder()).intake(
            version, request(), "FOUNDATIONAL", ValidationContext.unavailable(),
            "author", "key", "author", 100L);

    assertThat(result.trustState()).isEqualTo("UNVERIFIED");
    assertThat(result.candidateId()).isNotEqualTo(UUID.fromString(sourceProposalId));
    org.mockito.Mockito.verify(audit).appendWithinTransaction(any(), eq("AI_CANDIDATE_INTAKE"),
        eq("ASSESSMENT_CANDIDATE"), any(), eq("SUCCESS"), any(), any(), any());
  }

  @Test
  void verifiedAiClaimIsRejectedBeforePersistence() {
    AssessmentPort ai = mock(AssessmentPort.class);
    AiProposalEnvelope proposal = proposal(UUID.randomUUID().toString(), TrustLevel.VERIFIED_CONTENT);
    when(ai.requestAssessmentProposal(any(), any(Long.class), any())).thenReturn(proposal);
    ContentValidationPipeline pipeline = mock(ContentValidationPipeline.class);
    when(pipeline.validate(any(), any())).thenReturn(new ContentValidationPipeline.Outcome.NotRejected());

    assertThatThrownBy(() -> new AssessmentCandidateIntakeService(
        ai, pipeline, new AssessmentCandidatePersistenceService(
            mock(AssessmentCandidateRevisionRepository.class),
            mock(io.ramals.learningplatform.admin.AdminActivityRepository.class)),
        new NoOpAiExecutionRecorder())
        .intake(UUID.randomUUID(), request(), "FOUNDATIONAL", ValidationContext.unavailable(),
            "author", "key", "author", 100L))
        .isInstanceOf(AssessmentCandidateIntakeService.CandidateIntakeRejectedException.class);
  }

  private static AiProposalEnvelope proposal(String id, TrustLevel trust) {
    return new AiProposalEnvelope("1.0", id, AgentType.ASSESSMENT, "v1", "prompt-v1",
        "assessment-default", trust, "0.5", List.of(), Map.of(
            "skillCode", "KAFKA_TOPIC", "objectiveCode", "TOPIC_DEFINE", "difficulty", "FOUNDATIONAL",
            "stem", "What is a topic?", "options", List.of("A", "B", "C"),
            "answerKey", List.of("A"), "rationale", "A topic is a named stream."), null, null);
  }

  private static AiRequestEnvelope request() {
    return new AiRequestEnvelope("1.0", "interaction", "request", null, null, null, null, null, null);
  }

  private static AssessmentCandidateRevision revision(UUID version, String source) {
    return new AssessmentCandidateRevision(UUID.randomUUID(), 1, source, version, "AI_CANDIDATE_1",
        "KAFKA_TOPIC", "TOPIC_DEFINE", "SINGLE_CHOICE", "FOUNDATIONAL", "{}", "a".repeat(64),
        "UNVERIFIED", "1.0", "ASSESSMENT", "v1", "assessment-default", null,
        "model identity unavailable", "prompt-v1", "interaction", "author", Instant.now(),
        "author", "key", "a".repeat(64));
  }
}
