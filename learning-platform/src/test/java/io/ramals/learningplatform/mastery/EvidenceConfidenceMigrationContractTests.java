package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EvidenceConfidenceMigrationContractTests {

  @Test
  void migrationAddsNullableConfidenceColumnsWithChecks() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("ALTER TABLE ledger.mastery_snapshot")
        .contains("ADD COLUMN evidence_confidence NUMERIC(5, 4)")
        .contains("ADD COLUMN confidence_threshold NUMERIC(5, 4)")
        .contains("ADD COLUMN confidence_algorithm_version VARCHAR(32)")
        .contains("ck_mastery_snapshot_confidence")
        .doesNotContain("DOUBLE PRECISION");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V009__evidence_confidence.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
