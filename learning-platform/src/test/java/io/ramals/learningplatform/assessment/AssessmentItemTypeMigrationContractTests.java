package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V047 admits four item types without weakening what SINGLE_CHOICE already required, and uses the
 * {@code relaxes-constraint} declaration exactly where a CHECK genuinely could not be proved safe
 * by value-set comparison alone.
 */
class AssessmentItemTypeMigrationContractTests {

  @Test
  void fourItemTypesAreAdmitted() throws IOException {
    assertThat(migration())
        .contains("ck_assessment_item_type")
        .contains("'SINGLE_CHOICE', 'FILL_BLANK', 'SHORT_ANSWER', 'USE_CASE'");
  }

  @Test
  void optionsAreRequiredOnlyForSingleChoice() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("ck_assessment_item_options")
        .contains("item_type = 'SINGLE_CHOICE' AND jsonb_array_length(options_jsonb) >= 2")
        .contains("item_type <> 'SINGLE_CHOICE' AND jsonb_array_length(options_jsonb) = 0")
        // Declared, and the declaration sits on the ADD statement it licenses, not above the pair.
        .contains("-- relaxes-constraint: ck_assessment_item_options,");
  }

  @Test
  void answerKeyShapeMatchesItsItemTypeAndSingleChoiceIsUnweakened() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("ck_assessment_item_answer_key")
        .contains("item_type = 'SINGLE_CHOICE'")
        .contains("jsonb_typeof(answer_key_jsonb -> 'accepted')")
        .contains("jsonb_typeof(answer_key_jsonb -> 'rubric')")
        // The NULL-on-missing-key defect this constraint was rewritten to avoid.
        .contains("COALESCE")
        .contains("-- relaxes-constraint: ck_assessment_item_answer_key,");
  }

  @Test
  void unscoreableTypesAreDocumentedAsNotLearnerReachable() throws IOException {
    assertThat(migration())
        .contains("SHORT_ANSWER and USE_CASE are")
        .contains("M2-ADR-022");
  }

  @Test
  void packetPolicyIsRecordedPerAttemptAndNeverBackfilled() throws IOException {
    assertThat(migration())
        .contains("ADD COLUMN packet_policy VARCHAR(48)")
        .contains("ck_assessment_attempt_packet_policy");
  }

  private String migration() throws IOException {
    try (var input =
        getClass().getResourceAsStream("/db/migration/V047__assessment_item_types.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
