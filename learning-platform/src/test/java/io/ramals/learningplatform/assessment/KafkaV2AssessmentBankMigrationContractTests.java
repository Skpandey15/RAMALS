package io.ramals.learningplatform.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * V049 authors the Kafka v2 bank and publishes it in the same migration that tags every item with
 * a logical identity and an objective -- so this is the contract test that proves the two
 * publication guards (V017's trust state, V048's lineage) are actually satisfied by real content,
 * not only by a throwaway probe row.
 */
class KafkaV2AssessmentBankMigrationContractTests {

  private static final List<String> ASSESSED_SKILLS = List.of(
      "KAFKA_BROKER", "KAFKA_TOPIC", "KAFKA_PARTITION", "KAFKA_PRODUCER_ACKS",
      "KAFKA_CONSUMER_GROUPS");

  @Test
  void v2VersionIsAuthoredAndLeftDraft() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("'01900000-0000-7000-8000-000000000403'")
        .contains("'v2'")
        // Deliberately not published here. findPublishedDiagnostic orders by published_at, so
        // publishing v2 in this migration would silently replace what every new attempt receives,
        // through a selector (V045) that has no packet-quota awareness of the content this bank
        // was authored for. That is a selector decision for a later change, not a side effect of
        // authoring content.
        .doesNotContain("SET status = 'PUBLISHED'")
        .contains("v2 stays DRAFT");
  }

  @Test
  void everySkillHasExactlyTheApprovedTypeAndDifficultyDistribution() throws IOException {
    String migration = migration();
    for (String skill : ASSESSED_SKILLS) {
      assertThat(countOccurrences(migration, "KAFKA_V2_" + shortCode(skill) + "_MCQ_F"))
          .as("%s: one FOUNDATIONAL SINGLE_CHOICE", skill).isEqualTo(1);
      assertThat(countOccurrences(migration, "KAFKA_V2_" + shortCode(skill) + "_MCQ_I1"))
          .as("%s: INTERMEDIATE SINGLE_CHOICE #1", skill).isEqualTo(1);
      assertThat(countOccurrences(migration, "KAFKA_V2_" + shortCode(skill) + "_MCQ_I2"))
          .as("%s: INTERMEDIATE SINGLE_CHOICE #2", skill).isEqualTo(1);
      assertThat(countOccurrences(migration, "KAFKA_V2_" + shortCode(skill) + "_MCQ_A1"))
          .as("%s: ADVANCED SINGLE_CHOICE #1", skill).isEqualTo(1);
      assertThat(countOccurrences(migration, "KAFKA_V2_" + shortCode(skill) + "_MCQ_A2"))
          .as("%s: ADVANCED SINGLE_CHOICE #2", skill).isEqualTo(1);
      assertThat(countOccurrences(migration, "KAFKA_V2_" + shortCode(skill) + "_FILL_F"))
          .as("%s: FOUNDATIONAL FILL_BLANK", skill).isEqualTo(1);
      assertThat(countOccurrences(migration, "KAFKA_V2_" + shortCode(skill) + "_FILL_I"))
          .as("%s: INTERMEDIATE FILL_BLANK", skill).isEqualTo(1);
    }
  }

  @Test
  void exactlyThirtyFiveItemsAreAuthored() throws IOException {
    Matcher matcher = Pattern.compile("'SINGLE_CHOICE'|'FILL_BLANK'").matcher(migration());
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    // Each item states its type twice: once as the item_type value, once inside its own answer-key
    // shape is NOT restated per item (the shape lives in V047), so this counts the item_type column
    // value only -- one occurrence per authored item.
    assertThat(count).isEqualTo(35);
  }

  @Test
  void everyAuthoredItemDeclaresTrustAndProvenance() throws IOException {
    String migration = migration();
    assertThat(countOccurrences(migration, "kafka-v2-curriculum-authoring")).isEqualTo(35);
    assertThat(countOccurrences(migration, "'VERIFIED_CONTENT'")).isGreaterThanOrEqualTo(35);
  }

  @Test
  void everyItemIsLineagedAndObjectiveTaggedInBulkRatherThanIndividually() throws IOException {
    String migration = migration();
    assertThat(migration)
        .contains("INSERT INTO core.assessment_item_lineage (item_version_id, logical_item_id)")
        .contains("SELECT id, ('01900000-0000-7000-8000-0000000007' || right(id::text, 2))::uuid")
        .contains("INSERT INTO core.assessment_item_objective (item_version_id, objective_id)")
        // Every skill's items are tagged against the same objective V046 tagged its v1 item
        // against -- no new objective is introduced by this migration.
        .contains("'01900000-0000-7000-8000-000000000301'") // BROKER_RESPONSIBILITY
        .contains("'01900000-0000-7000-8000-000000000309'"); // GROUP_ASSIGNMENT
  }

  @Test
  void kafkaProducerAcksReceivesAdvancedContent() throws IOException {
    // The skill that had no ADVANCED item anywhere in the bank, and requires the HARD band its
    // required_difficulty_bands names -- provably unmasterable before this migration.
    assertThat(migration())
        .contains("KAFKA_V2_ACKS_MCQ_A1")
        .contains("KAFKA_V2_ACKS_MCQ_A2")
        .contains("'ADVANCED',25")
        .contains("'ADVANCED',26");
  }

  @Test
  void noItemIsAuthoredAsShortAnswerOrUseCase() throws IOException {
    // Approved scope for this migration: only the deterministically scoreable types. Free-text
    // content is deferred to PR-C, once M2-ADR-022 settles the rubric contract it must be authored
    // against.
    assertThat(migration()).doesNotContain("'SHORT_ANSWER'").doesNotContain("'USE_CASE'");
  }

  private static String shortCode(String skill) {
    return switch (skill) {
      case "KAFKA_BROKER" -> "BROKER";
      case "KAFKA_TOPIC" -> "TOPIC";
      case "KAFKA_PARTITION" -> "PARTITION";
      case "KAFKA_PRODUCER_ACKS" -> "ACKS";
      case "KAFKA_CONSUMER_GROUPS" -> "CGROUP";
      default -> throw new IllegalArgumentException("unmapped skill: " + skill);
    };
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int index = 0;
    while ((index = haystack.indexOf(needle, index)) != -1) {
      count++;
      index += needle.length();
    }
    return count;
  }

  private String migration() throws IOException {
    try (var input =
        getClass().getResourceAsStream("/db/migration/V049__kafka_v2_assessment_bank.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
  }
}
