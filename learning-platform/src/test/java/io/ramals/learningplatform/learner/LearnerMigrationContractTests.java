package io.ramals.learningplatform.learner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LearnerMigrationContractTests {

  @Test
  void migrationCreatesSubjectKeyedLearnerAndGoalWithoutPii() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.learner")
        .contains("subject VARCHAR(255) NOT NULL UNIQUE")
        .contains("CREATE TABLE core.learner_goal")
        .contains("REFERENCES core.learning_domain(id)")
        .contains("NUMERIC(5, 4)")
        .contains("CREATE TRIGGER trg_learner_touch_updated_at")
        .contains("CREATE TRIGGER trg_learner_goal_touch_updated_at")
        .doesNotContain("DOUBLE PRECISION")
        .doesNotContainIgnoringCase("email")
        .doesNotContainIgnoringCase("full_name")
        .doesNotContainIgnoringCase("phone");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V004__learner_domain.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
