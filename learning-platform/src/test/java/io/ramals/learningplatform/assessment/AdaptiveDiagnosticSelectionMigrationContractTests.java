package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V050 declares which selector governs a version, and widens the selection-reason vocabulary the
 * adaptive selector needs. Neither statement touches V1's frozen selector or its three reasons.
 */
class AdaptiveDiagnosticSelectionMigrationContractTests {

  @Test
  void selectionPolicyIsDeclaredOnTheVersionRatherThanInferred() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("ALTER TABLE core.assessment_version")
        .contains("ADD COLUMN selection_policy_version VARCHAR(48)")
        .contains("ck_assessment_version_selection_policy");
  }

  @Test
  void onlyTheKafkaV2DraftVersionDeclaresV2() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("UPDATE core.assessment_version")
        .contains("SET selection_policy_version = 'DIAGNOSTIC_SELECTION_V2'")
        .contains("WHERE id = '01900000-0000-7000-8000-000000000403'");
    // Declaring the selector is metadata, not publication: this migration must never touch status
    // or published_at, or the DRAFT-stays-DRAFT decision V049 documented would be undone here.
    assertThat(migration).doesNotContain("SET status = 'PUBLISHED'");
  }

  @Test
  void theReasonVocabularyIsWidenedNotReplaced() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("ALTER TABLE core.assessment_attempt_item DROP CONSTRAINT ck_assessment_attempt_item_reason")
        .contains("ADD CONSTRAINT ck_assessment_attempt_item_reason CHECK")
        // V045's original three, still present -- a true widening, not a replacement.
        .contains("'SKILL_COVERAGE', 'DIFFICULTY_COVERAGE', 'FILL'")
        .contains("'UNSEEN_ITEM'")
        .contains("'LOW_CONFIDENCE'")
        .contains("'WEAK_SKILL'")
        .contains("'OBJECTIVE_COVERAGE_GAP'")
        .contains("'DIFFICULTY_PROGRESSION'")
        .contains("'MASTERY_CONFIRMATION'");
  }

  @Test
  void retentionCheckIsDeliberatelyNotAdmittedAsAValue() throws IOException {
    // Spaced-repetition reassessment is a future, separately versioned policy; ordinary bank
    // exhaustion must not be able to borrow its reason ahead of that policy existing. The migration
    // explains this in prose (hence no bare doesNotContain("RETENTION_CHECK")), but the value itself
    // must never be admitted into the CHECK's membership list.
    assertThat(migration()).doesNotContain("'RETENTION_CHECK'");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream(
        "/db/migration/V050__adaptive_diagnostic_selection.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
