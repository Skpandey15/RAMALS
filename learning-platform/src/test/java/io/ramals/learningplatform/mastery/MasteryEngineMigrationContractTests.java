package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MasteryEngineMigrationContractTests {

  @Test
  void migrationDefinesAggregateCoordinationAndAppendOnlySnapshots() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.learner_skill_aggregate")
        .contains("aggregate_version INTEGER NOT NULL DEFAULT 0")
        .contains("CREATE TABLE ledger.mastery_snapshot")
        .contains("UNIQUE (learner_id, skill_id, curriculum_version_id, aggregate_version)")
        .contains("CREATE FUNCTION ledger.reject_mastery_snapshot_mutation()")
        .contains("CREATE TRIGGER trg_mastery_snapshot_append_only")
        .contains("BEFORE UPDATE OR DELETE ON ledger.mastery_snapshot")
        .contains("NUMERIC(5, 4)")
        .doesNotContain("DOUBLE PRECISION");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V008__mastery_engine.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
