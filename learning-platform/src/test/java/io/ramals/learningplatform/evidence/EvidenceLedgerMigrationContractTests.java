package io.ramals.learningplatform.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EvidenceLedgerMigrationContractTests {

  @Test
  void migrationDefinesAppendOnlyEvidenceWithLineageIdempotency() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE ledger.evidence")
        .contains("lineage_key TEXT NOT NULL")
        .contains("UNIQUE (lineage_key)")
        .contains("interaction_id VARCHAR(64) NOT NULL")
        .contains("adjusts_evidence_id UUID REFERENCES ledger.evidence(id)")
        .contains("ck_evidence_adjustment_link")
        .contains("CREATE FUNCTION ledger.reject_evidence_mutation()")
        .contains("CREATE TRIGGER trg_evidence_append_only")
        .contains("BEFORE UPDATE OR DELETE ON ledger.evidence")
        .doesNotContain("DOUBLE PRECISION");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V007__evidence_ledger.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
