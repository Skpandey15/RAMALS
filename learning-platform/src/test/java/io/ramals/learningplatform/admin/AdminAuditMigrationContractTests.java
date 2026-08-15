package io.ramals.learningplatform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AdminAuditMigrationContractTests {

  @Test
  void migrationDefinesAppendOnlyAdminActivityAudit() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE audit.admin_activity")
        .contains("interaction_id VARCHAR(64) NOT NULL")
        .contains("trace_id VARCHAR(64)")
        .contains("outcome IN ('SUCCESS', 'REJECTED')")
        .contains("CREATE FUNCTION audit.reject_admin_activity_mutation()")
        .contains("CREATE TRIGGER trg_admin_activity_append_only")
        .contains("BEFORE UPDATE OR DELETE ON audit.admin_activity");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V013__admin_audit.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
