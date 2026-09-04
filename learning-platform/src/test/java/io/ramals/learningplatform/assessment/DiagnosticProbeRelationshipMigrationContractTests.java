package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V054 (M2-ADR-024): the new {@code core.diagnostic_probe_relationship} table -- only
 * {@code ROOT_CAUSE_PROBE}/{@code CONTRADICTION_CHECK}, an immutable-once-published lifecycle, and
 * the three real, grounded seed relationships this PR's integration tests exercise.
 */
class DiagnosticProbeRelationshipMigrationContractTests {

  @Test
  void theTableAdmitsOnlyTheTwoStorageBackedRelationshipTypes() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.diagnostic_probe_relationship")
        .contains("ck_diagnostic_probe_relationship_type")
        .contains("relationship_type IN ('ROOT_CAUSE_PROBE', 'CONTRADICTION_CHECK')");

    // SAME_OBJECTIVE_CONFIRMATION and PREREQUISITE_VALIDATION are deliberately absent from the
    // membership check itself -- they are read from existing tables, never stored here. The header
    // comment explains why in prose, so this asserts the constraint's own value list specifically,
    // not that the file never mentions either name at all.
    String typeConstraint = migration.substring(
        migration.indexOf("ck_diagnostic_probe_relationship_type"),
        migration.indexOf(';', migration.indexOf("ck_diagnostic_probe_relationship_type")));
    assertThat(typeConstraint)
        .doesNotContain("SAME_OBJECTIVE_CONFIRMATION")
        .doesNotContain("PREREQUISITE_VALIDATION");
  }

  @Test
  void aRowCannotReferenceItsOwnObjectiveAsItsTarget() throws IOException {
    assertThat(migration()).contains("ck_diagnostic_probe_relationship_not_self")
        .contains("CHECK (source_objective_id <> target_objective_id)");
  }

  @Test
  void publishedRowsAreImmutable() throws IOException {
    assertThat(migration())
        .contains("CREATE FUNCTION core.protect_published_probe_relationship")
        .contains("published probe relationship % is immutable")
        .contains("CREATE TRIGGER trg_probe_relationship_immutable")
        .contains("BEFORE INSERT OR UPDATE OR DELETE ON core.diagnostic_probe_relationship");
  }

  @Test
  void rationaleIsRequiredAndCannotBeBlank() throws IOException {
    assertThat(migration())
        .contains("rationale TEXT NOT NULL")
        .contains("ck_diagnostic_probe_relationship_rationale")
        .contains("length(btrim(rationale)) > 0");
  }

  @Test
  void publicationTimeIsConsistentWithStatus() throws IOException {
    assertThat(migration())
        .contains("ck_diagnostic_probe_relationship_publication_time")
        .contains("(status = 'DRAFT' AND published_at IS NULL)")
        .contains("(status = 'PUBLISHED' AND published_at IS NOT NULL)");
  }

  @Test
  void theSameSourceTargetTypeCombinationCannotBeDeclaredTwice() throws IOException {
    assertThat(migration())
        .contains("UNIQUE (source_objective_id, target_objective_id, relationship_type)");
  }

  @Test
  void threeRelationshipsAreSeededGroundedInTheRealKafkaV2Curriculum() throws IOException {
    String migration = migration();
    // ACKS_DURABILITY_TRADEOFFS -> PRODUCER_IDEMPOTENCE, ROOT_CAUSE_PROBE -- both real objectives
    // on the real, seeded KAFKA_PRODUCER_ACKS v2 skill_version, verified against a real migrated
    // database before this migration was authored (not invented to make a test pass).
    assertThat(migration)
        .contains("'01900000-0000-7000-8000-000000000e01'")
        .contains("'01900000-0000-7000-8000-000000000d11', '01900000-0000-7000-8000-000000000d12'")
        .contains("'ROOT_CAUSE_PROBE', 'PUBLISHED'");

    // PRODUCER_IDEMPOTENCE -> IDEMPOTENT_DELIVERY (the KAFKA_PRODUCER_IDEMPOTENCE skill's own
    // carried-forward objective, with zero real items) -- the deliberate
    // RELATIONSHIP_DEFINED_BUT_NO_ITEMS integration case.
    assertThat(migration)
        .contains("'01900000-0000-7000-8000-000000000e02'")
        .contains("'01900000-0000-7000-8000-000000000d12', '01900000-0000-7000-8000-000000000c08'");

    // ACKS_DURABILITY_TRADEOFFS -> ACKS_SEMANTICS, CONTRADICTION_CHECK.
    assertThat(migration)
        .contains("'01900000-0000-7000-8000-000000000e03'")
        .contains("'01900000-0000-7000-8000-000000000d11', '01900000-0000-7000-8000-000000000d10'")
        .contains("'CONTRADICTION_CHECK', 'PUBLISHED'");
  }

  @Test
  void noSelectionPolicyOrSelectionReasonIsTouchedThisIsFoundationOnly() throws IOException {
    // M2-ADR-024 §4: this migration must not do anything a runtime selector would read. The header
    // comment discusses selection_policy_version in prose (explaining what this migration
    // deliberately does not do), so this checks for the absence of an actual statement that would
    // touch it, not the absence of the word.
    assertThat(migration())
        .doesNotContain("ALTER TABLE core.assessment_version")
        .doesNotContain("UPDATE core.assessment_version")
        .doesNotContain("ck_assessment_attempt_item_reason");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream(
        "/db/migration/V054__diagnostic_probe_relationship.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
