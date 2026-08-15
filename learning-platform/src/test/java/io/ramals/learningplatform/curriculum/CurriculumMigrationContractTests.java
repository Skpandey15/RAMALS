package io.ramals.learningplatform.curriculum;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CurriculumMigrationContractTests {

  @Test
  void migrationContainsVersioningGraphPolicyAndDeterministicSeed() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.learning_domain")
        .contains("CREATE TABLE core.curriculum_version")
        .contains("CREATE TABLE core.skill_version")
        .contains("CREATE TABLE core.skill_prerequisite")
        .contains("CREATE TABLE core.learning_objective")
        .contains("CREATE FUNCTION core.reject_prerequisite_cycle()")
        .contains("CREATE FUNCTION core.validate_curriculum_publication()")
        .contains("NUMERIC(5, 4)")
        .doesNotContain("DOUBLE PRECISION")
        .contains("'KAFKA_FAILURE_RECOVERY'")
        .contains("SET status = 'PUBLISHED'");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream(
        "/db/migration/V003__curriculum_and_versioning.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
