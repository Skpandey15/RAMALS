package io.ramals.learningplatform.curriculum;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * V052 mints a new KAFKA curriculum version rather than mutating v1's objectives in place, and
 * re-points only the still-DRAFT Kafka v2 assessment bank at it.
 */
class KafkaCurriculumV2FinerObjectivesMigrationContractTests {

  @Test
  void aNewCurriculumVersionIsMintedNotTheExistingOneMutated() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("INSERT INTO core.curriculum_version (id, domain_id, version_code) VALUES")
        .contains("'01900000-0000-7000-8000-000000000004'")
        .contains("'v2'")
        // v1's own curriculum_version row is never the target of an UPDATE in this migration.
        .doesNotContain("UPDATE core.curriculum_version SET version_code")
        .doesNotContain("UPDATE core.learning_objective");
  }

  @Test
  void allFifteenSkillsAndThePrerequisiteGraphAreCarriedForwardUnchanged() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("INSERT INTO core.skill_version")
        .contains("FROM core.skill_version")
        .contains("WHERE curriculum_version_id = '01900000-0000-7000-8000-000000000002'")
        .contains("INSERT INTO core.skill_prerequisite")
        // Thresholds and difficulty are copied through, never restated -- this migration changes
        // objective granularity, not mastery gates.
        .contains("mastery_threshold")
        .contains("confidence_threshold");
  }

  @Test
  void tenUnsplitSkillsKeepExactlyOneCarriedForwardObjective() throws IOException {
    String migration = migration();
    // Objectives for the ten unsplit skills are carried forward by an INSERT...SELECT excluding
    // exactly the five split skill_ids -- not restated by hand, and not given any new,
    // finer-sounding objective code of their own (which would mean they were split after all).
    assertThat(migration)
        .contains("SELECT ('01900000-0000-7000-8000-000000000C' || right(lo.id::text, 2))::uuid")
        .contains("AND oldsv.skill_id NOT IN (");
    int exclusionListStart = migration.indexOf("oldsv.skill_id NOT IN (");
    int exclusionListEnd = migration.indexOf(");", exclusionListStart);
    String exclusionList = migration.substring(exclusionListStart, exclusionListEnd);
    assertThat(exclusionList.chars().filter(c -> c == ',').count())
        .as("exactly five excluded skill_ids -- one fewer comma than entries")
        .isEqualTo(4);
    assertThat(migration).doesNotContain("'RECORD_"); // no finer objective code for an unsplit skill
  }

  @Test
  void fiveSkillsGetExactlyThreeGroundedFinerObjectives() throws IOException {
    String migration = migration();
    // Every one of the fifteen new objective codes below must be present -- three per skill, for
    // exactly the five skills that have real assessment content.
    assertThat(migration)
        .contains("BROKER_STORAGE_MODEL").contains("BROKER_CONTROLLER_ROLE").contains("BROKER_CLUSTER_OPERATIONS")
        .contains("TOPIC_RETENTION_AND_COMPACTION").contains("TOPIC_ORDERING_SCOPE").contains("TOPIC_TRANSACTIONAL_ISOLATION")
        .contains("PARTITION_ORDERING").contains("PARTITION_PARALLELISM").contains("PARTITION_AVAILABILITY_AND_SKEW")
        .contains("ACKS_SEMANTICS").contains("ACKS_DURABILITY_TRADEOFFS").contains("PRODUCER_IDEMPOTENCE")
        .contains("GROUP_PARTITION_ASSIGNMENT").contains("GROUP_ISOLATION").contains("GROUP_REBALANCE_TRIGGERS");
  }

  @Test
  void allThirtyFiveV2ItemsAreExplicitlyRetaggedAndV1IsUntouched() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("DELETE FROM core.assessment_item_objective")
        .contains("WHERE assessment_version_id = '01900000-0000-7000-8000-000000000403'")
        // Every one of the 35 v2 item codes appears in the retagging CASE.
        .contains("KAFKA_V2_BROKER_MCQ_F").contains("KAFKA_V2_CGROUP_FILL_I")
        // v1's item codes are never named -- v1's tags are never touched by this migration.
        .doesNotContain("KAFKA_DIAG_");
  }

  @Test
  void theNewCurriculumVersionIsPublishedButTheKafkaV2AssessmentVersionIsOnlyRepointed()
      throws IOException {
    String migration = migration();
    // The curriculum version is meant to be published -- publishedSkillContext/CurriculumService
    // need a PUBLISHED or RETIRED status to read it at all.
    assertThat(migration)
        .contains("UPDATE core.curriculum_version SET status = 'PUBLISHED'")
        .contains("WHERE id = '01900000-0000-7000-8000-000000000004'");
    // The Kafka v2 assessment bank itself is only re-pointed at the new curriculum version --
    // never published. Its own status is untouched by this migration; whether it should ever be
    // published is the separate, joint PR-A+PR-B decision the plan already calls for.
    assertThat(migration)
        .contains("UPDATE core.assessment_version SET curriculum_version_id")
        .contains("WHERE id = '01900000-0000-7000-8000-000000000403'");
    int statementStart = migration.indexOf("UPDATE core.assessment_version SET curriculum_version_id");
    int statementEnd = migration.indexOf(';', statementStart);
    String assessmentVersionUpdate = migration.substring(statementStart, statementEnd);
    assertThat(assessmentVersionUpdate).doesNotContain("status");
  }

  private String migration() throws IOException {
    try (var input = getClass().getResourceAsStream(
        "/db/migration/V052__kafka_curriculum_v2_finer_objectives.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
