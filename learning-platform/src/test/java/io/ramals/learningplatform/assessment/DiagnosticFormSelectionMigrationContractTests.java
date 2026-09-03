package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The record of what a learner was asked has to be a record: written once, never rewritten, and
 * binding on what may later be answered. These are the clauses that make it one.
 */
class DiagnosticFormSelectionMigrationContractTests {

  @Test
  void migrationRecordsTheSelectedFormAndProtectsIt() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.assessment_attempt_item")
        .contains("presentation_order INTEGER NOT NULL")
        .contains("selection_reason VARCHAR(24) NOT NULL")
        .contains("UNIQUE (attempt_id, item_version_id)")
        .contains("UNIQUE (attempt_id, presentation_order)")
        .contains("REFERENCES core.assessment_attempt(id) ON DELETE RESTRICT")
        .contains("REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT")
        .contains("ADD COLUMN selection_policy VARCHAR(48)");
  }

  @Test
  void aSelectedFormIsImmutableAndBindsTheResponsesThatMayFollow() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE FUNCTION core.protect_assessment_attempt_item()")
        .contains("selected assessment items are immutable")
        .contains("items may only be selected for an in-progress attempt")
        // The response guard is extended, not replaced: an answer to an item this attempt never
        // presented must not become evidence.
        .contains("CREATE OR REPLACE FUNCTION core.protect_assessment_response()")
        .contains("was not selected for attempt")
        .contains("assessment responses are immutable")
        .contains("responses may only be added to an in-progress attempt");
  }

  private String migration() throws IOException {
    try (var input =
        getClass().getResourceAsStream("/db/migration/V045__diagnostic_form_selection.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
