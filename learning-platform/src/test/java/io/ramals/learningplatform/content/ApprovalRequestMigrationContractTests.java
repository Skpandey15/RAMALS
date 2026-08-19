package io.ramals.learningplatform.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ApprovalRequestMigrationContractTests {
  @Test
  void migrationDefinesSeparateDurableStateAndCommandIdempotency() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V019__limited_durable_approval_workflow.sql"));
    assertThat(sql).contains("CREATE TABLE core.assessment_approval_request")
        .contains("CREATE TABLE core.assessment_approval_command")
        .contains("APPROVAL_REQUIRED")
        .contains("SUPERSEDED")
        .contains("reject_approval_provenance_mutation")
        .contains("uq_approval_create_idempotency");
  }
}
