package io.ramals.learningplatform.assessmentevaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AssessmentFeedbackRepositoryTests {

  @Test
  void projectsOnlyLearnerSafeRubricFieldsFromStoredDecisionJson() {
    var results =
        AssessmentFeedbackRepository.rubricResults(
            """
            [{
              "dimensionId": "accuracy",
              "score": 2,
              "maxScore": 4,
              "reason": "Use the exact acknowledgement semantics.",
              "evidenceIds": ["private-evidence-id"]
            }]
            """);

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().dimensionId()).isEqualTo("accuracy");
    assertThat(results.getFirst().score()).isEqualByComparingTo(new BigDecimal("2"));
    assertThat(results.getFirst().feedback())
        .isEqualTo("Use the exact acknowledgement semantics.");
    assertThat(results.getFirst().toString()).doesNotContain("private-evidence-id");
  }

  @Test
  void absentStoredRubricContentMapsToAnEmptyProjection() {
    assertThat(AssessmentFeedbackRepository.rubricResults(null)).isEmpty();
    assertThat(AssessmentFeedbackRepository.rubricResults(" ")).isEmpty();
  }

  @Test
  void latestReadIsBoundedAndScopedToTheAuthenticatedSubject() {
    String database =
        "jdbc:h2:mem:assessment-feedback-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(database, "sa", ""));
    jdbc.execute("CREATE SCHEMA core");
    jdbc.execute("CREATE SCHEMA ledger");
    jdbc.execute(
        """
        CREATE TABLE core.learner (
          id UUID PRIMARY KEY, subject VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL)
        """);
    jdbc.execute(
        """
        CREATE TABLE ledger.grounding_retrieval_record (
          context_id VARCHAR(64) PRIMARY KEY, learner_id UUID NOT NULL)
        """);
    jdbc.execute(
        """
        CREATE TABLE ledger.assessment_evaluation_decision (
          id UUID PRIMARY KEY, context_id VARCHAR(64) NOT NULL, outcome VARCHAR(24) NOT NULL,
          answer_version VARCHAR(64) NOT NULL, rubric_version VARCHAR(64) NOT NULL,
          feedback VARCHAR(4000), dimension_results VARCHAR(8000) NOT NULL,
          decided_at TIMESTAMP WITH TIME ZONE NOT NULL)
        """);

    UUID learner = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    jdbc.update("INSERT INTO core.learner VALUES (?, 'learner-1', 'ACTIVE')", learner);
    jdbc.update("INSERT INTO core.learner VALUES (?, 'learner-2', 'ACTIVE')", other);
    jdbc.update("INSERT INTO ledger.grounding_retrieval_record VALUES ('ctx-old', ?)", learner);
    jdbc.update("INSERT INTO ledger.grounding_retrieval_record VALUES ('ctx-new', ?)", learner);
    jdbc.update("INSERT INTO ledger.grounding_retrieval_record VALUES ('ctx-other', ?)", other);
    insertDecision(jdbc, "ctx-old", "Old owned feedback.", "2026-08-21T00:00:00Z");
    insertDecision(jdbc, "ctx-new", "Latest owned feedback.", "2026-08-22T00:00:00Z");
    insertDecision(jdbc, "ctx-other", "Other learner feedback.", "2026-08-23T00:00:00Z");

    var result = new AssessmentFeedbackRepository(jdbc).findLatestForSubject("learner-1");

    assertThat(result).isPresent();
    assertThat(result.orElseThrow().feedback()).isEqualTo("Latest owned feedback.");
  }

  private static void insertDecision(
      JdbcTemplate jdbc, String contextId, String feedback, String decidedAt) {
    jdbc.update(
        """
        INSERT INTO ledger.assessment_evaluation_decision
          (id, context_id, outcome, answer_version, rubric_version, feedback,
           dimension_results, decided_at)
        VALUES (?, ?, 'ACCEPTED', 'answer-v1', 'rubric-v1', ?,
                '[{"dimensionId":"accuracy","score":2,"maxScore":4,'
                  || '"reason":"Approved detail.","evidenceIds":[]}]', ?)
        """,
        UUID.randomUUID(),
        contextId,
        feedback,
        OffsetDateTime.parse(decidedAt));
  }
}
