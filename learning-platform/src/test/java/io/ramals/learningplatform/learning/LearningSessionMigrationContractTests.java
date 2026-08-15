package io.ramals.learningplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LearningSessionMigrationContractTests {

  @Test
  void migrationDefinesResumableSessionWithOneOpenGuardAndTransitionLog() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.learning_session")
        .contains("version INTEGER NOT NULL DEFAULT 1")
        .contains("checkpoint_jsonb JSONB NOT NULL DEFAULT '{}'::jsonb")
        .contains("CREATE UNIQUE INDEX uq_learning_session_one_open")
        .contains("WHERE status IN ('ACTIVE', 'PAUSED')")
        .contains("CREATE TABLE core.learning_session_transition")
        .contains("interaction_id VARCHAR(64) NOT NULL")
        .contains("UNIQUE (session_id, version_after)");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V012__learning_session.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
