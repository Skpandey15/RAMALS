package io.ramals.learningplatform.mastery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V046 supplies the two facts the V1 policy asked for and could never obtain, and it does so
 * without touching anything already written.
 */
class MasteryCoverageMigrationContractTests {

  @Test
  void itemsAreTaggedAgainstTheObjectivesTheyAssess() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.assessment_item_objective")
        .contains("REFERENCES core.assessment_item_version(id) ON DELETE RESTRICT")
        .contains("REFERENCES core.learning_objective(id) ON DELETE RESTRICT")
        .contains("PRIMARY KEY (item_version_id, objective_id)")
        // All five seeded KAFKA diagnostic items, tagged.
        .contains("'01900000-0000-7000-8000-000000000411', '01900000-0000-7000-8000-000000000301'")
        .contains("'01900000-0000-7000-8000-000000000412', '01900000-0000-7000-8000-000000000302'")
        .contains("'01900000-0000-7000-8000-000000000413', '01900000-0000-7000-8000-000000000303'")
        .contains("'01900000-0000-7000-8000-000000000414', '01900000-0000-7000-8000-000000000307'")
        .contains("'01900000-0000-7000-8000-000000000415', '01900000-0000-7000-8000-000000000309'");
  }

  @Test
  void anItemCannotBeTaggedAgainstAnotherSkillsObjective() throws IOException {
    // The one way objective coverage could be made to lie: credit a skill for an objective it does
    // not own. Refused in the database, not only in the service that writes evidence.
    assertThat(migration())
        .contains("CREATE FUNCTION core.validate_assessment_item_objective()")
        .contains("but item % assesses skill %")
        .contains("trg_assessment_item_objective_skill_match");
  }

  @Test
  void evidenceAndSnapshotsRecordWhatWasCovered() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("ALTER TABLE ledger.evidence")
        .contains("ADD COLUMN covered_objective_ids UUID[]")
        .contains("ADD COLUMN covered_difficulty_bands VARCHAR(16)[]")
        // A band outside the vocabulary cannot be stored, so the fail-closed parse on read can
        // only ever be defending against a row this constraint did not exist to reject.
        .contains("covered_difficulty_bands <@ ARRAY['EASY', 'MEDIUM', 'HARD']::VARCHAR(16)[]")
        .contains("ALTER TABLE ledger.mastery_snapshot")
        .contains("ADD COLUMN status_policy_version VARCHAR(32)")
        .contains("ADD COLUMN objective_coverage NUMERIC(5, 4)");
  }

  @Test
  void nothingAlreadyWrittenIsRewritten() throws IOException {
    String migration = migration();
    // Additive DDL only. No UPDATE anywhere: an append-only ledger stays append-only, published
    // assessment items stay immutable, and no historical coverage is invented for rows that never
    // recorded any. The new columns are nullable for exactly that reason.
    assertThat(migration).doesNotContain("UPDATE ledger.");
    assertThat(migration).doesNotContain("UPDATE core.assessment_item_version");
    assertThat(migration).doesNotContain("ALTER TABLE core.assessment_item_version");
    assertThat(migration).doesNotContain("DISABLE TRIGGER");
    assertThat(migration).doesNotContain("NOT NULL DEFAULT ARRAY");
  }

  private String migration() throws IOException {
    try (var input =
        getClass().getResourceAsStream("/db/migration/V046__mastery_coverage_v2.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
