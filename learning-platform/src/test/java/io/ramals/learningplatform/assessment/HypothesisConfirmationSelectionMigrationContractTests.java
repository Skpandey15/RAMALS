package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V053 widens the selection-reason vocabulary for DIAGNOSTIC_SELECTION_V4, as a pure superset of
 * V051's ten values, and declares no version onto V4.
 */
class HypothesisConfirmationSelectionMigrationContractTests {

  @Test
  void theReasonVocabularyIsWidenedToAdmitHypothesisConfirmation() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("ALTER TABLE core.assessment_attempt_item DROP CONSTRAINT ck_assessment_attempt_item_reason")
        .contains("ADD CONSTRAINT ck_assessment_attempt_item_reason CHECK")
        // Every one of V051's ten values, still present -- a true widening, not a replacement.
        .contains("'SKILL_COVERAGE', 'DIFFICULTY_COVERAGE', 'FILL'")
        .contains("'UNSEEN_ITEM'")
        .contains("'LOW_CONFIDENCE'")
        .contains("'WEAK_SKILL'")
        .contains("'OBJECTIVE_COVERAGE_GAP'")
        .contains("'DIFFICULTY_PROGRESSION'")
        .contains("'MASTERY_CONFIRMATION'")
        .contains("'PREREQUISITE_NOT_SECURED'")
        .contains("'HYPOTHESIS_CONFIRMATION'");
  }

  @Test
  void noAssessmentVersionIsSwitchedToV4() throws IOException {
    assertThat(migration()).doesNotContain("SET selection_policy_version");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream(
        "/db/migration/V053__hypothesis_confirmation_selection.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
