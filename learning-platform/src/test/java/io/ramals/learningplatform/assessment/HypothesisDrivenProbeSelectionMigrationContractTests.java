package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V055 (M2-ADR-025): widens the selection-reason vocabulary for DIAGNOSTIC_SELECTION_V5 and adds
 * {@code core.diagnostic_probe_provenance} -- both additive; V054's own
 * {@code core.diagnostic_probe_relationship} table is untouched.
 */
class HypothesisDrivenProbeSelectionMigrationContractTests {

  @Test
  void theReasonVocabularyIsWidenedToAdmitHypothesisDrivenProbe() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("ALTER TABLE core.assessment_attempt_item DROP CONSTRAINT ck_assessment_attempt_item_reason")
        .contains("ADD CONSTRAINT ck_assessment_attempt_item_reason CHECK")
        // Every one of V053's eleven values, still present -- a true widening, not a replacement.
        .contains("'SKILL_COVERAGE', 'DIFFICULTY_COVERAGE', 'FILL'")
        .contains("'UNSEEN_ITEM'")
        .contains("'LOW_CONFIDENCE'")
        .contains("'WEAK_SKILL'")
        .contains("'OBJECTIVE_COVERAGE_GAP'")
        .contains("'DIFFICULTY_PROGRESSION'")
        .contains("'MASTERY_CONFIRMATION'")
        .contains("'PREREQUISITE_NOT_SECURED'")
        .contains("'HYPOTHESIS_CONFIRMATION'")
        .contains("'HYPOTHESIS_DRIVEN_PROBE'");
  }

  @Test
  void provenanceAdmitsAllFourRelationshipTypesUnlikeV054sOwnTwoValueCheck() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.diagnostic_probe_provenance")
        .contains("ck_diagnostic_probe_provenance_type")
        .contains("'SAME_OBJECTIVE_CONFIRMATION', 'PREREQUISITE_VALIDATION', 'ROOT_CAUSE_PROBE', 'CONTRADICTION_CHECK'");
  }

  @Test
  void provenanceReferencesTheNewAttemptItemByItsExistingCompositeKey() throws IOException {
    assertThat(migration())
        .contains("UNIQUE (attempt_id, item_version_id)")
        .contains("FOREIGN KEY (attempt_id, item_version_id)")
        .contains("REFERENCES core.assessment_attempt_item(attempt_id, item_version_id)");
  }

  @Test
  void authorizingRelationshipIdIsNullable() throws IOException {
    assertThat(migration())
        .contains("authorizing_relationship_id UUID REFERENCES core.diagnostic_probe_relationship(id)");
  }

  @Test
  void provenanceIsImmutableOnceWrittenAndInsertableOnlyWhileInProgress() throws IOException {
    assertThat(migration())
        .contains("CREATE FUNCTION core.protect_probe_provenance")
        .contains("probe provenance is immutable")
        .contains("probe provenance may only be recorded for an in-progress attempt")
        .contains("CREATE TRIGGER trg_probe_provenance_guard")
        .contains("BEFORE INSERT OR UPDATE OR DELETE ON core.diagnostic_probe_provenance");
  }

  @Test
  void v054sOwnTableIsNeverTouchedByThisMigration() throws IOException {
    assertThat(migration())
        .doesNotContain("ALTER TABLE core.diagnostic_probe_relationship")
        .doesNotContain("INSERT INTO core.diagnostic_probe_relationship")
        .doesNotContain("UPDATE core.diagnostic_probe_relationship");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream(
        "/db/migration/V055__hypothesis_driven_probe_selection.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
