package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AssessmentResponseMigrationContractTests {

  @Test
  void migrationDefinesImmutableAppendOnlyResponses() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.assessment_response")
        .contains("response_jsonb JSONB NOT NULL")
        .contains("is_correct BOOLEAN NOT NULL")
        .contains("UNIQUE (attempt_id, item_version_id)")
        .contains("CREATE FUNCTION core.protect_assessment_response()")
        .contains("responses may only be added to an in-progress attempt")
        .contains("assessment responses are immutable");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/V006__assessment_responses.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
