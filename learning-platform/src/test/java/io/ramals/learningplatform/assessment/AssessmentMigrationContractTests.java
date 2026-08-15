package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AssessmentMigrationContractTests {

  @Test
  void migrationDefinesVersionedAssessmentAndIdempotentAttemptInvariants() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.assessment")
        .contains("CREATE TABLE core.assessment_version")
        .contains("CREATE TABLE core.assessment_item_version")
        .contains("CREATE TABLE core.assessment_attempt")
        .contains("answer_key_jsonb JSONB NOT NULL")
        .contains("UNIQUE (learner_id, assessment_version_id, idempotency_key)")
        .contains("CREATE UNIQUE INDEX uq_assessment_attempt_one_active")
        .contains("WHERE status = 'IN_PROGRESS'")
        .contains("REFERENCES core.assessment_version(id) ON DELETE RESTRICT")
        .contains("CREATE FUNCTION core.validate_assessment_publication()")
        .contains("'KAFKA_DIAGNOSTIC'")
        .contains("SET status = 'PUBLISHED'")
        .doesNotContain("DOUBLE PRECISION");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V005__assessment_and_attempts.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
