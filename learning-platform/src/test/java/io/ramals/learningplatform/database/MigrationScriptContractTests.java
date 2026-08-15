package io.ramals.learningplatform.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationScriptContractTests {

  @Test
  void baselineCreatesApprovedSchemasAndRevokesPublicAccess() throws IOException {
    String migration = resource("/db/migration/V001__baseline_schemas.sql");
    assertThat(migration)
        .contains("CREATE SCHEMA IF NOT EXISTS core")
        .contains("CREATE SCHEMA IF NOT EXISTS ledger")
        .contains("CREATE SCHEMA IF NOT EXISTS audit")
        .contains("REVOKE CREATE ON SCHEMA public FROM PUBLIC")
        .contains("REVOKE ALL ON SCHEMA core, ledger, audit FROM PUBLIC");
  }

  @Test
  void runtimeDefaultsAreMutableOnlyInCoreAndAppendOnlyInLedgerAndAudit() throws IOException {
    String migration = resource("/db/migration/V002__roles_and_grants_foundation.sql");
    assertThat(migration)
        .contains("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core")
        .contains("REVOKE ALL ON TABLE core.flyway_schema_history FROM ramals_core_runtime")
        .contains("GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA ledger TO ramals_core_runtime")
        .contains("GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA audit TO ramals_core_runtime")
        .contains("ON ALL TABLES IN SCHEMA ledger FROM ramals_core_runtime")
        .contains("ON ALL TABLES IN SCHEMA audit FROM ramals_core_runtime")
        .contains("IN SCHEMA ledger\n  GRANT SELECT, INSERT ON TABLES TO ramals_core_runtime")
        .contains("IN SCHEMA audit\n  GRANT SELECT, INSERT ON TABLES TO ramals_core_runtime")
        .doesNotContain("GRANT UPDATE ON ALL TABLES IN SCHEMA ledger")
        .doesNotContain("GRANT DELETE ON ALL TABLES IN SCHEMA ledger")
        .doesNotContain("GRANT UPDATE ON ALL TABLES IN SCHEMA audit")
        .doesNotContain("GRANT DELETE ON ALL TABLES IN SCHEMA audit");
  }

  private String resource(String path) throws IOException {
    try (var input = getClass().getResourceAsStream(path)) {
      assertThat(input).as("migration resource %s", path).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
