package io.ramals.learningplatform.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RecommendationDecisionMigrationContractTests {

  @Test
  void migrationDefinesAppendOnlyDecisionsAndCurrentRecommendationSurface() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE ledger.decision_record")
        .contains("UNIQUE (source_snapshot_id)")
        .contains("policy_version VARCHAR(32) NOT NULL")
        .contains("trace_id VARCHAR(64)")
        .contains("CREATE FUNCTION ledger.reject_decision_record_mutation()")
        .contains("CREATE TRIGGER trg_decision_record_append_only")
        .contains("BEFORE UPDATE OR DELETE ON ledger.decision_record")
        .contains("CREATE TABLE core.learning_recommendation")
        .contains("REFERENCES ledger.decision_record(id)")
        .doesNotContain("DOUBLE PRECISION");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V010__recommendation_decision.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
