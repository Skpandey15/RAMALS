package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V057 (M2-ADR-026): the granular diagnostic ontology foundation -- three new, additive tables.
 * No existing migration file is touched; core.learning_objective, core.assessment_item_objective,
 * core.diagnostic_probe_relationship (V054), core.diagnostic_probe_provenance (V055), and
 * core.diagnostic_confidence_observation (V056) are all untouched.
 */
class GranularDiagnosticOntologyMigrationContractTests {

  @Test
  void diagnosticNodeSupportsExactlyConceptAndSubConceptWithMutuallyExclusiveShape()
      throws IOException {
    assertThat(migration())
        .contains("CREATE TABLE core.diagnostic_node")
        .contains("ck_diagnostic_node_type")
        .contains("node_type IN ('CONCEPT', 'SUB_CONCEPT')")
        .contains("ck_diagnostic_node_objective_shape")
        .contains("(node_type = 'CONCEPT') = (objective_id IS NOT NULL)")
        .contains("ck_diagnostic_node_parent_shape")
        .contains("(node_type = 'CONCEPT') = (parent_node_id IS NULL)");
  }

  @Test
  void aSubConceptsParentMustBeAConceptEnforcedByTheGuardTriggerNotAPlainCheck()
      throws IOException {
    assertThat(migration())
        .contains("CREATE FUNCTION core.protect_diagnostic_node")
        .contains("SELECT * INTO parent_row FROM core.diagnostic_node WHERE id = NEW.parent_node_id")
        .contains("a SUB_CONCEPT''s parent must be a CONCEPT")
        .contains("CREATE TRIGGER trg_diagnostic_node_guard")
        .contains("BEFORE INSERT OR UPDATE OR DELETE ON core.diagnostic_node");
  }

  @Test
  void aSubConceptCannotPublishWhileItsParentConceptIsStillDraft() throws IOException {
    assertThat(migration())
        .contains("NEW.status = 'PUBLISHED' AND parent_row.status <> 'PUBLISHED'")
        .contains("a SUB_CONCEPT cannot be published while its parent CONCEPT % is not yet PUBLISHED");
  }

  @Test
  void aMisconceptionCannotPublishWhileItsTargetNodeIsStillDraft() throws IOException {
    assertThat(migration())
        .contains("SELECT status INTO target_node_status")
        .contains("FROM core.diagnostic_node WHERE id = NEW.target_diagnostic_node_id")
        .contains("misconception cannot be published while its target diagnostic node % is not yet PUBLISHED");
  }

  @Test
  void aMisconceptionCannotPublishWhileItsTargetObjectivesCurriculumIsStillDraft()
      throws IOException {
    // Not a new LearningObjective lifecycle rule -- reuses the same canonical
    // learning_objective -> skill_version -> curriculum_version join
    // core.protect_versioned_curriculum_row already resolves elsewhere.
    assertThat(migration())
        .contains("JOIN core.skill_version sv ON sv.id = lo.skill_version_id")
        .contains("JOIN core.curriculum_version cv ON cv.id = sv.curriculum_version_id")
        .contains("misconception cannot be published while its target objective %''s curriculum version is still DRAFT");
  }

  @Test
  void diagnosticNodeIsAppendOnlyOncePublished() throws IOException {
    assertThat(migration())
        .contains("published diagnostic node % is immutable");
  }

  @Test
  void misconceptionHasAnExclusiveArcTargetOverObjectiveOrNode() throws IOException {
    assertThat(migration())
        .contains("CREATE TABLE core.misconception")
        .contains("target_objective_id UUID REFERENCES core.learning_objective(id)")
        .contains("target_diagnostic_node_id UUID REFERENCES core.diagnostic_node(id)")
        .contains("ck_misconception_target")
        .contains("num_nonnulls(target_objective_id, target_diagnostic_node_id) = 1");
  }

  @Test
  void misconceptionIsDraftPublishedAndAppendOnlyOncePublished() throws IOException {
    assertThat(migration())
        .contains("ck_misconception_status")
        .contains("status IN ('DRAFT', 'PUBLISHED')")
        .contains("CREATE FUNCTION core.protect_misconception")
        .contains("published misconception % is immutable")
        .contains("CREATE TRIGGER trg_misconception_guard");
  }

  @Test
  void optionMappingReferencesARealAssessmentItemAndMisconception() throws IOException {
    assertThat(migration())
        .contains("CREATE TABLE core.assessment_item_option_misconception")
        .contains("item_version_id UUID NOT NULL REFERENCES core.assessment_item_version(id)")
        .contains("misconception_id UUID NOT NULL REFERENCES core.misconception(id)");
  }

  @Test
  void optionMappingGuardTriggerValidatesSingleChoiceRealAndIncorrectOption() throws IOException {
    assertThat(migration())
        .contains("CREATE FUNCTION core.protect_assessment_item_option_misconception")
        .contains("item_row.item_type <> 'SINGLE_CHOICE'")
        .contains("jsonb_array_elements(item_row.options_jsonb)")
        .contains("does not exist on item")
        .contains("jsonb_array_elements_text(item_row.answer_key_jsonb -> 'correct')")
        .contains("cannot be tagged as a misconception option");
  }

  @Test
  void optionMappingCannotPublishBeforeItsMisconceptionIsPublished() throws IOException {
    assertThat(migration())
        .contains("SELECT status INTO misconception_status FROM core.misconception WHERE id = NEW.misconception_id")
        .contains("must be PUBLISHED before this option mapping can be published");
  }

  @Test
  void optionMappingIsAppendOnlyOncePublished() throws IOException {
    assertThat(migration())
        .contains("published misconception option mapping is immutable");
  }

  @Test
  void deliberatelyNoCompositeForeignKeyAgainstTheAlreadyMergedItemVersionTable()
      throws IOException {
    // The same lesson the H5 hardening round already established: adding a new unique key to an
    // already-merged table to support a composite FK breaks rollback for the previous release's
    // image. This migration must not repeat that mistake against assessment_item_version.
    assertThat(migration())
        .doesNotContain("ALTER TABLE core.assessment_item_version");
  }

  @Test
  void noExistingMigrationOrTableIsTouchedByThisMigration() throws IOException {
    assertThat(migration())
        .doesNotContain("ALTER TABLE core.learning_objective")
        .doesNotContain("ALTER TABLE core.assessment_item_objective")
        .doesNotContain("ALTER TABLE core.diagnostic_probe_relationship")
        .doesNotContain("ALTER TABLE core.diagnostic_probe_provenance")
        .doesNotContain("ALTER TABLE core.diagnostic_confidence_observation")
        .doesNotContain("INSERT INTO core.learning_objective")
        .doesNotContain("INSERT INTO core.diagnostic_probe_relationship")
        .doesNotContain("INSERT INTO core.diagnostic_probe_provenance")
        .doesNotContain("INSERT INTO core.diagnostic_confidence_observation");
  }

  @Test
  void theVerticalSliceIsSeededAgainstRealAlreadyMigratedKafkaContent() throws IOException {
    assertThat(migration())
        // ACKS_DURABILITY_TRADEOFFS (d11) and its real item ACKS_MCQ_A1 (...0625), option "A".
        .contains("'01900000-0000-7000-8000-000000000d11'")
        .contains("'01900000-0000-7000-8000-000000000625', 'A'");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream(
        "/db/migration/V057__granular_diagnostic_ontology.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
