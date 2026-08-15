package io.ramals.learningplatform.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProgressionMigrationContractTests {

  @Test
  void migrationDefinesRetentionScheduleMaintainedByTrigger() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.skill_retention")
        .contains("retention_due_at TIMESTAMPTZ")
        .contains("retention_policy_version VARCHAR(32) NOT NULL")
        .contains("CREATE FUNCTION core.maintain_skill_retention()")
        .contains("INTERVAL '30 days'")
        .contains("CREATE TRIGGER trg_mastery_snapshot_retention")
        .contains("AFTER INSERT ON ledger.mastery_snapshot");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V011__progression_retention.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
