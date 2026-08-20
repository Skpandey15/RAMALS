package io.ramals.learningplatform.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.ramals.learningplatform.admin.AdminActivityRepository;
import io.ramals.learningplatform.ai.AssessmentPort;
import io.ramals.learningplatform.ai.contract.AgentType;
import io.ramals.learningplatform.ai.contract.AiProposalEnvelope;
import io.ramals.learningplatform.ai.contract.AiRequestEnvelope;
import io.ramals.learningplatform.ai.contract.TrustLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AssessmentCandidateTransactionBoundaryTests {

  @Test
  void realSpringProxyKeepsAiCallOutsideAndPersistenceInsideTransaction() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(TestConfiguration.class)) {
      AssessmentPort ai = context.getBean(AssessmentPort.class);
      ContentValidationPipeline pipeline = context.getBean(ContentValidationPipeline.class);
      AssessmentCandidateRevisionRepository repository =
          context.getBean(AssessmentCandidateRevisionRepository.class);
      AdminActivityRepository audit = context.getBean(AdminActivityRepository.class);
      AiProposalEnvelope proposal = proposal();
      AssessmentCandidateRevision saved = revision();

      when(ai.requestAssessmentProposal(any(), eq(100L), eq("FOUNDATIONAL")))
          .thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return proposal;
          });
      when(pipeline.validate(any(), any()))
          .thenReturn(new ContentValidationPipeline.Outcome.NotRejected());
      when(repository.findByIdempotency("author", "key"))
          .thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.empty();
          });
      when(repository.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
          any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return saved;
          });
      doAnswer(invocation -> {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        return null;
      }).when(audit).appendWithinTransaction(any(), any(), any(), any(), any(), any(), any(), any());

      AssessmentCandidateRevision result = context.getBean(AssessmentCandidateIntakeService.class)
          .intake(UUID.randomUUID(), request(), "FOUNDATIONAL", ValidationContext.unavailable(),
              "author", "key", "author", 100L);

      assertThat(result).isSameAs(saved);
    }
  }

  @Test
  void auditFailureRollsBackCandidateAndAudit() {
    try (AnnotationConfigApplicationContext context = newContext()) {
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      createProbeTable(jdbc);
      AssessmentCandidateRevisionRepository repository =
          context.getBean(AssessmentCandidateRevisionRepository.class);
      AdminActivityRepository audit = context.getBean(AdminActivityRepository.class);
      configureSuccessfulAi(context);
      when(repository.findByIdempotency("author", "key")).thenReturn(Optional.empty());
      doAnswer(invocation -> {
        jdbc.update("INSERT INTO candidate_probe(kind) VALUES ('candidate')");
        return revision();
      }).when(repository).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
          any(), any(), any(), any(), any(), any());
      doAnswer(invocation -> {
        jdbc.update("INSERT INTO candidate_probe(kind) VALUES ('audit')");
        throw new IllegalStateException("audit failure");
      }).when(audit).appendWithinTransaction(any(), any(), any(), any(), any(), any(), any(), any());

      assertThatThrownBy(() -> intake(context).intake(UUID.randomUUID(), request(), "FOUNDATIONAL",
          ValidationContext.unavailable(), "author", "key", "author", 100L))
          .isInstanceOf(IllegalStateException.class);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_probe", Integer.class)).isZero();
    }
  }

  @Test
  void candidateFailureDoesNotWriteAudit() {
    try (AnnotationConfigApplicationContext context = newContext()) {
      AssessmentCandidateRevisionRepository repository =
          context.getBean(AssessmentCandidateRevisionRepository.class);
      AdminActivityRepository audit = context.getBean(AdminActivityRepository.class);
      configureSuccessfulAi(context);
      when(repository.findByIdempotency("author", "key")).thenReturn(Optional.empty());
      doAnswer(invocation -> { throw new IllegalStateException("candidate failure"); })
          .when(repository).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
              any(), any(), any(), any(), any(), any());

      assertThatThrownBy(() -> intake(context).intake(UUID.randomUUID(), request(), "FOUNDATIONAL",
          ValidationContext.unavailable(), "author", "key", "author", 100L))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(audit);
    }
  }

  @Test
  void successfulPersistenceCommitsCandidateAndAudit() {
    try (AnnotationConfigApplicationContext context = newContext()) {
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      createProbeTable(jdbc);
      AssessmentCandidateRevisionRepository repository =
          context.getBean(AssessmentCandidateRevisionRepository.class);
      AdminActivityRepository audit = context.getBean(AdminActivityRepository.class);
      configureSuccessfulAi(context);
      when(repository.findByIdempotency("author", "key")).thenReturn(Optional.empty());
      doAnswer(invocation -> {
        jdbc.update("INSERT INTO candidate_probe(kind) VALUES ('candidate')");
        return revision();
      }).when(repository).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
          any(), any(), any(), any(), any(), any());
      doAnswer(invocation -> {
        jdbc.update("INSERT INTO candidate_probe(kind) VALUES ('audit')");
        return null;
      }).when(audit).appendWithinTransaction(any(), any(), any(), any(), any(), any(), any(), any());

      intake(context).intake(UUID.randomUUID(), request(), "FOUNDATIONAL", ValidationContext.unavailable(),
          "author", "key", "author", 100L);

      assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_probe", Integer.class)).isEqualTo(2);
    }
  }

  private static AnnotationConfigApplicationContext newContext() {
    return new AnnotationConfigApplicationContext(TestConfiguration.class);
  }

  private static AssessmentCandidateIntakeService intake(AnnotationConfigApplicationContext context) {
    return context.getBean(AssessmentCandidateIntakeService.class);
  }

  private static void configureSuccessfulAi(AnnotationConfigApplicationContext context) {
    AssessmentPort ai = context.getBean(AssessmentPort.class);
    ContentValidationPipeline pipeline = context.getBean(ContentValidationPipeline.class);
    when(ai.requestAssessmentProposal(any(), eq(100L), eq("FOUNDATIONAL"))).thenReturn(proposal());
    when(pipeline.validate(any(), any())).thenReturn(new ContentValidationPipeline.Outcome.NotRejected());
  }

  private static void createProbeTable(JdbcTemplate jdbc) {
    jdbc.execute("CREATE TABLE candidate_probe (kind VARCHAR(20) NOT NULL)");
  }

  @Configuration(proxyBeanMethods = false)
  @EnableTransactionManagement
  static class TestConfiguration {

    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource dataSource) {
      return new JdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    AssessmentPort assessmentPort() {
      return mock(AssessmentPort.class);
    }

    @Bean
    ContentValidationPipeline validationPipeline() {
      return mock(ContentValidationPipeline.class);
    }

    @Bean
    AssessmentCandidateRevisionRepository repository() {
      return mock(AssessmentCandidateRevisionRepository.class);
    }

    @Bean
    AdminActivityRepository audit() {
      return mock(AdminActivityRepository.class);
    }

    @Bean
    AssessmentCandidatePersistenceService persistenceService(
        AssessmentCandidateRevisionRepository repository, AdminActivityRepository audit) {
      return new AssessmentCandidatePersistenceService(repository, audit);
    }

    @Bean
    AssessmentCandidateIntakeService intakeService(
        AssessmentPort assessmentPort,
        ContentValidationPipeline validationPipeline,
        AssessmentCandidatePersistenceService persistenceService) {
      return new AssessmentCandidateIntakeService(
          assessmentPort, validationPipeline, persistenceService,
          new io.ramals.learningplatform.execution.NoOpAiExecutionRecorder());
    }
  }

  private static AiProposalEnvelope proposal() {
    return new AiProposalEnvelope("1.0", "source-proposal", AgentType.ASSESSMENT, "v1",
        "run-1", "TEMPLATE-1", "prompt-v1", "assessment-default", TrustLevel.UNVERIFIED, "0.5", List.of(), Map.of(
            "skillCode", "KAFKA_TOPIC", "objectiveCode", "TOPIC_DEFINE", "difficulty", "FOUNDATIONAL",
            "stem", "What is a topic?", "options", List.of("A", "B"), "answerKey", List.of("A"),
            "rationale", "A topic is a named stream."), null, null);
  }

  private static AiRequestEnvelope request() {
    return new AiRequestEnvelope("1.0", "interaction", "request", null, null, null, null, null, null);
  }

  private static AssessmentCandidateRevision revision() {
    return new AssessmentCandidateRevision(UUID.randomUUID(), 1, "source-proposal", UUID.randomUUID(),
        "AI_CANDIDATE_1", "KAFKA_TOPIC", "TOPIC_DEFINE", "SINGLE_CHOICE", "FOUNDATIONAL", "{}",
        "a".repeat(64), "UNVERIFIED", "1.0", "ASSESSMENT", "v1", "assessment-default", null,
        "model identity unavailable", "prompt-v1", "interaction", "author", Instant.now(), "author",
        "key", "a".repeat(64));
  }
}
