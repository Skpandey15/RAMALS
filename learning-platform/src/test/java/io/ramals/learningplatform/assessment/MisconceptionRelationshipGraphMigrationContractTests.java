package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V061 (M2-ADR-033 step 1): the two new additive misconception-graph tables, their DRAFT/PUBLISHED
 * lifecycle, the closed relationship-type set, canonical ordering, {@code SPECIALISES} acyclicity,
 * the prerequisite-link curriculum check, and the minimal grounded seed. Foundation only -- the
 * migration touches nothing a runtime selector reads.
 */
class MisconceptionRelationshipGraphMigrationContractTests {

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream(
        "/db/migration/V061__misconception_relationship_graph.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }

  @Test
  @DisplayName("both edge tables are created, keyed by FK to the authored entities they connect")
  void bothTablesAreCreatedWithTheirForeignKeys() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE TABLE core.misconception_relationship")
        .contains("misconception_a_id UUID NOT NULL REFERENCES core.misconception(id) ON DELETE RESTRICT")
        .contains("misconception_b_id UUID NOT NULL REFERENCES core.misconception(id) ON DELETE RESTRICT")
        .contains("CREATE TABLE core.misconception_prerequisite_link")
        .contains("misconception_id UUID NOT NULL REFERENCES core.misconception(id) ON DELETE RESTRICT")
        .contains("prerequisite_skill_id UUID NOT NULL REFERENCES core.skill(id) ON DELETE RESTRICT");
    // The prerequisite link's far endpoint is a skill, never an objective (M2-ADR-033 §1).
    assertThat(migration).doesNotContain("prerequisite_skill_id UUID NOT NULL REFERENCES core.learning_objective");
  }

  @Test
  @DisplayName("MISCONCEPTION_RELATED admits exactly the three ADR-authored sub-types")
  void relatedTypeIsTheClosedAdrSet() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("ck_misconception_relationship_type")
        .contains("relationship_type IN ('CO_OCCURS_WITH', 'SPECIALISES', 'CONTRASTS_WITH')");
    String typeConstraint = migration.substring(
        migration.indexOf("ck_misconception_relationship_type"),
        migration.indexOf(')', migration.indexOf("relationship_type IN")));
    // GENERALISES is SPECIALISES read backward and is never a stored value.
    assertThat(typeConstraint).doesNotContain("GENERALISES");
    assertThat(typeConstraint).doesNotContain("MISCONCEPTION_PREREQUISITE_LINK");
  }

  @Test
  @DisplayName("self-edges are rejected on both tables")
  void selfEdgesAreRejected() throws IOException {
    assertThat(migration())
        .contains("ck_misconception_relationship_not_self")
        .contains("CHECK (misconception_a_id <> misconception_b_id)");
  }

  @Test
  @DisplayName("symmetric related types are canonically ordered; SPECIALISES keeps its direction")
  void symmetricTypesAreCanonicallyOrdered() throws IOException {
    assertThat(migration())
        .contains("ck_misconception_relationship_canonical_order")
        .contains("relationship_type = 'SPECIALISES' OR misconception_a_id < misconception_b_id");
  }

  @Test
  @DisplayName("a logical duplicate cannot be stored on either table")
  void logicalDuplicatesAreRejected() throws IOException {
    assertThat(migration())
        .contains("UNIQUE (misconception_a_id, misconception_b_id, relationship_type)")
        .contains("UNIQUE (misconception_id, prerequisite_skill_id)");
  }

  @Test
  @DisplayName("both tables carry a required non-blank rationale and a consistent publication time")
  void rationaleAndPublicationTimeAreConstrained() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("rationale TEXT NOT NULL")
        .contains("ck_misconception_relationship_rationale")
        .contains("ck_misconception_prerequisite_link_rationale")
        .contains("length(btrim(rationale)) > 0")
        .contains("ck_misconception_relationship_publication_time")
        .contains("ck_misconception_prerequisite_link_publication_time")
        .contains("(status = 'DRAFT' AND published_at IS NULL)")
        .contains("(status = 'PUBLISHED' AND published_at IS NOT NULL)");
  }

  @Test
  @DisplayName("published edges are immutable and may reference only published endpoints")
  void publishedEdgesAreImmutableAndPublishedEndpointOnly() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("CREATE FUNCTION core.protect_misconception_relationship")
        .contains("published misconception relationship % is immutable")
        .contains("CREATE TRIGGER trg_misconception_relationship_guard")
        .contains("may reference only published misconceptions")
        .contains("CREATE FUNCTION core.protect_misconception_prerequisite_link")
        .contains("published misconception prerequisite link % is immutable")
        .contains("CREATE TRIGGER trg_misconception_prerequisite_link_guard")
        .contains("may reference only a published misconception");
  }

  @Test
  @DisplayName("SPECIALISES is acyclic, enforced with the same recursive shape as skill_prerequisite")
  void specialisesIsAcyclic() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("WITH RECURSIVE reachable(misconception_id)")
        .contains("WHERE edge.relationship_type = 'SPECIALISES'")
        .contains("SPECIALISES cycle detected");
  }

  @Test
  @DisplayName("a prerequisite link publishes only when it matches a real curriculum prerequisite")
  void prerequisiteLinkRequiresAMatchingSkillPrerequisiteRow() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("FROM core.skill_prerequisite sp")
        .contains("sp.curriculum_version_id = owning_curriculum_version_id")
        .contains("sp.skill_id = owning_skill_id")
        .contains("sp.prerequisite_skill_id = NEW.prerequisite_skill_id")
        .contains("is not a curriculum prerequisite of misconception");
    // The owning skill is resolved through the misconception's exclusive arc, covering all three
    // target shapes.
    assertThat(migration)
        .contains("COALESCE(m.target_objective_id, dn.objective_id, dnp.objective_id)");
  }

  @Test
  @DisplayName("useful lookup indexes exist on both edge tables")
  void indexesExist() throws IOException {
    assertThat(migration())
        .contains("CREATE INDEX idx_misconception_relationship_a")
        .contains("CREATE INDEX idx_misconception_relationship_b")
        .contains("CREATE INDEX idx_misconception_prerequisite_link_misconception")
        .contains("CREATE INDEX idx_misconception_prerequisite_link_skill");
  }

  @Test
  @DisplayName("the grounded seed uses the real Kafka v2 acks vertical slice")
  void groundedSeedIsOnRealContent() throws IOException {
    String migration = migration();
    // One new misconception on the real ACK_DURABILITY objective ...0d11.
    assertThat(migration)
        .contains("'01900000-0000-7000-8000-000000000f04'")
        .contains("'01900000-0000-7000-8000-000000000d11', NULL, 'PUBLISHED'");
    // One CONTRASTS_WITH edge, canonical a = ...0f03 (< ...0f04).
    assertThat(migration)
        .contains("INSERT INTO core.misconception_relationship")
        .contains("'01900000-0000-7000-8000-000000000f03', '01900000-0000-7000-8000-000000000f04',")
        .contains("'CONTRASTS_WITH', 'PUBLISHED'");
    // One MISCONCEPTION_PREREQUISITE_LINK: ...0f03 -> skill ...0101 (a real prerequisite of ...0107).
    assertThat(migration)
        .contains("INSERT INTO core.misconception_prerequisite_link")
        .contains("'01900000-0000-7000-8000-000000000f03', '01900000-0000-7000-8000-000000000101',")
        .contains("'PUBLISHED'");
  }

  @Test
  @DisplayName("this is foundation only -- no statement alters an existing table or touches a "
      + "selector")
  void foundationOnlyNoRuntimeWiring() throws IOException {
    // The header comment names selection engines and other tables in prose to explain what this
    // migration deliberately does not do; these checks are for actual statements, not the words.
    String migration = migration();
    assertThat(migration)
        .doesNotContain("ALTER TABLE core.assessment_version")
        .doesNotContain("ALTER TABLE core.misconception")
        .doesNotContain("ALTER TABLE core.misconception ")
        .doesNotContain("ALTER TABLE core.diagnostic_node")
        .doesNotContain("ALTER TABLE core.skill")
        .doesNotContain("ALTER TABLE core.skill_prerequisite")
        .doesNotContain("ALTER TABLE core.learning_objective")
        .doesNotContain("UPDATE core.assessment_version")
        .doesNotContain("INSERT INTO core.diagnostic_probe_provenance")
        .doesNotContain("INSERT INTO core.misconception_evidence_observation")
        .doesNotContain("selection_policy_version");
    // No DROP anywhere -- additive only.
    assertThat(migration).doesNotContain("DROP TABLE").doesNotContain("DROP COLUMN");
  }

  @Test
  @DisplayName("no column on either edge table carries learner state, confidence, probability, or "
      + "a score/weight")
  void noLearnerStateOrScoreColumns() throws IOException {
    String migration = migration();
    for (String table : new String[] {
        "CREATE TABLE core.misconception_relationship",
        "CREATE TABLE core.misconception_prerequisite_link"}) {
      String block = migration.substring(
          migration.indexOf(table), migration.indexOf("\n);", migration.indexOf(table)));
      for (String forbidden : new String[] {
          "learner_id", "learner ", "mastery", "confidence", "probability", "likelihood",
          "posterior", "entropy", "information_gain", "weight", "strength", "relevance_score",
          "importance_score", "diagnostic_score", "rank_", "ranking", "adaptive",
          "misconception_level", "misconception_depth", "graph_hierarchy_level",
          "parent_misconception_id", "child_misconception_id"}) {
        assertThat(block)
            .as("%s must not define a %s column", table, forbidden)
            .doesNotContain(forbidden);
      }
    }
  }
}
